"""Foreground paired IELTS host; no daemon, certificate generation or import-time work.

The caller supplies TLS files, existing credential files and a private state
directory. One flock owner and one worker process the durable queue. Startup
reconciliation never requeues a possibly accepted model call. An unreaped child
blocks the next startup instead of permitting guessed-PID cleanup or replay.

Tests inject a fake runner/server. The production defaults use the real Harness
pipeline only when serve() or the explicit CLI is called. Credentials are read
once into memory and are never included in capabilities, output or exceptions.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import signal
import ssl
import sys
import threading
from typing import Any, Optional

from harness_ielts import GENERATION_MODELS, REVIEW_MODELS, REASONING_EFFORTS, verify_native_version
from harness_model_proxy import contains_credential
from ielts_contract import READING_TYPES, TASK2_TYPES
from ielts_corpus import load as load_corpus, read as read_corpus
from ielts_http import HttpContractError, create_server, validate_submit
from ielts_jobs import ExerciseJobs, JobError
from ielts_pipeline import normalize_submission, run_job


class HostError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class HostConfig:
    tls_cert: Path
    tls_private_key: Path
    pairing_token_file: Path
    api_key_file: Path
    state_dir: Path
    corpus: Path
    bind: str = '127.0.0.1'
    port: int = 8443
    max_calls: int = 12
    timeout: int = 240
    shutdown_timeout: int = 30
    review_model: str = 'flash'
    review_reasoning: str = 'off'
    generation_model: str = 'flash'
    explanation_model: str = 'pro'
    explanation_reasoning: str = 'high'
    quality_revisions: int = 1


def _bounded_file(path, maximum):
    try:
        with Path(path).open('rb') as stream:
            raw = stream.read(maximum + 1)
    except OSError:
        raise HostError('configured_file_unavailable') from None
    if len(raw) > maximum:
        raise HostError('configured_file_too_large')
    return raw


def read_credential(path):
    """Read an existing, explicitly selected credential file; create no copies."""
    try:
        value = _bounded_file(path, 4096).decode('utf-8', errors='strict').strip()
    except UnicodeError:
        raise HostError('credential_file_invalid') from None
    if not 1 <= len(value) <= 1024 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise HostError('credential_file_invalid')
    return value


class OwnershipLock:
    """The lock file is not a PID file. No owner lookup, PID reuse or killing."""
    def __init__(self, path):
        self.path, self.fd = Path(path), None

    def acquire(self):
        if self.fd is not None:
            raise HostError('owner_lock_already_held')
        flags = os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0)
        fd = None
        try:
            fd = os.open(str(self.path), flags, 0o600)
            os.set_inheritable(fd, False)
        except OSError:
            if fd is not None:
                os.close(fd)
            raise HostError('owner_lock_unavailable') from None
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            raise HostError('another_host_owns_state') from None
        self.fd = fd
        return self

    def release(self):
        if self.fd is not None:
            fd, self.fd = self.fd, None
            try:
                fcntl.flock(fd, fcntl.LOCK_UN)
            finally:
                os.close(fd)


def load_tls_context(cert, private_key):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        # An encrypted key must not cause a hidden interactive password prompt.
        context.load_cert_chain(str(cert), str(private_key), password=lambda: '')
    except (OSError, ValueError, ssl.SSLError):
        raise HostError('tls_material_invalid') from None
    return context


def validate_config(config):
    if not isinstance(config, HostConfig):
        raise HostError('host_config_invalid')
    if (not isinstance(config.generation_model, str) or config.generation_model not in GENERATION_MODELS or
            not isinstance(config.review_model, str) or config.review_model not in REVIEW_MODELS or
            not isinstance(config.review_reasoning, str) or config.review_reasoning not in REASONING_EFFORTS or
            not isinstance(config.explanation_model, str) or config.explanation_model not in REVIEW_MODELS or
            not isinstance(config.explanation_reasoning, str) or config.explanation_reasoning not in REASONING_EFFORTS):
        raise HostError('host_model_invalid')
    try:
        ipaddress.IPv4Address(config.bind)
    except (ValueError, TypeError):
        raise HostError('bind_ipv4_required') from None
    for value, low, high in ((config.port, 1, 65535), (config.max_calls, 1, 16),
                             (config.timeout, 10, 600), (config.shutdown_timeout, 1, 120),
                             (config.quality_revisions, 0, 1)):
        if type(value) is not int or not low <= value <= high:
            raise HostError('host_limit_invalid')


class HostService:
    """One serial worker; HTTP threads may only normalize and durably enqueue."""
    def __init__(self, jobs, *, corpus_path, corpus_sha256, key, token, work_root,
                 normalizer=normalize_submission, runner=run_job, max_calls=12,
                 timeout=240, stop_event=None, generation_model=GENERATION_MODELS['flash'],
                 review_model='deepseek-flash', review_reasoning='off',
                 explanation_model=REVIEW_MODELS['pro'], explanation_reasoning='high', quality_revisions=1):
        if type(quality_revisions) is not int or quality_revisions not in (0, 1):
            raise HostError('host_limit_invalid')
        if (not isinstance(generation_model, str) or generation_model not in GENERATION_MODELS.values() or
                review_model not in REVIEW_MODELS.values() or review_reasoning not in REASONING_EFFORTS or
                explanation_model not in REVIEW_MODELS.values() or explanation_reasoning not in REASONING_EFFORTS):
            raise HostError('host_model_invalid')
        self.jobs = jobs
        self.corpus_path, self.corpus_sha256 = Path(corpus_path), corpus_sha256
        self._key, self._token = key, token
        self.work_root = Path(work_root)
        self.normalizer, self.runner = normalizer, runner
        self.max_calls, self.timeout = max_calls, timeout
        self.quality_revisions = quality_revisions
        self.generation_model = generation_model
        self.review_model, self.review_reasoning = review_model, review_reasoning
        self.explanation_model, self.explanation_reasoning = explanation_model, explanation_reasoning
        self.stop_event = stop_event if stop_event is not None else threading.Event()
        self.wake = threading.Event()
        self.lock = threading.Lock()
        self.accepting = False
        self.active_job = None
        self.thread = None
        self.failure = None
        self.completed_jobs = 0

    def _contains_secret(self, value):
        try:
            raw = json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
        except (ValueError, TypeError, UnicodeError, RecursionError):
            raise HttpContractError('input_invalid') from None
        return contains_credential(raw, self._key) or contains_credential(raw, self._token)

    def on_submit(self, value):
        with self.lock:
            if not self.accepting or self.stop_event.is_set():
                raise HttpContractError('gateway_stopping', 503)
        payload = validate_submit(value)
        if self._contains_secret(payload):
            raise HttpContractError('credential_in_input')
        request, body, digest = self.normalizer(payload, self.corpus_path)
        if digest != self.corpus_sha256:
            raise HttpContractError('corpus_changed', 409)
        if self._contains_secret({'request': request, 'body': body}):
            raise HttpContractError('credential_in_input')
        with self.lock:
            # Shutdown may start during normalization. Only the committed enqueue
            # precedes return; neither socket lifetime nor retries launch a worker.
            if not self.accepting or self.stop_event.is_set():
                raise HttpContractError('gateway_stopping', 503)
            result = self.jobs.submit(request, body, digest)
        self.wake.set()
        return result

    def start(self):
        with self.lock:
            if self.thread is not None or self.stop_event.is_set():
                raise HostError('worker_already_started_or_stopped')
            self.accepting = True
            self.thread = threading.Thread(target=self._work, name='lexicon-ielts-worker', daemon=False)
            self.thread.start()

    def request_stop(self):
        with self.lock:
            self.accepting = False
            self.stop_event.set()
            active = self.active_job
        self.wake.set()
        if active is not None:
            try:
                self.jobs.cancel(active)
            except Exception:
                with self.lock:
                    self.failure = self.failure or 'cancellation_unconfirmed'

    def _fail_worker(self, code):
        with self.lock:
            self.failure = code
            self.accepting = False
            self.stop_event.set()
        self.wake.set()

    def _work(self):
        try:
            while not self.stop_event.is_set():
                self.wake.clear()
                with self.lock:
                    if self.stop_event.is_set():
                        break
                    queued = self.jobs.queued(limit=1)
                    job = queued[0] if queued else None
                    if job is not None:
                        self.active_job = job['id']
                if job is None:
                    self.wake.wait(0.5)
                    continue
                raised = False
                try:
                    self.runner(self.jobs, job['id'], corpus_path=self.corpus_path, key=self._key,
                                work_root=self.work_root, max_calls=self.max_calls, timeout=self.timeout,
                                generation_model=self.generation_model,
                                review_model=self.review_model, review_reasoning=self.review_reasoning,
                                explanation_model=self.explanation_model, explanation_reasoning=self.explanation_reasoning,
                                quality_revisions=self.quality_revisions)
                except Exception:
                    # No diagnostics from a provider/process are written or printed.
                    # Read authoritative state; an exception is not proof of reaping.
                    raised = True
                state = self.jobs.get(job['id'])
                if state['process_reaped'] != 1:
                    self._fail_worker('child_completion_unconfirmed')
                    break
                if state['state'] not in ('ready', 'failed', 'cancelled', 'interrupted'):
                    self._fail_worker('worker_completion_unconfirmed')
                    break
                if raised and state['state'] not in ('cancelled', 'interrupted'):
                    self._fail_worker('worker_failed')
                    break
                with self.lock:
                    self.active_job = None
                    self.completed_jobs += 1
        except Exception:
            self._fail_worker('worker_store_failed')

    def close(self, timeout=30):
        self.request_stop()
        if self.thread is not None:
            self.thread.join(timeout=timeout)
        joined = self.thread is None or not self.thread.is_alive()
        reaped = False
        if joined:
            # Only after the owned worker exits; never while a stage is running.
            try:
                self.jobs.recover_interrupted()
                reaped = not self.jobs.unreaped(limit=1)
            except Exception:
                with self.lock:
                    self.failure = self.failure or 'shutdown_store_unavailable'
        return {'schema': 'lexicon.ielts.host-stop.v1',
                'status': 'stopped' if joined and reaped else 'shutdown_unconfirmed',
                'worker_joined': joined, 'children_reaped_confirmed': reaped,
                'completed_jobs': self.completed_jobs, 'error': self.failure}


def serve(config, *, jobs_factory=ExerciseJobs, normalizer=normalize_submission,
          runner=run_job, server_factory=create_server, tls_factory=load_tls_context,
          runtime_probe=verify_native_version, stop_event=None, install_signals=True,
          announce=None):
    """Run a foreground service. Dependencies are injected only by the trusted host."""
    validate_config(config)
    key = read_credential(config.api_key_file)
    token = read_credential(config.pairing_token_file)
    corpus_raw = _bounded_file(config.corpus, 262_144)
    corpus_sha = hashlib.sha256(corpus_raw).hexdigest()
    corpus = load_corpus(config.corpus, expected_sha256=corpus_sha)
    corpus_id = read_corpus(corpus, 'ielts.coverage')['corpus_id']
    state = Path(config.state_dir)
    try:
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(str(state), 0o700)
    except OSError:
        raise HostError('state_directory_unavailable') from None
    owner = OwnershipLock(state / '.gateway-owner.lock').acquire()
    host, server, result = None, None, None
    old_handlers = {}
    try:
        jobs = jobs_factory(state / 'jobs.sqlite')
        interrupted = jobs.recover_interrupted()
        if jobs.unreaped(limit=1):
            raise HostError('previous_child_completion_unconfirmed')
        version = runtime_probe()
        tls = tls_factory(config.tls_cert, config.tls_private_key)
        capabilities = {
            'runtime_version': version, 'corpus_id': corpus_id, 'corpus_sha256': corpus_sha,
            'supported_reading_types': list(READING_TYPES), 'supported_writing_task2_types': list(TASK2_TYPES),
            'supported_writing_task1_policies': ['source_numeric_visual_v1'],
            'corpus_scope': 'official_public_samples_and_format_metadata',
        }
        host = HostService(jobs, corpus_path=config.corpus, corpus_sha256=corpus_sha,
                           key=key, token=token, work_root=state / 'work', normalizer=normalizer,
                           runner=runner, max_calls=config.max_calls, timeout=config.timeout,
                           stop_event=stop_event, generation_model=GENERATION_MODELS[config.generation_model],
                           review_model=REVIEW_MODELS[config.review_model],
                           review_reasoning=config.review_reasoning,
                           explanation_model=REVIEW_MODELS[config.explanation_model],
                           explanation_reasoning=config.explanation_reasoning, quality_revisions=config.quality_revisions)
        server = server_factory(config.bind, config.port, tls_context=tls, token=token,
                                jobs=jobs, on_submit=host.on_submit, capabilities=capabilities)
        server.timeout = 0.25
        if install_signals and threading.current_thread() is threading.main_thread():
            for signum in (signal.SIGINT, signal.SIGTERM):
                old_handlers[signum] = signal.getsignal(signum)
                signal.signal(signum, lambda *_: host.stop_event.set())
        host.start()
        if announce is not None:
            announce({'schema': 'lexicon.ielts.host-start.v1', 'status': 'listening',
                      'bind': config.bind, 'port': config.port, 'runtime_version': version,
                      'corpus_id': corpus_id, 'interrupted_jobs_reconciled': interrupted,
                      'generation_model': host.generation_model, 'generation_reasoning': 'off',
                      'review_model': host.review_model, 'review_reasoning': host.review_reasoning,
                      'explanation_model': host.explanation_model, 'explanation_reasoning': host.explanation_reasoning,
                      'quality_revisions': host.quality_revisions})
        while not host.stop_event.is_set():
            server.handle_request()
    finally:
        if host is not None:
            host.request_stop()
        if server is not None:
            try:
                server.server_close()
            except Exception:
                if host is not None:
                    with host.lock:
                        host.failure = host.failure or 'listener_close_failed'
        if host is not None:
            result = host.close(timeout=config.shutdown_timeout)
        for signum, previous in old_handlers.items():
            signal.signal(signum, previous)
        if host is None or (result is not None and result['worker_joined'] and result['children_reaped_confirmed']):
            owner.release()
        # If shutdown is unconfirmed the raw lock FD remains held until this
        # process ends. Persisted unreaped state blocks the next owner as well.
    return result


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default may echo unknown arguments containing inline keys.
        raise HostError('cli_arguments_invalid')


def parse_config(argv=None):
    parser = _Parser(description=__doc__)
    for flag in ('tls-cert', 'tls-private-key', 'pairing-token-file', 'api-key-file', 'state-dir', 'corpus'):
        parser.add_argument('--' + flag, required=True, type=Path)
    parser.add_argument('--bind', default='127.0.0.1', help='Explicit IPv4 bind; set this to expose the paired TLS service on LAN')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--max-calls', type=int, default=12)
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--quality-revisions', type=int, choices=(0, 1), default=1,
                        help='At most one feedback-guided content revision, within the existing request and time budgets')
    parser.add_argument('--shutdown-timeout', type=int, default=30)
    parser.add_argument('--generation-model', choices=tuple(GENERATION_MODELS), default='flash')
    parser.add_argument('--review-model', choices=tuple(REVIEW_MODELS), default='flash', help='Independent answer review model; default: flash')
    parser.add_argument('--review-reasoning', choices=REASONING_EFFORTS, default='off', help='Independent answer review effort; default: off')
    parser.add_argument('--explanation-model', choices=tuple(REVIEW_MODELS), default='pro', help='Post-blind explanation review model; default: pro')
    parser.add_argument('--explanation-reasoning', choices=REASONING_EFFORTS, default='high', help='Post-blind explanation review effort; default: high')
    return HostConfig(**vars(parser.parse_args(argv)))


def main(argv=None):
    try:
        config = parse_config(argv)
        result = serve(config, announce=lambda item: print(json.dumps(item, ensure_ascii=False), flush=True))
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return 0 if result['status'] == 'stopped' and result['error'] is None else 1
    except Exception:
        print('{"schema":"lexicon.ielts.host-error.v1","error":"host_configuration_or_execution_failed"}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
