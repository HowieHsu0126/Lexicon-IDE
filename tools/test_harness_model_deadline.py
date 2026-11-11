"""Scaled local HTTP deadline tests. Synthetic credentials; zero provider calls."""
from contextlib import contextmanager
import http.server
import json
import os
import socketserver
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

from harness_model_proxy import (MAX_RESPONSE, ModelBudget, ModelRoute, ProviderCompletionUnknown,
                                RouteError, bounded_provider_request)

MEASUREMENTS = []


@contextmanager
def origin(write_response):
    observed = []
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *_): pass
        def do_POST(self):
            self.rfile.read(int(self.headers['Content-Length']))
            observed.append(self.path)
            try: write_response(self)
            except (BrokenPipeError, ConnectionResetError, OSError): pass
            self.close_connection = True
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try: yield 'http://127.0.0.1:%d/v1/messages' % server.server_port, observed
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=1)


def headers(handler, *, length=None, chunked=False, status=200):
    handler.send_response(status)
    handler.send_header('Content-Type', 'text/event-stream')
    if length is not None: handler.send_header('Content-Length', str(length))
    if chunked: handler.send_header('Transfer-Encoding', 'chunked')
    handler.send_header('Connection', 'close')
    handler.end_headers()


class TotalDeadlineTest(unittest.TestCase):
    key = 'offline-secret'
    payload = json.dumps({'model': 'deepseek-flash', 'messages': [{'role': 'user', 'content': 'synthetic'}]}).encode()

    def send(self, route, *, timeout=1):
        request = urllib.request.Request(route.base_url + '/v1/messages', data=self.payload,
            headers={'x-api-key': route.token, 'Content-Type': 'application/json'})
        try: response = urllib.request.urlopen(request, timeout=timeout)
        except urllib.error.HTTPError as error: response = error
        with response: return response.status, response.read()

    def test_reasoning_deadline_allows_complete_response_after_plain_deadline(self):
        body = b'data: {"type":"message_stop"}\n\n'
        def serve(handler):
            headers(handler, length=len(body))
            time.sleep(.30)
            handler.wfile.write(body); handler.wfile.flush()
        # The 90/180-second policy is scaled to .20/.40 seconds. Each scenario
        # is independent, not an automatic retry of an uncertain request.
        for deadline, expected in ((.20, 'unknown'), (.40, 'completed')):
            with self.subTest(deadline=deadline), origin(serve) as (url, seen):
                if expected == 'unknown':
                    with self.assertRaises(ProviderCompletionUnknown):
                        bounded_provider_request(url, self.key, self.payload, timeout=deadline)
                else:
                    self.assertEqual((200, 'text/event-stream', body),
                                     bounded_provider_request(url, self.key, self.payload, timeout=deadline))
                self.assertEqual(1, len(seen))

    def test_route_forwards_its_selected_total_deadline_and_rejects_unbounded_values(self):
        for deadline in (90, 180):
            with patch('harness_model_proxy.bounded_provider_request', return_value=(200, 'application/json', b'{}')) as request:
                route = ModelRoute(self.key, provider_deadline_seconds=deadline)
                route._request(self.payload)
                self.assertEqual(deadline, request.call_args.kwargs['timeout'])
        for deadline in (None, True, 0, -1, 181, float('inf'), float('nan'), 90.0):
            with self.subTest(deadline=deadline), self.assertRaisesRegex(RouteError, 'provider_deadline_invalid'):
                ModelRoute(self.key, provider_deadline_seconds=deadline)

    def test_buffered_success_after_old_scaled_idle_is_delivered_before_total_deadline(self):
        body = b'data: {"type":"message_stop"}\n\n'
        def serve(handler):
            headers(handler, length=len(body))
            handler.wfile.write(body[:5]); handler.wfile.flush()
            time.sleep(.20)  # Beyond the old scaled 60-second client idle window.
            handler.wfile.write(body[5:]); handler.wfile.flush()
        budget = ModelBudget(1)
        with origin(serve) as (url, seen):
            with ModelRoute(self.key, budget=budget, transport=lambda payload:
                    bounded_provider_request(url, self.key, payload, timeout=.45)) as route:
                start = time.monotonic(); status, received = self.send(route, timeout=.50)
                elapsed = time.monotonic() - start
        self.assertEqual((200, body), (status, received))
        self.assertGreaterEqual(elapsed, .18); self.assertLess(elapsed, .48)
        self.assertEqual(1, len(seen)); self.assertEqual('completed', budget.records[0]['phase'])
        self.assertFalse(budget.records[0]['real_provider_request'])
        MEASUREMENTS.append({'scenario': 'buffered_success', 'elapsed_ms': round(elapsed * 1000),
            'origin_pause_ms': 200, 'total_deadline_ms': 450, 'client_idle_ms': 500,
            'http_status': status, 'provider_phase': budget.records[0]['phase']})

    def test_continuous_body_drip_cannot_extend_total_deadline_or_trigger_retry(self):
        def serve(handler):
            headers(handler, length=100)
            for _ in range(100):
                handler.wfile.write(b'x'); handler.wfile.flush(); time.sleep(.025)
        budget = ModelBudget(2)
        with origin(serve) as (url, seen):
            with ModelRoute(self.key, budget=budget, transport=lambda payload:
                    bounded_provider_request(url, self.key, payload, timeout=.22)) as route:
                start = time.monotonic(); status, body = self.send(route)
                elapsed = time.monotonic() - start
                second = self.send(route)
        self.assertEqual(502, status); self.assertLess(elapsed, .60)
        self.assertIn(b'provider_completion_unknown', body); self.assertEqual(400, second[0])
        self.assertEqual(1, len(seen)); self.assertEqual(1, budget.calls); self.assertTrue(budget.halted)
        record = budget.records[0]
        self.assertEqual('completion_unknown', record['phase'])
        self.assertEqual(200, record['http_status'], 'retain observed headers without claiming complete response')
        self.assertEqual('response_deadline', record['reason']); self.assertFalse(record['real_provider_request'])
        MEASUREMENTS.append({'scenario': 'continuous_drip', 'elapsed_ms': round(elapsed * 1000),
            'drip_interval_ms': 25, 'planned_response_ms': 2500, 'total_deadline_ms': 220,
            'client_status': status, 'observed_provider_status': record['http_status'],
            'provider_phase': record['phase'], 'requests_started': len(seen)})

    def test_waiting_for_headers_is_inside_the_same_total_deadline(self):
        def serve(handler): time.sleep(.30); headers(handler, length=0)
        with origin(serve) as (url, seen):
            start = time.monotonic()
            with self.assertRaises(ProviderCompletionUnknown) as error:
                bounded_provider_request(url, self.key, self.payload, timeout=.08)
            elapsed = time.monotonic() - start
        self.assertLess(elapsed, .25); self.assertIsNone(error.exception.http_status)
        self.assertEqual('response_deadline', error.exception.code); self.assertEqual(1, len(seen))
        MEASUREMENTS.append({'scenario': 'waiting_for_headers', 'elapsed_ms': round(elapsed * 1000),
            'total_deadline_ms': 80, 'observed_provider_status': error.exception.http_status})

    def test_tls_handshake_wait_is_inside_the_total_deadline(self):
        class Stall(socketserver.BaseRequestHandler):
            def handle(self): time.sleep(.30)
        server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), Stall)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        thread.start()
        try:
            start = time.monotonic()
            with self.assertRaises(ProviderCompletionUnknown) as error:
                bounded_provider_request('https://127.0.0.1:%d/v1/messages' % server.server_address[1],
                                         self.key, self.payload, timeout=.10)
            self.assertLess(time.monotonic() - start, .40)
            self.assertEqual('response_deadline', error.exception.code); self.assertIsNone(error.exception.http_status)
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=1)

    def test_slow_partial_headers_cannot_reset_the_total_deadline(self):
        def serve(handler):
            handler.wfile.write(b'HTTP/1.1 200 OK\r\nX-Slow: '); handler.wfile.flush()
            for _ in range(100): handler.wfile.write(b'x'); handler.wfile.flush(); time.sleep(.025)
        with origin(serve) as (url, _):
            start = time.monotonic()
            with self.assertRaises(ProviderCompletionUnknown) as error:
                bounded_provider_request(url, self.key, self.payload, timeout=.20)
        self.assertLess(time.monotonic() - start, .60)
        self.assertEqual(200, error.exception.http_status); self.assertEqual('response_deadline', error.exception.code)

    def test_chunked_and_connection_closed_bodies_are_framed_completely(self):
        for chunked in (True, False):
            def serve(handler):
                headers(handler, chunked=chunked)
                handler.wfile.write(b'3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n' if chunked else b'abcde')
                handler.wfile.flush()
            with self.subTest(chunked=chunked), origin(serve) as (url, _):
                response = bounded_provider_request(url, self.key, self.payload, timeout=.50)
                self.assertEqual(200, response[0]); self.assertEqual(b'abcde', response[2])

    def test_truncated_declared_body_keeps_completion_unknown(self):
        def serve(handler): headers(handler, length=20); handler.wfile.write(b'abc'); handler.wfile.flush()
        with origin(serve) as (url, _):
            with self.assertRaises(ProviderCompletionUnknown) as error:
                bounded_provider_request(url, self.key, self.payload, timeout=.50)
        self.assertEqual(200, error.exception.http_status); self.assertEqual('response_incomplete', error.exception.code)

    def test_split_credential_is_scanned_only_after_full_buffer_and_never_relayed(self):
        parts = [b'data: {"type":"content_block_delta","index":0,"delta":{"text":"offline-"}}\n\n',
                 b'data: {"type":"content_block_delta","index":0,"delta":{"text":"secret"}}\n\n']
        def serve(handler):
            headers(handler, length=sum(map(len, parts)))
            handler.wfile.write(parts[0]); handler.wfile.flush(); time.sleep(.06)
            handler.wfile.write(parts[1]); handler.wfile.flush()
        budget = ModelBudget(1)
        with origin(serve) as (url, _):
            with ModelRoute(self.key, budget=budget, transport=lambda payload:
                    bounded_provider_request(url, self.key, payload, timeout=.50)) as route:
                status, body = self.send(route)
        self.assertEqual(502, status); self.assertNotIn(b'offline-', body); self.assertNotIn(b'secret', body)
        self.assertEqual('completed', budget.records[0]['phase'])
        self.assertEqual(200, budget.records[0]['http_status'], 'local redaction is not a fabricated provider 502')

    def test_redirect_and_environment_proxy_do_not_create_a_second_request(self):
        def serve(handler):
            handler.send_response(307); handler.send_header('Location', 'http://untrusted.invalid/secret')
            handler.send_header('Content-Length', '0'); handler.end_headers()
        with origin(serve) as (url, seen), patch.dict(os.environ, {'HTTP_PROXY': 'http://untrusted.invalid:1',
                                                                 'HTTPS_PROXY': 'http://untrusted.invalid:1'}):
            response = bounded_provider_request(url, self.key, self.payload, timeout=.50)
        self.assertEqual(307, response[0]); self.assertEqual(1, len(seen))

    def test_content_type_parameters_cannot_relay_credentials_to_the_cli(self):
        def serve(handler):
            handler.send_response(200)
            handler.send_header('Content-Type', 'application/json; echoed=' + self.key)
            handler.send_header('Content-Length', '2'); handler.end_headers()
            handler.wfile.write(b'{}'); handler.wfile.flush()
        with origin(serve) as (url, _):
            with ModelRoute(self.key, transport=lambda payload:
                    bounded_provider_request(url, self.key, payload, timeout=.50)) as route:
                request = urllib.request.Request(route.base_url + '/v1/messages', data=self.payload,
                    headers={'x-api-key': route.token, 'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=1) as response:
                    self.assertEqual(200, response.status); self.assertEqual(b'{}', response.read())
                    self.assertEqual('application/json', response.headers['Content-Type'])
                    self.assertNotIn(self.key, str(response.headers))

    def test_conflicting_framing_and_oversized_response_are_rejected(self):
        for conflict in (False, True):
            def serve(handler):
                headers(handler, length=MAX_RESPONSE + 1 if not conflict else 2, chunked=conflict)
            with self.subTest(conflict=conflict), origin(serve) as (url, _):
                with self.assertRaises(ProviderCompletionUnknown):
                    bounded_provider_request(url, self.key, self.payload, timeout=.50)

    def test_unobserved_transport_exception_never_becomes_a_provider_502(self):
        def broken(_): raise OSError('private provider text and offline-secret')
        budget = ModelBudget(2)
        with ModelRoute(self.key, budget=budget, transport=broken) as route:
            status, body = self.send(route)
            self.assertEqual(400, self.send(route)[0])
        self.assertEqual(502, status); self.assertNotIn(b'private', body); self.assertNotIn(self.key.encode(), body)
        self.assertEqual('completion_unknown', budget.records[0]['phase'])
        self.assertIsNone(budget.records[0]['http_status']); self.assertEqual(1, budget.calls)

    def test_auth_status_survives_a_discarded_non_json_diagnostic_body(self):
        budget = ModelBudget(2)
        with ModelRoute(self.key, budget=budget, transport=lambda _:
                (401, 'text/plain; echoed=' + self.key, self.key.encode())) as route:
            status, body = self.send(route)
            self.assertEqual(400, self.send(route)[0])
        self.assertEqual(401, status); self.assertNotIn(self.key.encode(), body)
        self.assertEqual(401, budget.records[0]['http_status']); self.assertTrue(budget.halted)


if __name__ == '__main__': unittest.main()
