"""Paired HTTPS transport for a host-owned durable IELTS exercise gateway.

No server, worker, model, or external connection starts on import. create_server
returns an unstarted TLS-only server. The injected on_submit(payload) callback
normalizes and durably enqueues exactly one request; this module never claims a
job or retries a callback. The client reconciles an uncertain submission through
the same request_id. Use generate_pairing_token() for 32 random token bytes.

/package delivers a verified full artifact to the paired app's private storage
for offline practice. It is not a learner-facing question view. /candidate uses
the answer-free contract projection; the app reveals answers only after its
own persisted submission. Provenance validation checks a trusted host's receipt
structure and binding, not independent proof that a model call happened.
"""
from __future__ import annotations

import base64
import copy
import http.server
import json
import re
import secrets
import ssl
from typing import Any, Dict

try:
    from . import ielts_contract as contract
    from .ielts_jobs import JobError
except ImportError:
    import ielts_contract as contract
    from ielts_jobs import JobError


SUBMIT_SCHEMA = 'lexicon.ielts.submit.v1'
VERIFIED_SCHEMA = 'lexicon.ielts.verified-package.v1'
MAX_REQUEST_BYTES = 500_000
MAX_BODY_BYTES = 200_000
MAX_RESPONSE_BYTES = 2_000_000
SOCKET_TIMEOUT = 5
_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z')
_JOB_ID = re.compile(r'job-[a-f0-9]{32}\Z')
_SHA = re.compile(r'[a-f0-9]{64}\Z')
_TOKEN = re.compile(r'[A-Za-z0-9_-]{43,128}\Z')
_VERSION = re.compile(r'[A-Za-z0-9][A-Za-z0-9._+:-]{0,63}\Z')
_STATES = ('queued', 'generating', 'reviewing', 'ready', 'cancelling', 'cancelled', 'failed', 'interrupted')
_JOB_FIELDS = ('id', 'request_id', 'input_hash', 'state', 'revision', 'created_at', 'updated_at', 'error', 'process_reaped')
_CAP_FIELDS = {'runtime_version', 'corpus_id', 'corpus_sha256', 'supported_reading_types',
               'supported_writing_task2_types', 'corpus_scope'}
_PROVENANCE_FIELDS = {'kind', 'runtime_version', 'generation_session_id', 'review_session_id',
                      'request_sha256', 'package_sha256', 'review_sha256', 'corpus_sha256',
                      'semantic_review', 'recent_three_year_coverage', 'real_provider_request_count', 'created_at',
                      'explanation_review_session_id', 'explanation_review_sha256', 'explanation_review'}


class HttpContractError(ValueError):
    """Fixed diagnostics only; never include material, pairing tokens or exceptions."""
    def __init__(self, code, status=400):
        self.code, self.status = code, status
        super().__init__(code)


def _fail(code, status=400):
    raise HttpContractError(code, status)


def _object(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields):
        _fail('invalid_fields')
    return value


def _identifier(value, *, job=False):
    if not isinstance(value, str) or (not _JOB_ID.fullmatch(value) if job else not _ID.fullmatch(value)):
        _fail('invalid_id')
    return value


def _integer(value, low=0, high=9_007_199_254_740_991):
    if type(value) is not int or not low <= value <= high:
        _fail('invalid_integer')
    return value


def _digest(value):
    if not isinstance(value, str) or not _SHA.fullmatch(value):
        _fail('invalid_digest')
    return value


def _array(value, minimum, maximum):
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        _fail('invalid_list')
    return value


def _units(text, maximum, *, allow_empty=False):
    if not isinstance(text, str) or (not allow_empty and not text.strip()):
        _fail('invalid_text')
    try:
        units = contract.utf16_length(text)
    except contract.ContractError:
        _fail('invalid_text')
    if units > maximum:
        _fail('text_bound')
    return units


def decode_submit(raw: bytes) -> Dict[str, Any]:
    """Parse the bounded JSON envelope, rejecting duplicate keys at every level."""
    if not isinstance(raw, bytes):
        _fail('invalid_json')
    if len(raw) > MAX_REQUEST_BYTES:
        _fail('request_too_large', 413)
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                _fail('duplicate_field')
            value[key] = item
        return value
    try:
        value = json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=unique,
                           parse_constant=lambda _: _fail('invalid_json'))
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, HttpContractError):
            raise
        _fail('invalid_json')
    return validate_submit(value)


def validate_submit(value: Any) -> Dict[str, Any]:
    """Validate client material without inventing official style-source inputs."""
    payload = _object(value, {'schema', 'request_id', 'article_id', 'body', 'paragraphs',
                              'reading_requests', 'writing_task2_request'} | ({'writing_task1_request'} if isinstance(value, dict) and 'writing_task1_request' in value else set()))
    if payload['schema'] != SUBMIT_SCHEMA:
        _fail('invalid_schema')
    _identifier(payload['request_id'])
    _identifier(payload['article_id'])
    body_units = _units(payload['body'], MAX_BODY_BYTES)
    if len(payload['body'].encode('utf-8')) > MAX_BODY_BYTES:
        _fail('body_too_large', 413)
    paragraphs = _array(payload['paragraphs'], 1, contract.MAX_PARAGRAPHS)
    seen, previous_end, selected_units = set(), -1, 0
    for p in paragraphs:
        _object(p, {'paragraph_id', 'start', 'end', 'text'})
        pid = _identifier(p['paragraph_id'])
        if pid in seen:
            _fail('duplicate_id')
        seen.add(pid)
        start = _integer(p['start'], 0, body_units)
        end = _integer(p['end'], start + 1, body_units)
        units = _units(p['text'], contract.MAX_ARTICLE_UNITS)
        if end - start != units or start < previous_end:
            _fail('paragraph_range')
        try:
            exact = contract.utf16_slice(payload['body'], start, end)
        except contract.ContractError:
            _fail('paragraph_range')
        if exact != p['text']:
            _fail('paragraph_body_mismatch')
        selected_units += units
        previous_end = end
    if selected_units > contract.MAX_ARTICLE_UNITS:
        _fail('selection_too_large', 413)
    question_ids = set()
    for q in _array(payload['reading_requests'], 1, contract.MAX_QUESTIONS):
        if not isinstance(q, dict):
            _fail('invalid_fields')
        fields = {'question_id', 'type'}
        if q.get('type') == 'short_text_completion':
            fields |= {'max_words', 'allow_numbers'}
        _object(q, fields)
        qid = _identifier(q['question_id'])
        if qid in question_ids:
            _fail('duplicate_id')
        question_ids.add(qid)
        if q['type'] not in contract.READING_TYPES:
            _fail('unsupported_reading_type')
        if q['type'] == 'short_text_completion':
            _integer(q['max_words'], 1, 8)
            if type(q['allow_numbers']) is not bool:
                _fail('invalid_boolean')
    writing = _object(payload['writing_task2_request'], {'task_id', 'task_type'})
    if _identifier(writing['task_id']) in question_ids:
        _fail('duplicate_id')
    if writing['task_type'] not in contract.TASK2_TYPES:
        _fail('unsupported_writing_type')
    if 'writing_task1_request' in payload:
        from ielts_visual import validate_request_spec
        validate_request_spec(payload)
    return copy.deepcopy(payload)


def generate_pairing_token() -> str:
    return secrets.token_urlsafe(32)


def _pairing_token(token):
    if not isinstance(token, str) or not _TOKEN.fullmatch(token):
        _fail('pairing_token_invalid')
    try:
        decoded = base64.b64decode(token + '=' * (-len(token) % 4), altchars=b'-_', validate=True)
    except ValueError:
        _fail('pairing_token_invalid')
    if len(decoded) < 32:
        _fail('pairing_token_invalid')
    # Representation/size validation cannot prove that an externally supplied
    # token was randomly generated. The host should use generate_pairing_token.
    return token.encode('ascii')


def _capabilities(value):
    c = copy.deepcopy(_object(value, _CAP_FIELDS | ({'supported_writing_task1_policies'} if isinstance(value, dict) and 'supported_writing_task1_policies' in value else set())))
    if not isinstance(c['runtime_version'], str) or not _VERSION.fullmatch(c['runtime_version']):
        _fail('capabilities_invalid')
    _identifier(c['corpus_id'])
    _digest(c['corpus_sha256'])
    for field, allowed in (('supported_reading_types', contract.READING_TYPES),
                           ('supported_writing_task2_types', contract.TASK2_TYPES)):
        values = _array(c[field], 0, len(allowed))
        if any(not isinstance(v, str) or v not in allowed for v in values) or len(values) != len(set(values)):
            _fail('capabilities_invalid')
    if c['corpus_scope'] != 'official_public_samples_and_format_metadata':
        _fail('capabilities_invalid')
    if 'supported_writing_task1_policies' in c and c['supported_writing_task1_policies'] != ['source_numeric_visual_v1']:
        _fail('capabilities_invalid')
    return c


def public_job(value):
    if not isinstance(value, dict) or any(k not in value for k in _JOB_FIELDS):
        _fail('job_receipt_invalid', 500)
    result = {key: copy.deepcopy(value[key]) for key in _JOB_FIELDS}
    try:
        _identifier(result['id'], job=True)
        _identifier(result['request_id'])
        _digest(result['input_hash'])
        for key in ('revision', 'created_at', 'updated_at'):
            _integer(result[key])
        if result['state'] not in _STATES or type(result['process_reaped']) is not int or result['process_reaped'] not in (0, 1):
            _fail('job_receipt_invalid')
        if result['error'] is not None and (not isinstance(result['error'], str) or not re.fullmatch('[a-z0-9_]{1,80}', result['error'])):
            _fail('job_receipt_invalid')
    except HttpContractError:
        _fail('job_receipt_invalid', 500)
    return result


def verified_package(value, job, capabilities):
    """Validate the trusted host's full offline artifact; never expose private traces."""
    try:
        wrapper = _object(value, {'schema', 'request', 'package', 'provenance'})
        if wrapper['schema'] != VERIFIED_SCHEMA:
            _fail('package_receipt_invalid')
        provenance = _object(wrapper['provenance'], _PROVENANCE_FIELDS)
        if provenance['kind'] != 'deepseek_harness':
            _fail('package_receipt_invalid')
        if provenance['runtime_version'] != capabilities['runtime_version']:
            _fail('package_receipt_invalid')
        generation = _identifier(provenance['generation_session_id'])
        reviewer = _identifier(provenance['review_session_id'])
        explanation_reviewer = _identifier(provenance['explanation_review_session_id'])
        if len({generation, reviewer, explanation_reviewer}) != 3:
            _fail('package_receipt_invalid')
        for key in ('request_sha256', 'package_sha256', 'review_sha256', 'corpus_sha256', 'explanation_review_sha256'):
            _digest(provenance[key])
        if provenance['corpus_sha256'] != capabilities['corpus_sha256']:
            _fail('package_receipt_invalid')
        if provenance['semantic_review'] != 'independent_answers_agree' or provenance['recent_three_year_coverage'] is not False:
            _fail('package_receipt_invalid')
        if provenance['explanation_review'] != 'source_faithfulness_checked':
            _fail('package_receipt_invalid')
        _integer(provenance['real_provider_request_count'], 3, 16)
        _integer(provenance['created_at'])
        receipt = contract.validate_package(wrapper['request'], wrapper['package'])
        if wrapper['request']['request_id'] != job['request_id']:
            _fail('package_receipt_invalid')
        for key in ('request_sha256', 'package_sha256'):
            if provenance[key] != receipt[key]:
                _fail('package_receipt_invalid')
        encoded = json.dumps(wrapper, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
        if len(encoded) > MAX_RESPONSE_BYTES:
            _fail('package_receipt_invalid')
    except (HttpContractError, contract.ContractError, ValueError, TypeError, RecursionError, UnicodeError):
        _fail('package_receipt_invalid', 502)
    return copy.deepcopy(wrapper)


def make_handler(*, token, jobs, on_submit, capabilities):
    """Inject transport dependencies; use create_server for the production TLS listener."""
    expected_token = _pairing_token(token)
    declared = _capabilities(capabilities)
    if not callable(on_submit):
        _fail('submit_callback_required')

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = 'LexiconGateway'
        sys_version = ''

        def setup(self):
            self.request.settimeout(SOCKET_TIMEOUT)
            super().setup()

        def log_message(self, *args):
            pass

        def _authenticated(self):
            headers = getattr(self, 'headers', None)
            supplied = [] if headers is None else headers.get_all('Authorization', [])
            if len(supplied) != 1 or not supplied[0].startswith('Bearer '):
                return False
            try:
                candidate = supplied[0][7:].encode('ascii', errors='strict')
            except UnicodeError:
                return False
            return len(candidate) <= 128 and secrets.compare_digest(candidate, expected_token)

        def _reply(self, status, value):
            try:
                raw = json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
            except (TypeError, ValueError, UnicodeError, RecursionError):
                status, raw = 500, b'{"schema":"lexicon.ielts.http-error.v1","error":{"code":"internal_error"}}'
            if len(raw) > MAX_RESPONSE_BYTES:
                status, raw = 500, b'{"schema":"lexicon.ielts.http-error.v1","error":{"code":"response_bound"}}'
            self.close_connection = True
            try:
                self.send_response(status)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Connection', 'close')
                if status == 401:
                    self.send_header('WWW-Authenticate', 'Bearer')
                self.end_headers()
                if getattr(self, 'command', '') != 'HEAD':
                    self.wfile.write(raw)
            except (OSError, ValueError):
                pass

        def _error(self, code, status, request_id=None):
            error = {'code': code}
            if request_id is not None:
                error['request_id'] = request_id
            self._reply(status, {'schema': 'lexicon.ielts.http-error.v1', 'error': error})

        def send_error(self, code, message=None, explain=None):
            # Base-handler parser/method errors must not echo raw request lines.
            if not self._authenticated():
                self._error('unauthorized', 401)
            else:
                self._error('method_not_allowed' if code == 501 else 'protocol_error', 405 if code == 501 else 400)

        def handle_expect_100(self):
            self._error('expectation_not_supported', 417) if self._authenticated() else self._error('unauthorized', 401)
            return False

        def _read_submit(self):
            if self.headers.get_all('Transfer-Encoding', []) or self.headers.get_all('Content-Encoding', []):
                _fail('encoding_not_supported')
            lengths = self.headers.get_all('Content-Length', [])
            if len(lengths) != 1 or not re.fullmatch(r'[0-9]{1,6}', lengths[0]):
                _fail('length_required')
            length = int(lengths[0])
            if length > MAX_REQUEST_BYTES:
                _fail('request_too_large', 413)
            if length < 1:
                _fail('length_required')
            types = self.headers.get_all('Content-Type', [])
            if len(types) != 1 or types[0].lower().replace(' ', '') not in ('application/json', 'application/json;charset=utf-8'):
                _fail('json_content_type_required', 415)
            raw = self.rfile.read(length)
            if len(raw) != length:
                _fail('incomplete_request')
            return decode_submit(raw)

        def _job_reply(self, value, status=200):
            self._reply(status, {'schema': 'lexicon.ielts.job.v1', 'job': public_job(value)})

        def _require_empty_body(self):
            if self.headers.get_all('Transfer-Encoding', []) or self.headers.get_all('Content-Encoding', []):
                _fail('encoding_not_supported')
            lengths = self.headers.get_all('Content-Length', [])
            if not lengths:
                return
            if len(lengths) != 1 or not re.fullmatch(r'[0-9]{1,6}', lengths[0]):
                _fail('length_required')
            length = int(lengths[0])
            if length > MAX_REQUEST_BYTES:
                _fail('request_too_large', 413)
            if length:
                _fail('unexpected_body')

        def _dispatch(self, method):
            if not self._authenticated():
                self._error('unauthorized', 401)
                return
            try:
                # No percent decoding, query parameters, alternate origins, path
                # traversal or arbitrary file/URL dispatch is provided.
                path = self.path
                if not isinstance(path, str) or len(path) > 300 or any(c in path for c in ('%', '?', '#', '\\')):
                    _fail('not_found', 404)
                if path == '/health':
                    if method != 'GET':
                        _fail('method_not_allowed', 405)
                    self._require_empty_body()
                    self._reply(200, {'schema': 'lexicon.ielts.health.v1', 'status': 'ok', 'capabilities': copy.deepcopy(declared)})
                    return
                if path == '/exercise-jobs':
                    if method != 'POST':
                        _fail('method_not_allowed', 405)
                    payload = self._read_submit()
                    request_id = payload['request_id']
                    try:
                        offered = public_job(on_submit(payload))
                        stored = public_job(jobs.get(offered['id']))
                    except TimeoutError:
                        self._error('submit_outcome_unknown', 504, request_id)
                        return
                    if stored['request_id'] != request_id or offered['request_id'] != request_id or offered['input_hash'] != stored['input_hash']:
                        _fail('submit_receipt_mismatch', 500)
                    self._job_reply(stored, 202)
                    return
                prefix = '/exercise-jobs/by-request/'
                if path.startswith(prefix):
                    if method != 'GET':
                        _fail('method_not_allowed', 405)
                    identifier = path[len(prefix):]
                    if not _ID.fullmatch(identifier):
                        _fail('not_found', 404)
                    self._require_empty_body()
                    self._job_reply(jobs.by_request(identifier))
                    return
                match = re.fullmatch(r'/exercise-jobs/(job-[a-f0-9]{32})(?:/(cancel|package|candidate))?', path)
                if match is None:
                    _fail('not_found', 404)
                job_id, action = match.groups()
                expected_method = 'POST' if action == 'cancel' else 'GET'
                if method != expected_method:
                    _fail('method_not_allowed', 405)
                self._require_empty_body()
                if action == 'cancel':
                    self._job_reply(jobs.cancel(job_id), 202)
                elif action in ('package', 'candidate'):
                    job = public_job(jobs.get(job_id))
                    if job['state'] != 'ready':
                        _fail('package_unavailable', 409)
                    package = verified_package(jobs.package(job_id), job, declared)
                    self._reply(200, contract.candidate_view(package['request'], package['package']) if action == 'candidate' else package)
                else:
                    self._job_reply(jobs.get(job_id))
            except HttpContractError as error:
                self._error(error.code, error.status)
            except JobError as error:
                mapping = {'job_not_found': (404, 'not_found'), 'request_id_conflict': (409, 'request_id_conflict'),
                           'package_unavailable': (409, 'package_unavailable')}
                status, code = mapping.get(error.code, (400, 'job_request_rejected'))
                self._error(code, status)
            except contract.ContractError:
                self._error('contract_rejected', 400)
            except TimeoutError:
                self._error('request_timeout', 408)
            except Exception:
                self._error('internal_error', 500)

        def do_GET(self): self._dispatch('GET')
        def do_POST(self): self._dispatch('POST')
        def do_HEAD(self): self._dispatch('HEAD')
        def do_PUT(self): self._dispatch('PUT')
        def do_DELETE(self): self._dispatch('DELETE')
        def do_PATCH(self): self._dispatch('PATCH')
        def do_OPTIONS(self): self._dispatch('OPTIONS')

    return Handler


def create_server(bind='127.0.0.1', port=0, *, tls_context, token, jobs, on_submit, capabilities):
    """Create, but do not run, a TLS-only listener. No insecure production option."""
    if not isinstance(tls_context, ssl.SSLContext) or tls_context.protocol != ssl.PROTOCOL_TLS_SERVER:
        _fail('tls_server_context_required')
    if tls_context.minimum_version < ssl.TLSVersion.TLSv1_2:
        _fail('tls_version_required')
    if not isinstance(bind, str) or not bind or len(bind) > 253:
        _fail('bind_invalid')
    _integer(port, 0, 65535)
    handler = make_handler(token=token, jobs=jobs, on_submit=on_submit, capabilities=capabilities)

    class Server(http.server.ThreadingHTTPServer):
        daemon_threads = True
        def get_request(self):
            connection, address = super().get_request()
            connection.settimeout(SOCKET_TIMEOUT)
            try:
                # Handshake starts with bounded handler I/O in its own thread,
                # not in the accept loop; a slow client cannot stall all peers.
                secured = tls_context.wrap_socket(connection, server_side=True, do_handshake_on_connect=False)
                return secured, address
            except Exception:
                connection.close()
                raise
        def handle_error(self, request, client_address):
            pass

    return Server((bind, port), handler)
