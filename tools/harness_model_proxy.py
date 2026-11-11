"""Bounded loopback model route. The Harness receives no real provider credential.

This is not a general proxy: one path, one upstream, one model and a fixed tool
catalog. Requests/responses are never logged. Tests inject an in-process transport
instead of contacting a provider; receipts distinguish that case explicitly.
"""
from __future__ import annotations

import asyncio
import http.server
import json
import secrets
import ssl
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit


TOOL_NAMES = frozenset(('lexicon_ielts_retrieve', 'lexicon_ielts_read', 'lexicon_ielts_validate'))
UPSTREAM = 'https://api.deepseek.com/anthropic/v1/messages'
MAX_RESPONSE = 4_000_000
PROVIDER_DEADLINE_SECONDS = 90
MAX_PROVIDER_DEADLINE_SECONDS = 180
MAX_RESPONSE_HEADERS = 65_536
STOP_REASONS = frozenset(('end_turn', 'max_tokens', 'stop_sequence', 'tool_use',
                         'pause_turn', 'refusal', 'model_context_window_exceeded'))
MAX_TOKEN_COUNT = 9_007_199_254_740_991


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class RouteError(ValueError):
    pass


class ProviderCompletionUnknown(RouteError):
    """The local wait ended without a complete provider HTTP response."""
    def __init__(self, code, http_status=None):
        allowed = ('response_deadline', 'response_incomplete', 'response_bound',
                   'provider_headers_invalid', 'provider_framing_invalid', 'provider_status_invalid')
        self.code = code if code in allowed else 'response_incomplete'
        self.http_status = http_status if type(http_status) is int and 200 <= http_status <= 599 else None
        super().__init__(self.code)


async def _read_provider_response(url, key, payload, observed):
    """One direct HTTP request; no redirect, ambient proxy or incremental relay."""
    target = urlsplit(url)
    if target.scheme not in ('https', 'http') or not target.hostname or target.username or target.password or target.fragment:
        raise RouteError('provider_url_invalid')
    # Plain HTTP is accepted only by local protocol fixtures, never by UPSTREAM.
    if target.scheme == 'http' and target.hostname not in ('127.0.0.1', '::1'):
        raise RouteError('provider_tls_required')
    tls = ssl.create_default_context() if target.scheme == 'https' else None
    port = target.port or (443 if tls else 80)
    reader, writer = await asyncio.open_connection(target.hostname, port, ssl=tls,
        server_hostname=target.hostname if tls else None, limit=MAX_RESPONSE_HEADERS)
    try:
        path = target.path or '/'
        if target.query: path += '?' + target.query
        authority = target.netloc
        head = ('POST ' + path + ' HTTP/1.1\r\nHost: ' + authority + '\r\n'
                'x-api-key: ' + key + '\r\nanthropic-version: 2023-06-01\r\n'
                'Content-Type: application/json\r\nAccept-Encoding: identity\r\n'
                'Connection: close\r\nContent-Length: ' + str(len(payload)) + '\r\n\r\n')
        writer.write(head.encode('ascii') + payload)
        await writer.drain()
        count = 0
        async def line(*, header=True):
            nonlocal count
            value = await reader.readline()
            if header: count += len(value)
            if len(value) > MAX_RESPONSE_HEADERS or count > MAX_RESPONSE_HEADERS or not value.endswith(b'\r\n'):
                raise RouteError('provider_headers_invalid')
            return value[:-2]
        status_line = (await line()).split(b' ', 2)
        if (len(status_line) < 2 or status_line[0] not in (b'HTTP/1.0', b'HTTP/1.1') or
                len(status_line[1]) != 3 or not status_line[1].isdigit()):
            raise RouteError('provider_status_invalid')
        status = int(status_line[1])
        if not 200 <= status <= 599:
            raise RouteError('provider_status_invalid')
        observed['http_status'] = status
        headers = {}
        while True:
            value = await line()
            if not value: break
            name, separator, member = value.partition(b':')
            tokens = b"!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            if (not separator or not name or any(c not in tokens for c in name) or
                    any((c < 32 and c != 9) or c == 127 for c in member)):
                raise RouteError('provider_headers_invalid')
            name = name.decode('ascii').lower()
            if name in ('content-length', 'transfer-encoding') and name in headers:
                raise RouteError('provider_framing_invalid')
            headers[name] = member.strip().decode('latin-1')
        raw = bytearray()
        transfer, length = headers.get('transfer-encoding'), headers.get('content-length')
        if transfer is not None:
            if transfer.lower() != 'chunked' or length is not None:
                raise RouteError('provider_framing_invalid')
            while True:
                size_text = (await line(header=False)).split(b';', 1)[0]
                if not size_text or any(c not in b'0123456789abcdefABCDEF' for c in size_text):
                    raise RouteError('provider_framing_invalid')
                size = int(size_text, 16)
                if size == 0:
                    while await line(): pass
                    break
                if len(raw) + size > MAX_RESPONSE:
                    raise RouteError('response_bound')
                raw.extend(await reader.readexactly(size))
                if await reader.readexactly(2) != b'\r\n':
                    raise RouteError('provider_framing_invalid')
        elif length is not None:
            if not length.isascii() or not length.isdigit():
                raise RouteError('provider_framing_invalid')
            size = int(length)
            if size > MAX_RESPONSE:
                raise RouteError('response_bound')
            raw.extend(await reader.readexactly(size))
        else:
            while True:
                block = await reader.read(min(65_536, MAX_RESPONSE + 1 - len(raw)))
                if not block: break
                raw.extend(block)
                if len(raw) > MAX_RESPONSE:
                    raise RouteError('response_bound')
        return status, headers.get('content-type', 'application/json'), bytes(raw)
    finally:
        # Abort releases the local socket immediately on deadline/cancellation;
        # it does not claim that an already accepted model operation was undone.
        writer.transport.abort()


def bounded_provider_request(url, key, payload, *, timeout=PROVIDER_DEADLINE_SECONDS):
    """One total deadline covers DNS, connect, TLS, headers and the entire body."""
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 0 < timeout <= MAX_PROVIDER_DEADLINE_SECONDS:
        raise RouteError('provider_deadline_invalid')
    observed = {'http_status': None}
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(asyncio.wait_for(
            _read_provider_response(url, key, payload, observed), timeout=timeout))
    except (asyncio.TimeoutError, TimeoutError):
        raise ProviderCompletionUnknown('response_deadline', observed['http_status']) from None
    except RouteError as error:
        raise ProviderCompletionUnknown(str(error), observed['http_status']) from None
    except Exception:
        raise ProviderCompletionUnknown('response_incomplete', observed['http_status']) from None
    finally:
        # Do not join an OS DNS resolver thread after its future was cancelled.
        # No HTTP work can resume after cancellation of the request coroutine.
        loop.run_until_complete(asyncio.sleep(0))
        loop.close()


def contains_credential(raw, key):
    """Inspect JSON/SSE string values, including a JSON document inside model text."""
    text = raw.decode('utf-8', errors='replace')
    pending = [(text, 0)]
    # The native streaming adapter joins deltas before presenting model output.
    # Inspect that same representation, not only each wire fragment in isolation.
    streams = {}
    for line in text.splitlines():
        if not line.startswith('data:'):
            continue
        try:
            event = json.loads(line[5:].strip())
        except (ValueError, RecursionError):
            continue
        if not isinstance(event, dict):
            continue
        block = event.get('delta') if event.get('type') == 'content_block_delta' else event.get('content_block')
        if not isinstance(block, dict):
            continue
        for field in ('text', 'thinking', 'partial_json'):
            value = block.get(field)
            if isinstance(value, str):
                streams.setdefault((event.get('index'), field), []).append(value)
    pending.extend((''.join(chunks), 0) for chunks in streams.values())
    seen = set()
    while pending:
        value, depth = pending.pop()
        if isinstance(value, str):
            if key in value:
                return True
            if depth >= 12 or value in seen:
                continue
            seen.add(value)
            try:
                decoded = json.loads(value)
                pending.append((decoded, depth + 1))
            except (ValueError, RecursionError):
                for line in value.splitlines():
                    if line.startswith('data:') and len(line) > 5:
                        pending.append((line[5:].strip(), depth + 1))
        elif isinstance(value, dict):
            pending.extend((part, depth) for pair in value.items() for part in pair)
        elif isinstance(value, list):
            pending.extend((part, depth) for part in value)
    return False


def decode_object(raw):
    def unique(pairs):
        out = {}
        for k, v in pairs:
            if k in out:
                raise RouteError('duplicate_field')
            out[k] = v
        return out
    def invalid(_):
        raise RouteError('invalid_number')
    try:
        value = json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)
    except (ValueError, UnicodeError, RecursionError):
        raise RouteError('invalid_json') from None
    if not isinstance(value, dict):
        raise RouteError('object_required')
    return value


def _token_count(value):
    return value if type(value) is int and 0 <= value <= MAX_TOKEN_COUNT else None


def _termination_metadata(value=None):
    """Copy only fixed enums, two valid token counts and a tri-state marker."""
    value = value if isinstance(value, dict) else {}
    format_ = value.get('format')
    if format_ not in ('anthropic_json', 'anthropic_sse'):
        format_ = 'unknown'
    reason = value.get('stop_reason')
    if not isinstance(reason, str) or reason not in STOP_REASONS:
        reason = 'unknown'
    incoming, outgoing = _token_count(value.get('input_tokens')), _token_count(value.get('output_tokens'))
    stopped = value.get('message_stop_observed')
    if type(stopped) is not bool or format_ != 'anthropic_sse':
        stopped = None
    observed = (value.get('metadata_status') == 'observed' and reason != 'unknown' and
                incoming is not None and outgoing is not None and
                (format_ == 'anthropic_json' or (format_ == 'anthropic_sse' and stopped is True)))
    return {'format': format_, 'metadata_status': 'observed' if observed else 'unknown',
            'stop_reason': reason, 'input_tokens': incoming, 'output_tokens': outgoing,
            'message_stop_observed': stopped}


def provider_termination_metadata(raw, mime):
    """Read Anthropic termination metadata without retaining content or tool arguments.

    HTTP framing/completion is decided separately. These observations neither
    mark a model run complete nor relax a publication gate. Malformed frames,
    unknown values and missing usage remain explicitly unknown. SSE content
    blocks are parsed only as individual JSON frames and immediately discarded;
    text/thinking/tool deltas are never joined or included in this result.
    """
    base_mime = mime.partition(';')[0].strip().lower() if isinstance(mime, str) else ''
    format_ = {'application/json': 'anthropic_json', 'text/event-stream': 'anthropic_sse'}.get(base_mime, 'unknown')
    unknown = _termination_metadata({'format': format_})
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_RESPONSE or format_ == 'unknown':
        return unknown
    try:
        if format_ == 'anthropic_json':
            message = decode_object(raw)
            if message.get('type') != 'message':
                return unknown
            usage = message.get('usage')
            usage = usage if isinstance(usage, dict) else {}
            return _termination_metadata({'format': format_, 'metadata_status': 'observed',
                'stop_reason': message.get('stop_reason'), 'input_tokens': usage.get('input_tokens'),
                'output_tokens': usage.get('output_tokens')})

        inputs, outputs, reasons = [], [], []
        starts = stops = 0
        data_lines, event_name = [], None
        for line in raw.decode('utf-8', errors='strict').splitlines():
            if line == '':
                if data_lines:
                    event = decode_object('\n'.join(data_lines))
                    kind = event.get('type')
                    if not isinstance(kind, str) or (event_name is not None and event_name != kind):
                        return unknown
                    if kind == 'message_start':
                        starts += 1
                        message = event.get('message')
                        if not isinstance(message, dict) or message.get('type') != 'message':
                            return unknown
                        usage = message.get('usage')
                        inputs.append(_token_count(usage.get('input_tokens')) if isinstance(usage, dict) else None)
                    elif kind == 'message_delta':
                        delta = event.get('delta')
                        if not isinstance(delta, dict):
                            return unknown
                        if delta.get('stop_reason') is not None:
                            reason = delta['stop_reason']
                            reasons.append(reason if isinstance(reason, str) and reason in STOP_REASONS else 'unknown')
                        usage = event.get('usage')
                        if isinstance(usage, dict) and 'output_tokens' in usage:
                            outputs.append(_token_count(usage['output_tokens']))
                    elif kind == 'message_stop':
                        stops += 1
                    elif kind == 'error':
                        return unknown
                    # All other event fields, content and deltas are discarded.
                data_lines, event_name = [], None
            elif line.startswith('data:'):
                data_lines.append(line[5:].removeprefix(' '))
            elif line.startswith('event:'):
                if event_name is not None:
                    return unknown
                event_name = line[6:].removeprefix(' ')
        # An unterminated final SSE frame is not an observed terminal event.
        if data_lines or event_name is not None or starts > 1 or stops > 1:
            return unknown
        incoming = inputs[0] if len(inputs) == 1 else None
        outgoing = outputs[-1] if outputs and all(v is not None for v in outputs) and outputs == sorted(outputs) else None
        reason = reasons[0] if reasons and all(v == reasons[0] for v in reasons) else 'unknown'
        return _termination_metadata({'format': format_, 'metadata_status': 'observed',
            'stop_reason': reason, 'input_tokens': incoming, 'output_tokens': outgoing,
            'message_stop_observed': stops == 1})
    except (ValueError, TypeError, UnicodeError, RecursionError):
        return unknown


def validate_model_request(raw, model, token_limit):
    if len(raw) > 500_000:
        raise RouteError('input_bound')
    payload = decode_object(raw)
    if set(payload) - {'model', 'messages', 'system', 'max_tokens', 'thinking', 'output_config',
                       'stream', 'temperature', 'stop_sequences', 'tools', 'tool_choice'}:
        raise RouteError('request_fields_changed')
    if payload.get('model') != model:
        raise RouteError('model_route_changed')
    messages = payload.get('messages')
    if not isinstance(messages, list) or not 1 <= len(messages) <= 80:
        raise RouteError('message_bound')
    if any(not isinstance(m, dict) or m.get('role') not in ('user', 'assistant') for m in messages):
        raise RouteError('message_shape')
    tools = payload.get('tools', [])
    if not isinstance(tools, list) or len(tools) > 3:
        raise RouteError('tool_catalog_changed')
    names = []
    for tool in tools:
        if not isinstance(tool, dict) or not isinstance(tool.get('input_schema'), dict):
            raise RouteError('tool_catalog_changed')
        names.append(tool.get('name'))
    if any(name not in TOOL_NAMES for name in names) or len(set(names)) != len(names):
        raise RouteError('tool_catalog_changed')
    # The host owns output budget even when the runtime's exact-model default is larger.
    cap = payload.get('max_tokens', token_limit)
    if type(cap) is not int or cap <= 0:
        raise RouteError('token_limit_invalid')
    payload['max_tokens'] = min(cap, token_limit)
    if 'max_completion_tokens' in payload:
        raise RouteError('unsupported_token_limit')
    if 'stream' in payload and type(payload['stream']) is not bool:
        raise RouteError('stream_invalid')
    return json.dumps(payload, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


class ModelBudget:
    def __init__(self, max_calls=8):
        if type(max_calls) is not int or not 1 <= max_calls <= 16:
            raise RouteError('call_limit_invalid')
        self.max_calls, self.calls = max_calls, 0
        self.lock = threading.Lock()
        self.halted = False
        self.records = []

    def reserve(self):
        with self.lock:
            if self.halted or self.calls >= self.max_calls:
                raise RouteError('model_budget_exhausted')
            self.calls += 1
            return self.calls

    def record(self, ordinal, status, elapsed, response_bytes, real, termination=None):
        with self.lock:
            item = {'ordinal': ordinal, 'phase': 'completed', 'http_status': status,
                    'elapsed_ms': round(elapsed * 1000), 'response_bytes': response_bytes,
                    'real_provider_request': real, 'termination': _termination_metadata(termination)}
            index = next((i for i, old in enumerate(self.records) if old['ordinal'] == ordinal), None)
            if index is None: self.records.append(item)
            else: self.records[index] = item
            if status in (401, 402, 403, 429):
                self.halted = True

    def started(self, ordinal, real):
        with self.lock:
            self.records.append({'ordinal': ordinal, 'phase': 'inflight', 'http_status': None,
                                 'real_provider_request': real, 'termination': _termination_metadata()})

    def completion_unknown(self, ordinal, status, elapsed, real, reason):
        with self.lock:
            item = {'ordinal': ordinal, 'phase': 'completion_unknown', 'http_status': status,
                    'elapsed_ms': round(elapsed * 1000), 'real_provider_request': real, 'reason': reason,
                    'termination': _termination_metadata()}
            index = next((i for i, old in enumerate(self.records) if old['ordinal'] == ordinal), None)
            if index is None: self.records.append(item)
            else: self.records[index] = item
            # Uncertain completion consumes its original request and stops the
            # shared budget; no automatic second POST follows a local timeout.
            self.halted = True

    def snapshot(self):
        with self.lock:
            return [dict(item, termination=dict(item['termination'])) for item in self.records]


class ModelRoute:
    def __init__(self, key, *, model='deepseek-flash', budget=None, token_limit=8192,
                 provider_deadline_seconds=PROVIDER_DEADLINE_SECONDS, transport=None):
        if model not in ('deepseek-flash', 'deepseek-v4-pro'):
            raise RouteError('unsupported_model')
        if not isinstance(key, str) or not key or '\n' in key or '\r' in key:
            raise RouteError('invalid_key')
        if type(token_limit) is not int or not 256 <= token_limit <= 16384:
            raise RouteError('token_limit_invalid')
        if type(provider_deadline_seconds) is not int or not 1 <= provider_deadline_seconds <= MAX_PROVIDER_DEADLINE_SECONDS:
            raise RouteError('provider_deadline_invalid')
        self._key, self.model, self.budget = key, model, budget or ModelBudget()
        self.token_limit, self._transport = token_limit, transport
        self.provider_deadline_seconds = provider_deadline_seconds
        self.token = secrets.token_urlsafe(32)
        self.active = True
        self.inflight = 0
        self.lock = threading.Lock()
        self.server = None
        self.thread = None

    def _request(self, payload):
        if self._transport is not None:
            return self._transport(payload)
        return bounded_provider_request(UPSTREAM, self._key, payload, timeout=self.provider_deadline_seconds)

    def __enter__(self):
        route = self
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def log_message(self, *args):
                pass
            def reply(self, status, raw, mime='application/json'):
                try:
                    self.send_response(status)
                    self.send_header('Content-Type', mime)
                    self.send_header('Content-Length', str(len(raw)))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                self.close_connection = True
            def do_GET(self):
                self.reply(405, b'{"error":{"message":"method_denied"}}')
            def do_POST(self):
                self.connection.settimeout(5)
                if self.path != '/v1/messages':
                    self.reply(404, b'{"error":{"message":"route_denied"}}'); return
                supplied = self.headers.get('x-api-key', '')
                if len(self.headers.get_all('x-api-key', [])) != 1 or not secrets.compare_digest(supplied, route.token):
                    self.reply(401, b'{"error":{"message":"route_authentication"}}'); return
                try:
                    if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) != 1:
                        raise RouteError('length_required')
                    length = int(self.headers.get('Content-Length', '-1'))
                    if not 1 <= length <= 500_000:
                        raise RouteError('input_bound')
                    payload = self.rfile.read(length)
                    if len(payload) != length:
                        raise RouteError('incomplete_input')
                    payload = validate_model_request(payload, route.model, route.token_limit)
                    with route.lock:
                        if not route.active or route.inflight:
                            raise RouteError('route_inactive_or_busy')
                        ordinal = route.budget.reserve()
                        route.inflight += 1
                        route.budget.started(ordinal, route._transport is None)
                except (ValueError, TimeoutError, OSError):
                    self.reply(400, b'{"error":{"message":"route_request_rejected"}}'); return
                started, status, raw, mime = time.monotonic(), 502, b'', 'application/json'
                provider_status, provider_bytes, completed, unknown_reason = None, 0, False, 'response_incomplete'
                termination = _termination_metadata()
                try:
                    status, mime, raw = route._request(payload)
                    if type(status) is not int or not 200 <= status <= 599 or not isinstance(raw, bytes):
                        raise RouteError('provider_response_invalid')
                    provider_status, provider_bytes, completed = status, len(raw), True
                    termination = provider_termination_metadata(raw, mime)
                    # Header parameters can echo credentials too. Only these
                    # literal media types may cross into the uncredentialed CLI.
                    base_mime = mime.partition(';')[0].strip().lower() if isinstance(mime, str) else ''
                    if status != 200:
                        mime = 'application/json'
                    elif base_mime not in ('application/json', 'text/event-stream'):
                        status, raw, mime = 502, b'{"error":{"message":"provider_response_rejected"}}', 'application/json'
                    else:
                        mime = base_mime
                    if len(raw) > MAX_RESPONSE:
                        status = 502
                    if status != 200:
                        # Never pass a provider diagnostic body (which can echo credentials or text).
                        raw, mime = b'{"error":{"message":"provider_request_failed"}}', 'application/json'
                    if contains_credential(raw, route._key):
                        status, raw, mime = 502, b'{"error":{"message":"provider_response_rejected"}}', 'application/json'
                except ProviderCompletionUnknown as error:
                    provider_status, unknown_reason = error.http_status, error.code
                    status, raw, mime = 502, b'{"error":{"message":"provider_completion_unknown"}}', 'application/json'
                except Exception:
                    status, raw, mime = 502, b'{"error":{"message":"provider_completion_unknown"}}', 'application/json'
                finally:
                    with route.lock:
                        if completed:
                            route.budget.record(ordinal, provider_status, time.monotonic() - started, provider_bytes, route._transport is None, termination)
                        else:
                            route.budget.completion_unknown(ordinal, provider_status, time.monotonic() - started,
                                                          route._transport is None, unknown_reason)
                        route.inflight -= 1
                self.reply(status, raw, mime)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .1}, daemon=True)
        self.thread.start()
        return self

    @property
    def base_url(self):
        return 'http://127.0.0.1:{}'.format(self.server.server_port)

    def __exit__(self, *args):
        with self.lock:
            self.active = False
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        # Revocation stops new work. An already accepted provider request can still
        # complete upstream; cancellation is never reported as a billing rollback.
