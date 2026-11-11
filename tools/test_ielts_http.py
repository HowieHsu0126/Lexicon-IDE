"""Offline paired-HTTP contracts, not real TLS/provider/runtime evidence."""
import copy
from contextlib import redirect_stderr, redirect_stdout
from email.message import Message
import hashlib
import io
import json
from pathlib import Path
import ssl
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import ielts_http as api
from ielts_jobs import ExerciseJobs
from test_ielts_contract import fixture


class CountingInput(io.BytesIO):
    def __init__(self, value):
        super().__init__(value)
        self.bytes_read = 0
    def read(self, length=-1):
        result = super().read(length)
        self.bytes_read += len(result)
        return result


class PairedHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jobs = ExerciseJobs(Path(self.temp.name) / 'jobs.sqlite')
        self.request, self.package, _ = fixture()
        self.body = '\n\n'.join(p['text'] for p in self.request['paragraphs'])
        self.payload = {key: copy.deepcopy(self.request[key]) for key in
                        ('request_id', 'article_id', 'paragraphs', 'reading_requests', 'writing_task2_request')}
        self.payload.update(schema=api.SUBMIT_SCHEMA, body=self.body)
        self.token = api.generate_pairing_token()
        self.capabilities = {
            'runtime_version': '0.2.1-rc.5', 'corpus_id': 'synthetic-corpus-fixture', 'corpus_sha256': 'b' * 64,
            'supported_reading_types': list(api.contract.READING_TYPES),
            'supported_writing_task2_types': list(api.contract.TASK2_TYPES),
            'corpus_scope': 'official_public_samples_and_format_metadata',
        }
        self.submissions = []

    def submit(self, payload):
        self.submissions.append(copy.deepcopy(payload))
        request = copy.deepcopy(self.request)
        for field in ('request_id', 'article_id', 'paragraphs', 'reading_requests', 'writing_task2_request'):
            request[field] = copy.deepcopy(payload[field])
        request['body_hash'] = hashlib.sha256(payload['body'].encode()).hexdigest()
        return self.jobs.submit(request, payload['body'], self.capabilities['corpus_sha256'])

    def handler(self, path, method='GET', payload=None, *, auth=True, headers=None, callback=None):
        raw = json.dumps(payload, ensure_ascii=False).encode() if isinstance(payload, (dict, list)) else (payload or b'')
        cls = api.make_handler(token=self.token, jobs=self.jobs, on_submit=callback or self.submit,
                               capabilities=self.capabilities)
        h = cls.__new__(cls)
        h.path, h.command = path, method
        h.headers = Message()
        if auth:
            h.headers['Authorization'] = 'Bearer ' + self.token
        h.headers['Content-Type'] = 'application/json; charset=utf-8'
        h.headers['Content-Length'] = str(len(raw))
        if headers:
            for key, values in headers.items():
                del h.headers[key]
                for value in values:
                    h.headers[key] = value
        h.rfile = CountingInput(raw)
        h.results = []
        h._reply = lambda status, value: h.results.append((status, value))
        return h

    def send(self, path, method='GET', payload=None, **kwargs):
        h = self.handler(path, method, payload, **kwargs)
        h._dispatch(method)
        self.assertEqual(1, len(h.results))
        return h.results[0]

    def publish(self, mutation=None):
        job = self.submit(self.payload)
        request = copy.deepcopy(self.request)
        wrapper = {
            'schema': api.VERIFIED_SCHEMA, 'request': request, 'package': copy.deepcopy(self.package),
            'provenance': {
                # These are deliberately synthetic field fixtures for validation,
                # not receipts claiming that this test made real provider calls.
                'kind': 'deepseek_harness', 'runtime_version': self.capabilities['runtime_version'],
                'generation_session_id': 'synthetic-generation-session', 'review_session_id': 'synthetic-review-session',
                'request_sha256': api.contract.canonical_sha256(request),
                'package_sha256': api.contract.canonical_sha256(self.package),
                'review_sha256': 'd' * 64, 'corpus_sha256': self.capabilities['corpus_sha256'],
                'semantic_review': 'independent_answers_agree', 'recent_three_year_coverage': False,
                'real_provider_request_count': 3, 'created_at': 1000,
                'explanation_review_session_id': 'synthetic-explanation-session',
                'explanation_review_sha256': 'e' * 64, 'explanation_review': 'source_faithfulness_checked',
            },
        }
        if mutation:
            mutation(wrapper)
        lease, _ = self.jobs.claim(job['id'])
        self.jobs.begin_review(job['id'], lease)
        self.jobs.finish(job['id'], lease, output=wrapper)
        return job, wrapper

    def test_authentication_precedes_routing_body_reading_and_existence_lookups(self):
        for path in ('/health', '/exercise-jobs', '/exercise-jobs/by-request/known',
                     '/exercise-jobs/job-' + 'a' * 32, '/not-a-route', '/%2fprivate'):
            h = self.handler(path, 'POST', b'not json', auth=False)
            with patch.object(self.jobs, 'get', side_effect=AssertionError('must authenticate first')), \
                 patch.object(self.jobs, 'by_request', side_effect=AssertionError('must authenticate first')):
                h._dispatch('POST')
            self.assertEqual([(401, {'schema': 'lexicon.ielts.http-error.v1', 'error': {'code': 'unauthorized'}})], h.results)
            self.assertEqual(0, h.rfile.bytes_read)
        self.assertEqual([], self.submissions)

    def test_duplicate_bad_or_nonascii_authorization_is_rejected(self):
        for values in ([], ['Bearer wrong'], ['Basic x'], ['Bearer ' + self.token, 'Bearer ' + self.token], ['Bearer 非ASCII']):
            status, result = self.send('/health', headers={'Authorization': values})
            self.assertEqual(401, status)
            self.assertEqual('unauthorized', result['error']['code'])

    def test_generated_token_has_32_random_bytes_and_short_tokens_are_rejected(self):
        self.assertNotEqual(self.token, api.generate_pairing_token())
        self.assertEqual(43, len(self.token))
        for token in ('', 'short-token', 'x' * 42, 'x' * 129, 'x' * 43 + '\n', 123):
            with self.assertRaises(api.HttpContractError):
                api.make_handler(token=token, jobs=self.jobs, on_submit=self.submit, capabilities=self.capabilities)

    def test_health_exposes_only_declared_capabilities_and_no_mutable_alias(self):
        cls = api.make_handler(token=self.token, jobs=self.jobs, on_submit=self.submit, capabilities=self.capabilities)
        self.capabilities['runtime_version'] = 'changed-after-handler'
        h = cls.__new__(cls)
        h.path, h.command = '/health', 'GET'
        h.headers = Message(); h.headers['Authorization'] = 'Bearer ' + self.token
        values = []; h._reply = lambda status, value: values.append((status, value))
        h._dispatch('GET')
        self.assertEqual('0.2.1-rc.5', values[0][1]['capabilities']['runtime_version'])
        self.assertEqual(api._CAP_FIELDS, set(values[0][1]['capabilities']))
        polluted = dict(self.capabilities, api_key='DO_NOT_RETURN')
        with self.assertRaises(api.HttpContractError):
            api.make_handler(token=self.token, jobs=self.jobs, on_submit=self.submit, capabilities=polluted)

    def test_submit_returns_202_only_after_durable_enqueue(self):
        status, result = self.send('/exercise-jobs', 'POST', self.payload)
        self.assertEqual(202, status)
        stored = self.jobs.get(result['job']['id'])
        self.assertEqual(result['job'], stored)
        self.assertEqual('queued', stored['state'])
        self.assertEqual(1, len(self.submissions))

    def test_status_reconciliation_and_identical_retry_return_same_job(self):
        _, created = self.send('/exercise-jobs', 'POST', self.payload)
        _, duplicate = self.send('/exercise-jobs', 'POST', self.payload)
        self.assertEqual(created, duplicate)
        status, by_id = self.send('/exercise-jobs/' + created['job']['id'])
        self.assertEqual(200, status)
        status, by_request = self.send('/exercise-jobs/by-request/' + self.payload['request_id'])
        self.assertEqual(200, status)
        self.assertEqual(by_id, by_request)

    def test_changed_material_for_same_request_id_conflicts(self):
        self.send('/exercise-jobs', 'POST', self.payload)
        changed = copy.deepcopy(self.payload); changed['body'] += ' New content.'
        status, result = self.send('/exercise-jobs', 'POST', changed)
        self.assertEqual(409, status)
        self.assertEqual('request_id_conflict', result['error']['code'])

    def test_callback_timeout_is_not_retried_and_is_reconcilable(self):
        calls = []
        def commit_then_timeout(payload):
            calls.append(True)
            self.submit(payload)
            raise TimeoutError('PRIVATE exception body and API-like key')
        status, result = self.send('/exercise-jobs', 'POST', self.payload, callback=commit_then_timeout)
        self.assertEqual(504, status)
        self.assertEqual(self.payload['request_id'], result['error']['request_id'])
        self.assertEqual('submit_outcome_unknown', result['error']['code'])
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertEqual(1, len(calls))
        status, reconciled = self.send('/exercise-jobs/by-request/' + self.payload['request_id'])
        self.assertEqual(200, status)
        self.assertEqual('queued', reconciled['job']['state'])

    def test_callback_cannot_claim_success_without_persisting(self):
        fake = {'id': 'job-' + 'f' * 32, 'request_id': self.payload['request_id'], 'input_hash': 'a' * 64,
                'state': 'queued', 'revision': 0, 'created_at': 1, 'updated_at': 1, 'error': None, 'process_reaped': 1}
        status, _ = self.send('/exercise-jobs', 'POST', self.payload, callback=lambda _: fake)
        self.assertNotEqual(202, status)
        self.assertEqual(404, self.send('/exercise-jobs/by-request/' + self.payload['request_id'])[0])

    def test_callback_mismatched_identity_and_private_status_fields_do_not_leak(self):
        def callback(payload):
            result = self.submit(payload)
            result.update(input_hash='e' * 64, api_key='PRIVATE_CALLBACK_KEY')
            return result
        status, result = self.send('/exercise-jobs', 'POST', self.payload, callback=callback)
        self.assertEqual(500, status)
        self.assertNotIn('PRIVATE_CALLBACK_KEY', json.dumps(result))

    def test_unknown_fields_cannot_set_provider_key_model_or_files(self):
        for field in ('api_key', 'model', 'cli_path', 'url', 'body_hash'):
            payload = dict(self.payload, **{field: 'PRIVATE_UNEXPECTED_VALUE'})
            status, result = self.send('/exercise-jobs', 'POST', payload)
            self.assertEqual(400, status)
            self.assertNotIn('PRIVATE_UNEXPECTED_VALUE', json.dumps(result))
        self.assertEqual([], self.submissions)

    def test_duplicate_json_keys_at_top_and_nested_levels_are_rejected(self):
        raw = json.dumps(self.payload).encode()
        top = raw.replace(b'"schema":', b'"schema":"ignored","schema":', 1)
        nested = raw.replace(b'"paragraph_id":', b'"paragraph_id":"ignored","paragraph_id":', 1)
        for body in (top, nested, b'{"a":NaN}', b'\xff', b'[]'):
            status, _ = self.send('/exercise-jobs', 'POST', body)
            self.assertEqual(400, status)
        self.assertEqual([], self.submissions)

    def test_request_framing_and_content_type_are_strict(self):
        raw = json.dumps(self.payload).encode()
        variants = [({'Content-Length': []}, 400), ({'Content-Length': ['1', '2']}, 400),
                    ({'Content-Length': ['-1']}, 400), ({'Content-Length': [str(len(raw) + 1)]}, 400),
                    ({'Transfer-Encoding': ['chunked']}, 400), ({'Transfer-Encoding': ['']}, 400),
                    ({'Content-Encoding': ['gzip']}, 400), ({'Content-Type': ['text/plain']}, 415),
                    ({'Content-Type': ['application/json', 'application/json']}, 415)]
        for headers, expected in variants:
            status, _ = self.send('/exercise-jobs', 'POST', raw, headers=headers)
            self.assertEqual(expected, status)
        self.assertEqual([], self.submissions)

    def test_declared_oversize_body_is_rejected_before_read(self):
        h = self.handler('/exercise-jobs', 'POST', b'x', headers={'Content-Length': ['500001']})
        h._dispatch('POST')
        self.assertEqual(413, h.results[0][0])
        self.assertEqual(0, h.rfile.bytes_read)
        with self.assertRaises(api.HttpContractError) as error:
            api.decode_submit(b' ' * (api.MAX_REQUEST_BYTES + 1))
        self.assertEqual(413, error.exception.status)

    def test_full_body_byte_limit_and_selection_unit_limit_are_independent(self):
        payload = copy.deepcopy(self.payload)
        payload['body'] += '界' * 70_000
        self.assertEqual(413, self.send('/exercise-jobs', 'POST', payload)[0])
        text = 'a' * 24_001
        payload = copy.deepcopy(self.payload)
        payload['body'] = text
        payload['paragraphs'] = [{'paragraph_id': 'p1', 'start': 0, 'end': len(text), 'text': text}]
        self.assertNotEqual(202, self.send('/exercise-jobs', 'POST', payload)[0])
        self.assertEqual([], self.submissions)

    def test_source_ranges_are_precise_utf16_and_do_not_search_repeated_words(self):
        self.assertEqual(self.payload, api.validate_submit(self.payload))
        mutations = [lambda p: p['paragraphs'][0].update(text=p['paragraphs'][0]['text'].replace('council', 'Council')),
                     lambda p: p['paragraphs'][0].update(start=1),
                     lambda p: p['paragraphs'][0].update(start=True),
                     lambda p: p['paragraphs'].reverse(),
                     lambda p: p['paragraphs'][1].update(paragraph_id='p1')]
        for change in mutations:
            payload = copy.deepcopy(self.payload); change(payload)
            self.assertEqual(400, self.send('/exercise-jobs', 'POST', payload)[0])
        self.assertEqual([], self.submissions)

    def test_question_ids_types_and_completion_limits_are_validated(self):
        mutations = [lambda p: p['reading_requests'][0].update(type='invented'),
                     lambda p: p['reading_requests'][1].update(question_id='q1'),
                     lambda p: p['reading_requests'][3].update(max_words=True),
                     lambda p: p['reading_requests'][3].update(max_words=9),
                     lambda p: p['reading_requests'][3].update(allow_numbers=1),
                     lambda p: p['writing_task2_request'].update(task_id='q1'),
                     lambda p: p['writing_task2_request'].update(task_type='discuss_both_views')]
        for change in mutations:
            payload = copy.deepcopy(self.payload); change(payload)
            self.assertEqual(400, self.send('/exercise-jobs', 'POST', payload)[0])

    def test_route_ids_queries_and_percent_paths_never_become_other_resources(self):
        for path in ('/exercise-jobs/by-request/../../API.txt', '/exercise-jobs/%2e%2e',
                     '/exercise-jobs/by-request/abc?path=API.txt', '/exercise-jobs/by-request/' + 'a' * 129,
                     'https://example.invalid/health', '/private/API.txt', '/exercise-jobs/job-short',
                     '/exercise-jobs/by-request/test\\file'):
            self.assertEqual(404, self.send(path)[0])
        for path in ('/exercise-jobs/by-request/unknown', '/exercise-jobs/job-' + 'c' * 32):
            self.assertEqual(404, self.send(path)[0])

    def test_cancel_preserves_active_cancelling_semantics_and_nonready_package_denial(self):
        job = self.submit(self.payload)
        status, cancelled = self.send('/exercise-jobs/' + job['id'] + '/cancel', 'POST')
        self.assertEqual(202, status)
        self.assertEqual('cancelled', cancelled['job']['state'])
        self.assertEqual(409, self.send('/exercise-jobs/' + job['id'] + '/package')[0])
        payload = copy.deepcopy(self.payload); payload['request_id'] += '-active'
        active = self.submit(payload); lease, _ = self.jobs.claim(active['id'])
        self.jobs.set_process_active(active['id'], lease)
        _, current = self.send('/exercise-jobs/' + active['id'] + '/cancel', 'POST')
        self.assertEqual('cancelling', current['job']['state'])
        self.assertEqual(0, current['job']['process_reaped'])

    def test_cancel_and_query_do_not_silently_accept_undeclared_bodies(self):
        job = self.submit(self.payload)
        for path, method in (('/health', 'GET'), ('/exercise-jobs/' + job['id'], 'GET'),
                             ('/exercise-jobs/' + job['id'] + '/cancel', 'POST')):
            self.assertEqual(400, self.send(path, method, {'api_key': 'UNDECLARED'})[0])
            h = self.handler(path, method, b'x', headers={'Content-Length': ['500001']})
            h._dispatch(method)
            self.assertEqual(413, h.results[0][0])
            self.assertEqual(0, h.rfile.bytes_read)
        self.assertEqual('queued', self.jobs.get(job['id'])['state'])

    def test_verified_package_keeps_offline_answers_but_candidate_does_not(self):
        job, wrapper = self.publish()
        status, full = self.send('/exercise-jobs/' + job['id'] + '/package')
        self.assertEqual(200, status)
        self.assertEqual(wrapper, full)
        self.assertEqual('A', full['package']['reading_questions'][0]['answer'])
        status, candidate = self.send('/exercise-jobs/' + job['id'] + '/candidate')
        self.assertEqual(200, status)
        self.assertEqual('lexicon.ielts.candidate-view.v1', candidate['schema'])
        self.assertNotIn('SECRET_RATIONALE', json.dumps(candidate))
        self.assertNotIn('"answer"', json.dumps(candidate))

    def test_verified_wrapper_rejects_private_fields_raw_package_and_synthetic_runtime_kind(self):
        job, wrapper = self.publish()
        cases = [self.package, dict(wrapper, runtime_log='PRIVATE_TRACE'),
                 dict(wrapper, provenance=dict(wrapper['provenance'], kind='synthetic_runtime_fixture')),
                 dict(wrapper, provenance=dict(wrapper['provenance'], api_key='PRIVATE_KEY'))]
        for case in cases:
            with patch.object(self.jobs, 'package', return_value=case):
                status, value = self.send('/exercise-jobs/' + job['id'] + '/package')
            self.assertEqual(502, status)
            self.assertNotIn('PRIVATE', json.dumps(value))

    def test_verified_wrapper_recomputes_identity_and_requires_independent_real_count(self):
        job, wrapper = self.publish()
        mutations = [lambda w: w['provenance'].update(generation_session_id=w['provenance']['review_session_id']),
                     lambda w: w['provenance'].update(request_sha256='f' * 64),
                     lambda w: w['provenance'].update(package_sha256='f' * 64),
                     lambda w: w['provenance'].update(corpus_sha256='f' * 64),
                     lambda w: w['provenance'].update(real_provider_request_count=1),
                     lambda w: w['provenance'].update(real_provider_request_count=True),
                     lambda w: w['provenance'].update(recent_three_year_coverage=True),
                     lambda w: w['package']['reading_questions'][0].update(answer='E'),
                     lambda w: w['request']['paragraphs'][0].update(text='forged')]
        for change in mutations:
            changed = copy.deepcopy(wrapper); change(changed)
            with patch.object(self.jobs, 'package', return_value=changed):
                self.assertEqual(502, self.send('/exercise-jobs/' + job['id'] + '/package')[0])

    def test_new_network_packages_require_complete_explanation_review_provenance(self):
        job, wrapper = self.publish()
        mutations = [
            lambda p: p.pop('explanation_review'),
            lambda p: p.pop('explanation_review_sha256'),
            lambda p: p.pop('explanation_review_session_id'),
            lambda p: p.update(explanation_review='structure_only'),
            lambda p: p.update(explanation_review_sha256='missing'),
            lambda p: p.update(explanation_review_session_id=p['review_session_id']),
            lambda p: p.update(explanation_review_session_id=p['generation_session_id']),
            lambda p: p.update(real_provider_request_count=2),
            lambda p: p.update(real_provider_request_count=17),
        ]
        for mutate in mutations:
            changed = copy.deepcopy(wrapper); mutate(changed['provenance'])
            with patch.object(self.jobs, 'package', return_value=changed):
                self.assertEqual(502, self.send('/exercise-jobs/' + job['id'] + '/package')[0])

    def test_methods_parser_errors_and_expect_continue_are_authenticated_without_echo(self):
        for method in ('PUT', 'PATCH', 'DELETE', 'OPTIONS'):
            self.assertEqual(401, self.send('/health', method, auth=False)[0])
            self.assertEqual(405, self.send('/health', method)[0])
        for auth, expected in ((False, 401), (True, 405)):
            h = self.handler('/health', auth=auth)
            h.send_error(501, 'PRIVATE RAW REQUEST')
            self.assertEqual(expected, h.results[0][0])
            self.assertNotIn('PRIVATE', json.dumps(h.results))
        h = self.handler('/exercise-jobs', 'POST', auth=False)
        self.assertFalse(h.handle_expect_100())
        self.assertEqual(401, h.results[0][0])

    def test_exception_and_access_logging_never_print_body_or_tokens(self):
        def failure(_):
            raise RuntimeError('PRIVATE_KEY_OR_MATERIAL')
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status, value = self.send('/exercise-jobs', 'POST', self.payload, callback=failure)
            h = self.handler('/health'); h.log_message('PRIVATE %s', self.token)
        self.assertEqual(500, status)
        self.assertEqual('', stdout.getvalue() + stderr.getvalue())
        self.assertNotIn('PRIVATE', json.dumps(value))
        self.assertNotIn(self.token, json.dumps(value))

    def test_actual_reply_headers_disable_cache_and_never_echo_pairing_token(self):
        cls = api.make_handler(token=self.token, jobs=self.jobs, on_submit=self.submit, capabilities=self.capabilities)
        h = cls.__new__(cls); h.command = 'GET'; h.wfile = io.BytesIO()
        codes, headers = [], {}
        h.send_response = codes.append
        h.send_header = lambda key, value: headers.update({key: value})
        h.end_headers = lambda: None
        h._error('unauthorized', 401)
        self.assertEqual([401], codes)
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual('close', headers['Connection'])
        self.assertEqual('Bearer', headers['WWW-Authenticate'])
        self.assertNotIn(self.token.encode(), h.wfile.getvalue())


class TlsServerConstructionTest(unittest.TestCase):
    def arguments(self):
        return dict(token=api.generate_pairing_token(), jobs=object(), on_submit=lambda p: None,
                    capabilities={'runtime_version': '0.2.1-rc.5', 'corpus_id': 'synthetic', 'corpus_sha256': 'b' * 64,
                                  'supported_reading_types': [], 'supported_writing_task2_types': [],
                                  'corpus_scope': 'official_public_samples_and_format_metadata'})

    def test_production_constructor_rejects_missing_client_or_weak_tls_context(self):
        client = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        weak = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        weak.minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED
        for context in (None, object(), client, weak):
            with patch.object(api.http.server, 'ThreadingHTTPServer', side_effect=AssertionError('must reject before bind')):
                with self.assertRaises(api.HttpContractError):
                    api.create_server(tls_context=context, **self.arguments())

    def test_default_bind_is_loopback_and_constructor_never_serves_or_starts_worker(self):
        class MemoryServer:
            def __init__(self, address, handler):
                self.address, self.handler, self.started = address, handler, False
            def serve_forever(self): self.started = True
            def get_request(self):
                raise AssertionError('No connection should be accepted by construction')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        with patch.object(api.http.server, 'ThreadingHTTPServer', MemoryServer):
            server = api.create_server(tls_context=context, **self.arguments())
        self.assertEqual(('127.0.0.1', 0), server.address)
        self.assertFalse(server.started)

    def test_tls_handshake_is_deferred_until_bounded_connection_io(self):
        class Connection:
            def settimeout(self, value): self.timeout = value
            def close(self): self.closed = True
        connection, secured = Connection(), object()
        class MemoryServer:
            def __init__(self, address, handler): pass
            def get_request(self): return connection, ('127.0.0.1', 1)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        with patch.object(api.http.server, 'ThreadingHTTPServer', MemoryServer), \
             patch.object(context, 'wrap_socket', return_value=secured) as wrap:
            server = api.create_server(tls_context=context, **self.arguments())
            result = server.get_request()
        self.assertIs(secured, result[0])
        self.assertEqual(api.SOCKET_TIMEOUT, connection.timeout)
        wrap.assert_called_once_with(connection, server_side=True, do_handshake_on_connect=False)


if __name__ == '__main__':
    unittest.main()
