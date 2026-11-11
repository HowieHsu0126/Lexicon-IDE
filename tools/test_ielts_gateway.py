"""Synthetic offline job/proxy checks; no provider or Android operation."""
import copy
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import threading
import sys
import unittest
import urllib.error
import urllib.request

from ielts_jobs import ExerciseJobs, JobError, verify_body
from harness_model_proxy import ModelBudget, ModelRoute, RouteError, validate_model_request
from test_ielts_contract import fixture
from harness_ielts import HarnessError, parse_headless_events, run_owned_process, validate_terminal


class JobsTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix='lexicon-ielts-jobs-')
        self.addCleanup(self.temp.cleanup)
        self.jobs = ExerciseJobs(Path(self.temp.name) / 'jobs.sqlite')
        self.request, self.package, _ = fixture()
        self.body = '\n\n'.join(p['text'] for p in self.request['paragraphs'])

    def submit(self):
        return self.jobs.submit(self.request, self.body, 'b' * 64)

    def test_idempotent_same_request_has_one_job(self):
        a, b = self.submit(), self.submit()
        self.assertEqual(a, b)
        self.assertEqual(a, self.jobs.by_request(self.request['request_id']))

    def test_same_id_changed_material_or_corpus_conflicts(self):
        self.submit()
        with self.assertRaisesRegex(JobError, 'request_id_conflict'):
            self.jobs.submit(self.request, self.body, 'c' * 64)

    def test_full_body_hash_and_precise_paragraphs_are_independently_checked(self):
        verify_body(self.request, self.body)
        with self.assertRaises(JobError):
            verify_body(self.request, self.body + '!')
        request = copy.deepcopy(self.request)
        request['paragraphs'][0]['text'] = request['paragraphs'][0]['text'].replace('council', 'Council')
        with self.assertRaisesRegex(JobError, 'paragraph_body_mismatch'):
            verify_body(request, self.body)

    def test_only_one_concurrent_worker_can_claim(self):
        job = self.submit()
        outcomes = []
        def claim():
            try:
                self.jobs.claim(job['id']); outcomes.append('claimed')
            except JobError as error:
                outcomes.append(error.code)
        threads = [threading.Thread(target=claim) for _ in range(4)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual(1, outcomes.count('claimed'))
        self.assertEqual(3, outcomes.count('job_not_queued'))

    def test_package_cannot_be_read_before_review_and_completion(self):
        job = self.submit()
        lease, _ = self.jobs.claim(job['id'])
        with self.assertRaisesRegex(JobError, 'package_unavailable'): self.jobs.package(job['id'])
        with self.assertRaisesRegex(JobError, 'review_required'): self.jobs.finish(job['id'], lease, output=self.package)
        self.jobs.begin_review(job['id'], lease)
        done = self.jobs.finish(job['id'], lease, output=self.package)
        self.assertEqual('ready', done['state'])
        self.assertEqual(self.package, self.jobs.package(job['id']))

    def test_cancelled_queued_job_never_starts(self):
        job = self.submit()
        self.assertEqual('cancelled', self.jobs.cancel(job['id'])['state'])
        with self.assertRaisesRegex(JobError, 'job_not_queued'): self.jobs.claim(job['id'])

    def test_active_cancellation_waits_for_reaping_and_discards_late_output(self):
        job = self.submit(); lease, _ = self.jobs.claim(job['id'])
        token = self.jobs.set_process_active(job['id'], lease)
        self.assertEqual('cancelling', self.jobs.cancel(job['id'])['state'])
        self.assertTrue(self.jobs.cancellation_requested(job['id'], lease))
        with self.assertRaisesRegex(JobError, 'process_not_reaped'):
            self.jobs.finish(job['id'], lease, output=self.package)
        self.jobs.record_stage(job['id'], lease, token, {'status': 'cancelled'}, reaped=True)
        self.assertEqual('cancelled', self.jobs.finish(job['id'], lease, output=self.package)['state'])
        with self.assertRaisesRegex(JobError, 'package_unavailable'): self.jobs.package(job['id'])
        with self.assertRaisesRegex(JobError, 'worker_conflict'):
            self.jobs.finish(job['id'], lease, output=self.package)

    def test_wrong_lease_cannot_publish_or_record(self):
        job = self.submit(); self.jobs.claim(job['id'])
        for fn in (lambda: self.jobs.begin_review(job['id'], 'wrong'),
                   lambda: self.jobs.set_process_active(job['id'], 'wrong'),
                   lambda: self.jobs.record_stage(job['id'], 'wrong', 'token', {}, reaped=True),
                   lambda: self.jobs.finish(job['id'], 'wrong', error='failure')):
            with self.assertRaisesRegex(JobError, 'worker_conflict'): fn()

    def test_restart_keeps_uncertain_process_state_and_never_requeues(self):
        job = self.submit(); lease, _ = self.jobs.claim(job['id'])
        self.jobs.set_process_active(job['id'], lease)
        reopened = ExerciseJobs(self.jobs.path)
        self.assertEqual(1, reopened.recover_interrupted())
        status = reopened.get(job['id'])
        self.assertEqual('interrupted', status['state'])
        self.assertEqual(0, status['process_reaped'])
        with self.assertRaisesRegex(JobError, 'job_not_queued'): reopened.claim(job['id'])
        self.assertEqual(status, self.submit())

    def test_errors_are_bounded_codes_not_provider_messages(self):
        job = self.submit(); lease, _ = self.jobs.claim(job['id'])
        with self.assertRaisesRegex(JobError, 'error_code_invalid'):
            self.jobs.finish(job['id'], lease, error='Provider leaked material and a secret')
        self.assertEqual('failed', self.jobs.finish(job['id'], lease, error='provider_failure')['state'])

    def test_old_generation_receipt_cannot_reap_a_live_review(self):
        job = self.submit(); lease, _ = self.jobs.claim(job['id'])
        old_token = self.jobs.set_process_active(job['id'], lease)
        self.jobs.record_stage(job['id'], lease, old_token, {'stage': 'generation'}, reaped=True)
        self.jobs.begin_review(job['id'], lease)
        new_token = self.jobs.set_process_active(job['id'], lease)
        self.assertNotEqual(old_token, new_token)
        with self.assertRaisesRegex(JobError, 'worker_conflict'):
            self.jobs.record_stage(job['id'], lease, old_token, {'stage': 'generation'}, reaped=True)
        self.assertEqual(0, self.jobs.get(job['id'])['process_reaped'])
        with self.assertRaisesRegex(JobError, 'process_not_reaped'):
            self.jobs.finish(job['id'], lease, output=self.package)
        self.jobs.record_stage(job['id'], lease, new_token, {'stage': 'review'}, reaped=True)
        self.assertEqual('ready', self.jobs.finish(job['id'], lease, output=self.package)['state'])


class ModelRouteTest(unittest.TestCase):
    def payload(self, **kwargs):
        return json.dumps({'model': 'deepseek-flash', 'messages': [{'role': 'user', 'content': 'synthetic text'}], **kwargs}).encode()

    def send(self, route, payload=None, path='/v1/messages', auth=None):
        request = urllib.request.Request(route.base_url + path, data=payload or self.payload(),
            headers={'x-api-key': auth or route.token, 'Content-Type': 'application/json'})
        try: response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error: response = error
        with response: return response.status, response.read()

    def test_budget_cap_is_host_owned(self):
        parsed = json.loads(validate_model_request(self.payload(max_tokens=32000), 'deepseek-flash', 1024))
        self.assertEqual(1024, parsed['max_tokens'])
        with self.assertRaises(RouteError): validate_model_request(self.payload(max_tokens=True), 'deepseek-flash', 1024)

    def test_model_or_tool_catalog_change_and_duplicate_fields_are_rejected(self):
        for raw in (self.payload(model='other'), self.payload(tools=[{'name': 'shell', 'input_schema': {}}]),
                    b'{"model":"deepseek-flash","model":"deepseek-flash","messages":[]}'):
            with self.assertRaises(RouteError): validate_model_request(raw, 'deepseek-flash', 1024)

    def test_loopback_route_receives_only_ephemeral_token_and_counts_requests(self):
        seen = []
        def transport(raw):
            seen.append(raw)
            return 200, 'application/json', b'{"choices":[]}'
        budget = ModelBudget(1)
        with ModelRoute('offline-provider-secret', budget=budget, transport=transport) as route:
            self.assertNotEqual('offline-provider-secret', route.token)
            self.assertEqual(200, self.send(route)[0])
            self.assertEqual(400, self.send(route)[0])
        self.assertEqual(1, len(seen))
        self.assertNotIn(b'offline-provider-secret', seen[0])
        self.assertFalse(budget.records[0]['real_provider_request'])

    def test_unknown_route_and_bad_auth_do_not_consume_model_budget(self):
        budget = ModelBudget(2)
        with ModelRoute('offline-secret', budget=budget, transport=lambda _: self.fail('must not forward')) as route:
            self.assertEqual(404, self.send(route, path='/files')[0])
            self.assertEqual(401, self.send(route, auth='wrong')[0])
            self.assertEqual(400, self.send(route, self.payload(tools=[{'name':'bash','input_schema':{}}]))[0])
        self.assertEqual(0, budget.calls)

    def test_rate_limit_and_authentication_failures_halt_shared_budget(self):
        for status in (401, 402, 403, 429):
            budget = ModelBudget(3)
            with ModelRoute('offline-secret', budget=budget,
                            transport=lambda _: (status, 'application/json', b'private raw provider body')) as route:
                first_status, body = self.send(route)
                self.assertEqual(status, first_status)
                self.assertNotIn(b'private', body)
                self.assertEqual(400, self.send(route)[0])
            self.assertEqual(1, budget.calls)

    def test_response_cannot_echo_real_credential(self):
        with ModelRoute('offline-secret', transport=lambda _: (200, 'text/plain', b'offline-secret')) as route:
            status, body = self.send(route)
        self.assertEqual(502, status)
        self.assertNotIn(b'offline-secret', body)

    def test_unicode_escaped_key_in_json_or_sse_is_not_relayed(self):
        encoded = ''.join('\\u%04x' % ord(c) for c in 'offline-secret')
        for response in (('{"content":"' + encoded + '"}').encode(),
                         ('event: content_block_delta\ndata: {"delta":{"text":"' + encoded + '"}}\n\n').encode(),
                         json.dumps({'text': '{"content":"' + encoded + '"}'}).encode()):
            with ModelRoute('offline-secret', transport=lambda _: (200, 'text/event-stream', response)) as route:
                status, body = self.send(route)
            self.assertEqual(502, status)
            self.assertNotIn(encoded.encode(), body)

    def test_key_split_across_sse_deltas_is_not_relayed(self):
        response = ''.join('data: ' + json.dumps({'type':'content_block_delta','index':0,
            'delta':{'type':'text_delta','text':part}}) + '\n\n' for part in ('offline-', 'sec', 'ret')).encode()
        with ModelRoute('offline-secret', transport=lambda _: (200, 'text/event-stream', response)) as route:
            status, body = self.send(route)
        self.assertEqual(502, status)

    def test_concurrent_budget_reservations_never_exceed_bound(self):
        budget = ModelBudget(2)
        accepted = []
        def reserve():
            try: accepted.append(budget.reserve())
            except RouteError: pass
        threads = [threading.Thread(target=reserve) for _ in range(10)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual([1, 2], sorted(accepted))


class OwnedRuntimeTest(unittest.TestCase):
    def events(self):
        return [
            {'type': 'session', 'sessionId': 'session-offline-native-shape'},
            {'type': 'status', 'phase': 'turn_start'},
            {'type': 'tool_call', 'tool': 'lexicon_ielts_read', 'input': 'discard private arguments'},
            {'type': 'thinking', 'text': 'discard private reasoning'},
            {'type': 'status', 'phase': 'step_end', 'usage': {'inputTokens': 10, 'outputTokens': 20, 'totalTokens': True, 'secret': 'discard'}},
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'completed'}},
            {'type': 'final', 'text': '{"answer":"synthetic"}'},
        ]

    def raw(self, events):
        return ('\n'.join(json.dumps(e) for e in events) + '\n').encode()

    def execution(self):
        return {'exit_code': 0, 'process_reaped': True, 'stop_reason': None, 'input_write_failed': False}

    def test_complete_terminal_retains_metadata_without_reasoning(self):
        value, receipt = validate_terminal(self.raw(self.events()), self.execution())
        self.assertEqual({'answer': 'synthetic'}, value)
        self.assertNotIn('discard', json.dumps(receipt))
        self.assertEqual([{'inputTokens': 10, 'outputTokens': 20}], receipt['usage'])

    def test_wrong_session_multiple_turns_and_partial_json_do_not_publish(self):
        variants = [self.events() + [{'type': 'session', 'sessionId': 'session-other'}],
                    self.events() + [{'type': 'status', 'phase': 'turn_start'}],
                    [e for e in self.events() if e.get('phase') != 'turn_end'],
                    self.events() + [{'type': 'error', 'message': 'private'}]]
        for events in variants:
            with self.assertRaises(HarnessError): validate_terminal(self.raw(events), self.execution())
        with self.assertRaises(HarnessError): validate_terminal(self.raw(self.events()) + b'{incomplete', self.execution())

    def test_cancellation_discards_even_complete_late_result(self):
        execution = self.execution(); execution['stop_reason'] = 'cancelled'
        with self.assertRaisesRegex(HarnessError, 'cancelled'):
            validate_terminal(self.raw(self.events()), execution)

    def test_fake_executable_is_reaped_on_timeout_and_no_secret_env_is_injected(self):
        with TemporaryDirectory(prefix='lexicon-owned-runtime-') as temp:
            script = Path(temp) / 'fake.py'
            script.write_text('import time,sys\nsys.stdin.read()\nprint("running",flush=True)\ntime.sleep(30)\n')
            raw, receipt = run_owned_process([sys.executable, str(script)], cwd=temp,
                env={'PATH': os.defpath}, prompt='offline', timeout=1)
            self.assertEqual('timeout', receipt['stop_reason'])
            self.assertTrue(receipt['process_reaped'])
            self.assertNotEqual(0, receipt['exit_code'])
            self.assertLess(receipt['elapsed_ms'], 6000)

    def test_cancel_callback_stops_owned_child_before_timeout(self):
        with TemporaryDirectory(prefix='lexicon-owned-runtime-') as temp:
            raw, receipt = run_owned_process([sys.executable, '-c', 'import time; time.sleep(30)'],
                cwd=temp, env={'PATH': os.defpath}, prompt='offline', timeout=20, cancelled=lambda: True)
            self.assertEqual('cancelled', receipt['stop_reason'])
            self.assertTrue(receipt['process_reaped'])

    def test_duplicate_json_fields_and_multiple_final_payloads_are_not_silently_merged(self):
        with self.assertRaisesRegex(HarnessError, 'incomplete_runtime_result'):
            validate_terminal(self.raw(self.events() + [self.events()[-1]]), self.execution())
        events = self.events()
        events[-1]['text'] = '{"answer":1,"answer":2}'
        with self.assertRaisesRegex(HarnessError, 'model_json_invalid'):
            validate_terminal(self.raw(events), self.execution())


class ModelRouteHardeningOfflineTest(unittest.TestCase):
    """Exercise the real route handler without opening a listening socket."""

    class MemoryServer:
        def __init__(self, address, handler):
            self.handler, self.server_port = handler, 0
        def serve_forever(self, **kwargs): pass
        def shutdown(self): pass
        def server_close(self): pass

    class MemoryConnection:
        def settimeout(self, timeout): pass

    def invoke(self, route):
        import io
        from email.message import Message
        body = json.dumps({'model': 'deepseek-flash', 'max_tokens': 1024,
                           'messages': [{'role': 'user', 'content': 'synthetic'}]}).encode()
        handler = route.server.handler.__new__(route.server.handler)
        handler.path = '/v1/messages'
        handler.headers = Message()
        handler.headers['x-api-key'] = route.token
        handler.headers['Content-Length'] = str(len(body))
        handler.rfile = io.BytesIO(body)
        handler.connection = self.MemoryConnection()
        output = []
        handler.reply = lambda status, raw, mime='application/json': output.append((status, raw, mime))
        handler.do_POST()
        return output[0]

    def test_redirects_cannot_forward_provider_credential_to_another_origin(self):
        from harness_model_proxy import NoRedirect, UPSTREAM
        request = urllib.request.Request(UPSTREAM, data=b'{}', method='POST',
                                         headers={'x-api-key': 'synthetic-only-key'})
        for status in (301, 302, 303, 307, 308):
            for target in ('https://other.example.invalid/collect',
                           'http://api.deepseek.com/anthropic/v1/messages',
                           'https://api.deepseek.com/different-path'):
                self.assertIsNone(NoRedirect().redirect_request(request, None, status, 'Redirect', {}, target))

    def test_fixed_provider_request_ignores_ambient_proxy_configuration(self):
        from unittest.mock import patch
        import harness_model_proxy as proxy
        captured = {}
        def bounded_request(url, key, payload, *, timeout):
            captured.update(url=url, key=key, timeout=timeout)
            return 200, 'application/json', b'{"content":[]}'
        with patch.dict(os.environ, {'https_proxy': 'http://untrusted.example.invalid:9999',
                                     'HTTPS_PROXY': 'http://untrusted.example.invalid:9999'}), \
             patch.object(proxy, 'bounded_provider_request', side_effect=bounded_request):
            result = proxy.ModelRoute('synthetic-only-key')._request(b'{}')
        self.assertEqual(200, result[0])
        self.assertEqual(proxy.UPSTREAM, captured['url'])
        self.assertEqual('synthetic-only-key', captured['key'])
        self.assertEqual(proxy.PROVIDER_DEADLINE_SECONDS, captured['timeout'])

    def test_auth_failure_halts_budget_before_releasing_route_capacity(self):
        from unittest.mock import patch
        import harness_model_proxy as proxy
        for failed_status in (401, 402, 403, 429):
            budget = proxy.ModelBudget(2)
            at_record, allow_record, second_validating = threading.Event(), threading.Event(), threading.Event()
            transports, outputs, record_observations = [], {}, []
            original_record, original_validate = budget.record, proxy.validate_model_request
            def transport(_):
                transports.append(True)
                return (failed_status if len(transports) == 1 else 200), 'application/json', b'{}'
            def validate(*args):
                result = original_validate(*args)
                if threading.current_thread().name == 'route-second':
                    second_validating.set()
                return result
            def record(ordinal, *args):
                if ordinal == 1:
                    record_observations.append((route.lock.locked(), route.inflight))
                    at_record.set()
                    if not allow_record.wait(3):
                        raise AssertionError('offline concurrency probe did not release first request')
                original_record(ordinal, *args)
            budget.record = record
            with patch.object(proxy.http.server, 'ThreadingHTTPServer', self.MemoryServer), \
                 patch.object(proxy, 'validate_model_request', side_effect=validate):
                with proxy.ModelRoute('synthetic-only-key', budget=budget, transport=transport) as route:
                    first = threading.Thread(target=lambda: outputs.update(first=self.invoke(route)), name='route-first')
                    second = threading.Thread(target=lambda: outputs.update(second=self.invoke(route)), name='route-second')
                    first.start()
                    try:
                        self.assertTrue(at_record.wait(3))
                        second.start()
                        self.assertTrue(second_validating.wait(3))
                    finally:
                        allow_record.set()
                        first.join(timeout=3)
                        if second.ident is not None:
                            second.join(timeout=3)
                    self.assertFalse(first.is_alive())
                    self.assertFalse(second.is_alive())
            self.assertEqual([(True, 1)], record_observations)
            self.assertEqual(failed_status, outputs['first'][0])
            self.assertEqual(400, outputs['second'][0])
            self.assertEqual(1, len(transports))
            self.assertEqual(1, budget.calls)
            self.assertTrue(budget.halted)


if __name__ == '__main__':
    unittest.main()
