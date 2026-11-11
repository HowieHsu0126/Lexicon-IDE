"""Local durable exercise jobs. No model, network, or Android database access.

One request ID names one immutable input. Claims and completion use random lease
tokens, so a late worker cannot publish over a cancellation or another worker.
Restart reconciliation deliberately never resends a possibly accepted model call.
"""
from __future__ import annotations

import copy
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import uuid

from ielts_contract import canonical_sha256, utf16_slice, validate_request


class JobError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def verify_body(request, body):
    validate_request(request)
    if not isinstance(body, str) or len(body.encode('utf-8')) > 200_000:
        raise JobError('body_bound')
    if hashlib.sha256(body.encode('utf-8')).hexdigest() != request['body_hash']:
        raise JobError('body_hash_mismatch')
    for paragraph in request['paragraphs']:
        if utf16_slice(body, paragraph['start'], paragraph['end']) != paragraph['text']:
            raise JobError('paragraph_body_mismatch')


def _json(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


class ExerciseJobs:
    """Each operation opens its own transaction; safe for a small worker + HTTP host."""

    def __init__(self, path, clock=time.time):
        self.path, self.clock = Path(path), clock
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('''CREATE TABLE IF NOT EXISTS exercise_jobs(
                id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE,
                input_hash TEXT NOT NULL, input_json TEXT NOT NULL,
                state TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
                lease TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                error TEXT, output_json TEXT, process_reaped INTEGER NOT NULL DEFAULT 1,
                process_token TEXT)''')
            db.execute('''CREATE TABLE IF NOT EXISTS stage_receipts(
                job_id TEXT NOT NULL, ordinal INTEGER NOT NULL, receipt_json TEXT NOT NULL,
                PRIMARY KEY(job_id,ordinal), FOREIGN KEY(job_id) REFERENCES exercise_jobs(id))''')

    @contextmanager
    def _db(self):
        db = sqlite3.connect(str(self.path), timeout=10)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _public(row):
        if row is None:
            raise JobError('job_not_found')
        return {k: row[k] for k in ('id', 'request_id', 'input_hash', 'state', 'revision',
                                   'created_at', 'updated_at', 'error', 'process_reaped')}

    def submit(self, request, body, corpus_hash):
        verify_body(request, body)
        if not isinstance(corpus_hash, str) or not re.fullmatch('[a-f0-9]{64}', corpus_hash):
            raise JobError('corpus_hash_invalid')
        envelope = {'request': copy.deepcopy(request), 'body': body, 'corpus_hash': corpus_hash}
        digest, now = canonical_sha256(envelope), int(self.clock() * 1000)
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM exercise_jobs WHERE request_id=?', (request['request_id'],)).fetchone()
            if row:
                if row['input_hash'] != digest:
                    raise JobError('request_id_conflict')
                return self._public(row)
            job_id = 'job-' + uuid.uuid4().hex
            db.execute('INSERT INTO exercise_jobs(id,request_id,input_hash,input_json,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?)',
                       (job_id, request['request_id'], digest, _json(envelope), 'queued', now, now))
            return self._public(db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone())

    def get(self, job_id):
        with self._db() as db:
            return self._public(db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone())

    def by_request(self, request_id):
        with self._db() as db:
            return self._public(db.execute('SELECT * FROM exercise_jobs WHERE request_id=?', (request_id,)).fetchone())

    def queued(self, limit=1):
        if type(limit) is not int or not 1 <= limit <= 128: raise JobError('query_bound')
        with self._db() as db:
            return [self._public(row) for row in db.execute("SELECT * FROM exercise_jobs WHERE state='queued' ORDER BY created_at,id LIMIT ?", (limit,))]

    def unreaped(self, limit=1):
        if type(limit) is not int or not 1 <= limit <= 128: raise JobError('query_bound')
        with self._db() as db:
            return [self._public(row) for row in db.execute('SELECT * FROM exercise_jobs WHERE process_reaped=0 ORDER BY created_at,id LIMIT ?', (limit,))]

    def claim(self, job_id):
        lease = uuid.uuid4().hex
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            changed = db.execute("UPDATE exercise_jobs SET state='generating',lease=?,revision=revision+1,updated_at=? WHERE id=? AND state='queued'",
                                 (lease, int(self.clock() * 1000), job_id)).rowcount
            if not changed:
                raise JobError('job_not_queued')
            row = db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone()
            return lease, json.loads(row['input_json'])

    def set_process_active(self, job_id, lease):
        token = uuid.uuid4().hex
        with self._db() as db:
            changed = db.execute("UPDATE exercise_jobs SET process_reaped=0,process_token=? WHERE id=? AND lease=? AND state IN ('generating','reviewing') AND process_reaped=1 AND process_token IS NULL",
                                 (token, job_id, lease)).rowcount
            if not changed:
                raise JobError('worker_conflict')
        return token

    def record_stage(self, job_id, lease, process_token, receipt, *, reaped):
        """Only the process owner attests reaping. A JSON from the model never calls this."""
        if type(reaped) is not bool or not reaped:
            raise JobError('process_not_reaped')
        encoded = _json(receipt)
        if len(encoded.encode()) > 64_000:
            raise JobError('receipt_bound')
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM exercise_jobs WHERE id=? AND lease=?', (job_id, lease)).fetchone()
            if (row is None or row['state'] not in ('generating', 'reviewing', 'cancelling') or
                    row['process_reaped'] != 0 or row['process_token'] != process_token or not process_token):
                raise JobError('worker_conflict')
            ordinal = db.execute('SELECT count(*) FROM stage_receipts WHERE job_id=?', (job_id,)).fetchone()[0]
            db.execute('INSERT INTO stage_receipts VALUES(?,?,?)', (job_id, ordinal, encoded))
            db.execute('UPDATE exercise_jobs SET process_reaped=1,process_token=NULL,updated_at=? WHERE id=?', (int(self.clock() * 1000), job_id))

    def begin_review(self, job_id, lease):
        with self._db() as db:
            changed = db.execute("UPDATE exercise_jobs SET state='reviewing',revision=revision+1,updated_at=? WHERE id=? AND lease=? AND state='generating' AND process_reaped=1",
                                 (int(self.clock() * 1000), job_id, lease)).rowcount
            if not changed:
                raise JobError('worker_conflict')

    def cancel(self, job_id):
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone()
            self._public(row)
            if row['state'] == 'queued':
                state = 'cancelled'
            elif row['state'] in ('generating', 'reviewing'):
                state = 'cancelling'
            else:
                return self._public(row)
            db.execute('UPDATE exercise_jobs SET state=?,revision=revision+1,updated_at=? WHERE id=?',
                       (state, int(self.clock() * 1000), job_id))
            return self._public(db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone())

    def cancellation_requested(self, job_id, lease):
        with self._db() as db:
            row = db.execute('SELECT state,lease FROM exercise_jobs WHERE id=?', (job_id,)).fetchone()
            return row is None or row['lease'] != lease or row['state'] not in ('generating', 'reviewing')

    def finish(self, job_id, lease, *, output=None, error=None):
        if error is not None and (not isinstance(error, str) or not re.fullmatch('[a-z0-9_]{1,80}', error)):
            raise JobError('error_code_invalid')
        encoded = None if output is None else _json(output)
        if encoded and len(encoded.encode()) > 2_000_000:
            raise JobError('output_bound')
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM exercise_jobs WHERE id=? AND lease=?', (job_id, lease)).fetchone()
            if row is None or row['state'] not in ('generating', 'reviewing', 'cancelling'):
                raise JobError('worker_conflict')
            if not row['process_reaped']:
                raise JobError('process_not_reaped')
            if row['state'] == 'cancelling':
                state, encoded, error = 'cancelled', None, None
            elif error is not None:
                state, encoded = 'failed', None
            elif row['state'] == 'reviewing' and encoded is not None:
                state = 'ready'
            else:
                raise JobError('review_required')
            db.execute('UPDATE exercise_jobs SET state=?,output_json=?,error=?,lease=NULL,revision=revision+1,updated_at=? WHERE id=?',
                       (state, encoded, error, int(self.clock() * 1000), job_id))
            return self._public(db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone())

    def package(self, job_id):
        with self._db() as db:
            row = db.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone()
            self._public(row)
            if row['state'] != 'ready' or not row['output_json']:
                raise JobError('package_unavailable')
            return json.loads(row['output_json'])

    def receipts(self, job_id):
        self.get(job_id)
        with self._db() as db:
            return [json.loads(r[0]) for r in db.execute('SELECT receipt_json FROM stage_receipts WHERE job_id=? ORDER BY ordinal', (job_id,))]

    def interrupt_unconfirmed(self, job_id, lease):
        """Preserve an uncertain child state; never turn uncertainty into cancellation."""
        with self._db() as db:
            changed = db.execute("UPDATE exercise_jobs SET state='interrupted',error='process_completion_unconfirmed',lease=NULL,revision=revision+1,updated_at=? WHERE id=? AND lease=? AND state IN ('generating','reviewing','cancelling')",
                                 (int(self.clock() * 1000), job_id, lease)).rowcount
            if not changed: raise JobError('worker_conflict')
        return self.get(job_id)

    def recover_interrupted(self):
        """Call under an exclusive gateway-owner lock after restart, never during work.

        Does not kill a guessed PID, claim remote cancellation, or resend input.
        Retains process_reaped so prior unconfirmed shutdown remains visible.
        """
        with self._db() as db:
            return db.execute("UPDATE exercise_jobs SET state='interrupted',error='owner_restarted',lease=NULL,revision=revision+1,updated_at=? WHERE state IN ('generating','reviewing','cancelling')",
                              (int(self.clock() * 1000),)).rowcount
