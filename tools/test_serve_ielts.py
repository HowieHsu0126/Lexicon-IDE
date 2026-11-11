"""Offline host orchestration with fake runners/listeners; no model or daemon."""
import copy
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import threading
import types
import unittest
from unittest.mock import patch

from ielts_corpus import build
from ielts_http import generate_pairing_token, HttpContractError
from ielts_jobs import ExerciseJobs
from ielts_pipeline import normalize_submission
import serve_ielts as host_api
from test_ielts_contract import fixture


class HostFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = TemporaryDirectory()
        cls.corpus_path = Path(cls.shared.name) / 'corpus.json'
        cls.corpus_sha = build(cls.corpus_path)['corpus_sha256']

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'state'
        self.key = 'sk-offline-only-credential-fixture'
        self.token = generate_pairing_token()
        self.key_path, self.token_path = self.root / 'api.txt', self.root / 'pair.txt'
        self.key_path.write_text(self.key + '\n')
        self.token_path.write_text(self.token + '\n')
        self.config = host_api.HostConfig(self.root / 'cert.pem', self.root / 'tls.key', self.token_path,
                                         self.key_path, self.state, self.corpus_path, shutdown_timeout=2)
        request, _, _ = fixture()
        self.payload = {key: copy.deepcopy(request[key]) for key in
                        ('request_id', 'article_id', 'paragraphs', 'reading_requests', 'writing_task2_request')}
        self.payload.update(schema='lexicon.ielts.submit.v1', body='\n\n'.join(p['text'] for p in request['paragraphs']))
        self.jobs = ExerciseJobs(self.state / 'jobs.sqlite')

    def enqueue(self, suffix=''):
        payload = copy.deepcopy(self.payload)
        payload['request_id'] += suffix
        request, body, digest = normalize_submission(payload, self.corpus_path)
        return self.jobs.submit(request, body, digest)

    def service(self, runner, **kwargs):
        return host_api.HostService(self.jobs, corpus_path=self.corpus_path, corpus_sha256=self.corpus_sha,
                                    key=self.key, token=self.token, work_root=self.state / 'work', runner=runner, **kwargs)

    @staticmethod
    def finish_fake(jobs, identifier, **kwargs):
        lease, _ = jobs.claim(identifier)
        jobs.begin_review(identifier, lease)
        return jobs.finish(identifier, lease, output={'synthetic_fixture': True})


class WorkerLifecycleTest(HostFixture):
    def test_invalid_generation_model_is_rejected_before_job_claim(self):
        job = self.enqueue()
        with patch.object(self.jobs, 'claim', side_effect=AssertionError('must not claim a job')) as claim:
            for model in ('pro', 'other', None, [], True):
                with self.subTest(model=model):
                    with self.assertRaisesRegex(host_api.HostError, 'host_model_invalid'):
                        self.service(self.finish_fake, generation_model=model)
            claim.assert_not_called()
        self.assertEqual('queued', self.jobs.get(job['id'])['state'])

    def test_single_non_daemon_worker_processes_jobs_serially(self):
        first_started, release, completed = threading.Event(), threading.Event(), threading.Event()
        calls, in_flight, maximum = [], [0], [0]
        lock = threading.Lock()
        def runner(jobs, identifier, **kwargs):
            lease, _ = jobs.claim(identifier)
            with lock:
                calls.append(identifier); in_flight[0] += 1; maximum[0] = max(maximum[0], in_flight[0])
            if len(calls) == 1:
                first_started.set()
                if not release.wait(3): raise AssertionError('test release missing')
            jobs.begin_review(identifier, lease)
            result = jobs.finish(identifier, lease, output={'synthetic_fixture': True})
            with lock:
                in_flight[0] -= 1
                if len(calls) == 3: completed.set()
            return result
        service = self.service(runner)
        service.start()
        self.assertFalse(service.thread.daemon)
        try:
            first = service.on_submit(self.payload)
            self.assertTrue(first_started.wait(3))
            self.assertEqual(first['id'], service.on_submit(self.payload)['id'])
            for suffix in ('-two', '-three'):
                payload = copy.deepcopy(self.payload); payload['request_id'] += suffix
                service.on_submit(payload)
            self.assertEqual(1, len(calls))
            release.set()
            self.assertTrue(completed.wait(3))
        finally:
            release.set()
            receipt = service.close(timeout=3)
        self.assertEqual(3, len(set(calls)))
        self.assertEqual(1, maximum[0])
        self.assertEqual('stopped', receipt['status'])
        self.assertEqual([], self.jobs.queued())

    def test_shutdown_cancels_only_owned_active_and_leaves_pending_queued(self):
        active_started = threading.Event()
        def runner(jobs, identifier, **kwargs):
            lease, _ = jobs.claim(identifier)
            token = jobs.set_process_active(identifier, lease)
            active_started.set()
            for _ in range(300):
                if jobs.cancellation_requested(identifier, lease): break
                threading.Event().wait(.01)
            else: raise AssertionError('expected cancellation')
            jobs.record_stage(identifier, lease, token, {'synthetic_fixture': True}, reaped=True)
            return jobs.finish(identifier, lease, error='cancelled')
        service = self.service(runner)
        service.start()
        first = service.on_submit(self.payload)
        self.assertTrue(active_started.wait(3))
        payload = copy.deepcopy(self.payload); payload['request_id'] += '-pending'
        pending = service.on_submit(payload)
        result = service.close(timeout=3)
        self.assertEqual('stopped', result['status'])
        self.assertTrue(result['worker_joined'])
        self.assertTrue(result['children_reaped_confirmed'])
        self.assertEqual('cancelled', self.jobs.get(first['id'])['state'])
        self.assertEqual('queued', self.jobs.get(pending['id'])['state'])

    def test_late_submission_after_stop_is_not_persisted(self):
        service = self.service(self.finish_fake)
        service.start(); service.close(timeout=2)
        with self.assertRaises(HttpContractError) as error:
            service.on_submit(self.payload)
        self.assertEqual(503, error.exception.status)
        self.assertEqual([], self.jobs.queued())

    def test_stop_during_normalization_cannot_commit_after_accepting_is_revoked(self):
        entered, release = threading.Event(), threading.Event()
        def normalizer(payload, path):
            entered.set()
            if not release.wait(3): raise AssertionError('test normalization release missing')
            return normalize_submission(payload, path)
        service = self.service(self.finish_fake, normalizer=normalizer)
        service.start()
        errors = []
        def submit():
            try: service.on_submit(self.payload)
            except HttpContractError as error: errors.append(error.status)
        thread = threading.Thread(target=submit)
        thread.start()
        self.assertTrue(entered.wait(3))
        service.request_stop()
        release.set(); thread.join(timeout=3)
        service.close(timeout=3)
        self.assertEqual([503], errors)
        self.assertEqual([], self.jobs.queued())

    def test_api_key_and_pairing_token_are_rejected_before_normalization_or_write(self):
        normalized = []
        def normalizer(payload, path):
            normalized.append(True)
            return normalize_submission(payload, path)
        service = self.service(self.finish_fake, normalizer=normalizer)
        service.start()
        try:
            for secret in (self.key, self.token):
                payload = copy.deepcopy(self.payload); payload['body'] += '\n' + secret
                with self.assertRaisesRegex(HttpContractError, 'credential_in_input'):
                    service.on_submit(payload)
        finally:
            service.close(timeout=3)
        self.assertEqual([], normalized)
        for path in self.state.rglob('*'):
            if path.is_file():
                self.assertNotIn(self.key.encode(), path.read_bytes())
                self.assertNotIn(self.token.encode(), path.read_bytes())

    def test_secret_introduced_by_normalizer_is_also_rejected_before_enqueue(self):
        def normalizer(payload, path):
            request, body, digest = normalize_submission(payload, path)
            request['style_sources'][0]['summary'] += self.key
            return request, body, digest
        service = self.service(self.finish_fake, normalizer=normalizer)
        service.start()
        try:
            with self.assertRaisesRegex(HttpContractError, 'credential_in_input'):
                service.on_submit(self.payload)
        finally:
            service.close(timeout=2)
        self.assertEqual([], self.jobs.queued())

    def test_changed_corpus_cannot_be_accepted_during_service(self):
        def normalizer(payload, path):
            request, body, _ = normalize_submission(payload, path)
            return request, body, 'a' * 64
        service = self.service(self.finish_fake, normalizer=normalizer)
        service.start()
        try:
            with self.assertRaisesRegex(HttpContractError, 'corpus_changed'):
                service.on_submit(self.payload)
        finally:
            service.close(timeout=2)
        self.assertEqual([], self.jobs.queued())

    def test_incomplete_worker_return_is_interrupted_not_requeued(self):
        returned = threading.Event()
        def runner(jobs, identifier, **kwargs):
            jobs.claim(identifier)
            returned.set()
            return {'state': 'pretend_completed'}
        service = self.service(runner)
        service.start(); job = service.on_submit(self.payload)
        self.assertTrue(returned.wait(3))
        self.assertTrue(service.stop_event.wait(3))
        result = service.close(timeout=2)
        self.assertEqual('worker_completion_unconfirmed', result['error'])
        self.assertEqual('interrupted', self.jobs.get(job['id'])['state'])
        self.assertEqual([], self.jobs.queued())

    def test_unreaped_worker_return_stops_queue_and_never_claims_clean_shutdown(self):
        def runner(jobs, identifier, **kwargs):
            lease, _ = jobs.claim(identifier)
            jobs.set_process_active(identifier, lease)
            return {'state': 'unknown'}
        service = self.service(runner)
        service.start(); job = service.on_submit(self.payload)
        self.assertTrue(service.stop_event.wait(3))
        result = service.close(timeout=2)
        self.assertEqual('shutdown_unconfirmed', result['status'])
        self.assertTrue(result['worker_joined'])
        self.assertFalse(result['children_reaped_confirmed'])
        self.assertEqual(0, self.jobs.get(job['id'])['process_reaped'])

    def test_close_timeout_retains_unconfirmed_status_until_worker_actually_exits(self):
        started, release = threading.Event(), threading.Event()
        def runner(jobs, identifier, **kwargs):
            lease, _ = jobs.claim(identifier)
            token = jobs.set_process_active(identifier, lease)
            started.set()
            if not release.wait(3): raise AssertionError('test release missing')
            jobs.record_stage(identifier, lease, token, {}, reaped=True)
            return jobs.finish(identifier, lease, error='cancelled')
        service = self.service(runner)
        service.start(); service.on_submit(self.payload)
        self.assertTrue(started.wait(3))
        first = service.close(timeout=0)
        self.assertEqual('shutdown_unconfirmed', first['status'])
        self.assertFalse(first['worker_joined'])
        release.set()
        final = service.close(timeout=3)
        self.assertEqual('stopped', final['status'])


class ForegroundServiceTest(HostFixture):
    def fake_server(self, stop, on_request, record, close_failure=False):
        class Server:
            def __init__(self, bind, port, **kwargs):
                self.callback = kwargs['on_submit']
                record['bind'] = bind
                record['capabilities'] = kwargs['capabilities']
                record['created'] = True
            def handle_request(self):
                on_request(self.callback)
                stop.set()
            def server_close(self):
                record['closed'] = True
                if close_failure: raise RuntimeError('PRIVATE_CLOSE_DIAGNOSTIC')
        return Server

    def exercise_model_configuration(self, config):
        record, selected, announcements = {}, [], []
        stop, completed = threading.Event(), threading.Event()
        def runner(jobs, identifier, **kwargs):
            selected.append({name: kwargs[name] for name in
                             ('generation_model', 'review_model', 'review_reasoning',
                              'explanation_model', 'explanation_reasoning', 'max_calls', 'timeout')})
            result = self.finish_fake(jobs, identifier, **kwargs)
            completed.set()
            return result
        def request(callback):
            callback(self.payload)
            self.assertTrue(completed.wait(3))
        result = host_api.serve(config, runner=runner,
            server_factory=self.fake_server(stop, request, record), tls_factory=lambda *_: object(),
            runtime_probe=lambda: '0.2.1-rc.5', stop_event=stop, install_signals=False,
            announce=announcements.append)
        self.assertEqual('stopped', result['status'])
        self.assertEqual(1, len(selected))
        self.assertEqual({'runtime_version', 'corpus_id', 'corpus_sha256', 'supported_reading_types',
                          'supported_writing_task2_types', 'supported_writing_task1_policies', 'corpus_scope'}, set(record['capabilities']))
        self.assertNotIn(self.key, json.dumps([selected, announcements, record['capabilities']]))
        self.assertNotIn(self.token, json.dumps([selected, announcements, record['capabilities']]))
        return selected[0], announcements[0]

    def test_default_host_reports_pro_high_explanation_and_preserves_flash_off_blind_review(self):
        selected, announcement = self.exercise_model_configuration(self.config)
        self.assertEqual({'generation_model': 'deepseek-flash',
                          'review_model': 'deepseek-flash', 'review_reasoning': 'off',
                          'explanation_model': 'deepseek-v4-pro', 'explanation_reasoning': 'high',
                          'max_calls': 12, 'timeout': 240}, selected)
        self.assertEqual('deepseek-flash', announcement['generation_model'])
        self.assertEqual('off', announcement['generation_reasoning'])
        self.assertEqual('deepseek-flash', announcement['review_model'])
        self.assertEqual('off', announcement['review_reasoning'])
        self.assertEqual('deepseek-v4-pro', announcement['explanation_model'])
        self.assertEqual('high', announcement['explanation_reasoning'])

    def test_explicit_host_model_maps_pro_high_and_keeps_existing_budget_bounds(self):
        config = replace(self.config, review_model='pro', review_reasoning='high', max_calls=16, timeout=600)
        selected, announcement = self.exercise_model_configuration(config)
        self.assertEqual({'generation_model': 'deepseek-flash',
                          'review_model': 'deepseek-v4-pro', 'review_reasoning': 'high',
                          'explanation_model': 'deepseek-v4-pro', 'explanation_reasoning': 'high',
                          'max_calls': 16, 'timeout': 600}, selected)
        self.assertEqual('deepseek-v4-pro', announcement['review_model'])
        self.assertEqual('high', announcement['review_reasoning'])

    def test_explicit_generation_pro_reaches_worker_with_reasoning_off(self):
        config = replace(self.config, generation_model='pro', review_model='pro', review_reasoning='high')
        selected, announcement = self.exercise_model_configuration(config)
        self.assertEqual({'generation_model': 'deepseek-v4-pro', 'review_model': 'deepseek-v4-pro',
                          'review_reasoning': 'high', 'explanation_model': 'deepseek-v4-pro',
                          'explanation_reasoning': 'high', 'max_calls': 12, 'timeout': 240}, selected)
        self.assertEqual('deepseek-v4-pro', announcement['generation_model'])
        self.assertEqual('off', announcement['generation_reasoning'])
        self.assertEqual('deepseek-v4-pro', announcement['review_model'])
        self.assertEqual('high', announcement['review_reasoning'])

    def test_explicit_explanation_settings_only_change_the_explanation_stage(self):
        config = replace(self.config, explanation_model='flash', explanation_reasoning='low')
        selected, announcement = self.exercise_model_configuration(config)
        self.assertEqual({'generation_model': 'deepseek-flash', 'review_model': 'deepseek-flash',
                          'review_reasoning': 'off', 'explanation_model': 'deepseek-flash',
                          'explanation_reasoning': 'low', 'max_calls': 12, 'timeout': 240}, selected)
        self.assertEqual('deepseek-flash', announcement['explanation_model'])
        self.assertEqual('low', announcement['explanation_reasoning'])
        self.assertEqual('off', announcement['review_reasoning'])

    def test_startup_lock_precedes_recovery_and_only_pending_queue_runs(self):
        old = self.enqueue('-old'); self.jobs.claim(old['id'])
        pending = self.enqueue('-pending')
        events, seen, record = [], [], {}
        completed, stop = threading.Event(), threading.Event()
        parent = self
        class ObservedJobs(ExerciseJobs):
            def recover_interrupted(self):
                with parent.assertRaisesRegex(host_api.HostError, 'another_host_owns_state'):
                    host_api.OwnershipLock(parent.state / '.gateway-owner.lock').acquire()
                events.append('recover')
                return super().recover_interrupted()
        def runner(jobs, identifier, **kwargs):
            seen.append(identifier)
            result = self.finish_fake(jobs, identifier, **kwargs)
            completed.set()
            return result
        def request(_): self.assertTrue(completed.wait(3))
        announcements = []
        result = host_api.serve(self.config, jobs_factory=ObservedJobs, runner=runner,
            server_factory=self.fake_server(stop, request, record), tls_factory=lambda *_: object(),
            runtime_probe=lambda: events.append('version') or '0.2.1-rc.5', stop_event=stop,
            install_signals=False, announce=announcements.append)
        self.assertLess(events.index('recover'), events.index('version'))
        self.assertEqual([pending['id']], seen)
        self.assertEqual('interrupted', self.jobs.get(old['id'])['state'])
        self.assertEqual('stopped', result['status'])
        self.assertEqual('127.0.0.1', record['bind'])
        self.assertNotIn(self.key, json.dumps(announcements + [record['capabilities']]))
        self.assertNotIn(self.token, json.dumps(announcements + [record['capabilities']]))
        owner = host_api.OwnershipLock(self.state / '.gateway-owner.lock').acquire(); owner.release()

    def test_previous_unreaped_child_blocks_start_before_runtime_or_listener(self):
        job = self.enqueue(); lease, _ = self.jobs.claim(job['id'])
        self.jobs.set_process_active(job['id'], lease)
        for _ in range(2):
            with self.assertRaisesRegex(host_api.HostError, 'previous_child_completion_unconfirmed'):
                host_api.serve(self.config, runtime_probe=lambda: self.fail('must not probe runtime'),
                               server_factory=lambda *_a, **_k: self.fail('must not bind'), install_signals=False)
        self.assertEqual('interrupted', self.jobs.get(job['id'])['state'])
        self.assertEqual([], self.jobs.queued())

    def test_foreground_request_normalizes_and_persists_before_fake_work(self):
        record, seen = {}, []
        stop, completed = threading.Event(), threading.Event()
        def runner(jobs, identifier, **kwargs):
            seen.append(identifier)
            result = self.finish_fake(jobs, identifier, **kwargs)
            completed.set()
            return result
        def request(callback):
            job = callback(self.payload)
            self.assertEqual(job['id'], self.jobs.by_request(self.payload['request_id'])['id'])
            self.assertTrue(completed.wait(3))
        result = host_api.serve(self.config, runner=runner,
            server_factory=self.fake_server(stop, request, record), tls_factory=lambda *_: object(),
            runtime_probe=lambda: '0.2.1-rc.5', stop_event=stop, install_signals=False)
        self.assertEqual(1, len(seen))
        self.assertTrue(record['closed'])
        self.assertEqual('stopped', result['status'])

    def test_listener_close_failure_does_not_skip_worker_cleanup(self):
        stop, record = threading.Event(), {}
        result = host_api.serve(self.config, runner=self.finish_fake,
            server_factory=self.fake_server(stop, lambda _: None, record, close_failure=True),
            tls_factory=lambda *_: object(), runtime_probe=lambda: '0.2.1-rc.5',
            stop_event=stop, install_signals=False)
        self.assertTrue(result['worker_joined'])
        self.assertTrue(result['children_reaped_confirmed'])
        self.assertEqual('listener_close_failed', result['error'])
        owner = host_api.OwnershipLock(self.state / '.gateway-owner.lock').acquire(); owner.release()

    def test_tls_startup_failure_starts_no_worker_and_releases_lock(self):
        def bad_tls(*_): raise host_api.HostError('tls_material_invalid')
        with self.assertRaisesRegex(host_api.HostError, 'tls_material_invalid'):
            host_api.serve(self.config, runner=lambda *_a, **_k: self.fail('must not run'),
                tls_factory=bad_tls, runtime_probe=lambda: '0.2.1-rc.5', install_signals=False)
        owner = host_api.OwnershipLock(self.state / '.gateway-owner.lock').acquire(); owner.release()

    def test_second_owner_is_rejected_before_recovery_or_runtime(self):
        owner = host_api.OwnershipLock(self.state / '.gateway-owner.lock').acquire()
        try:
            with self.assertRaisesRegex(host_api.HostError, 'another_host_owns_state'):
                host_api.serve(self.config, jobs_factory=lambda _: self.fail('must not open/recover store'),
                               runtime_probe=lambda: self.fail('must not probe runtime'), install_signals=False)
        finally:
            owner.release()


class HostConfigurationTest(HostFixture):
    def flags(self):
        return ['--tls-cert', str(self.config.tls_cert), '--tls-private-key', str(self.config.tls_private_key),
                '--pairing-token-file', str(self.token_path), '--api-key-file', str(self.key_path),
                '--state-dir', str(self.state), '--corpus', str(self.corpus_path)]

    def test_cli_defaults_to_loopback_and_lan_requires_explicit_bind(self):
        self.assertEqual('127.0.0.1', host_api.parse_config(self.flags()).bind)
        explicit = host_api.parse_config(self.flags() + ['--bind', '0.0.0.0'])
        host_api.validate_config(explicit)
        self.assertEqual('0.0.0.0', explicit.bind)

    def test_legacy_positional_host_config_keeps_review_fields_and_default_generator(self):
        config = host_api.HostConfig(self.config.tls_cert, self.config.tls_private_key,
            self.config.pairing_token_file, self.config.api_key_file, self.config.state_dir, self.config.corpus,
            '127.0.0.1', 8443, 12, 240, 30, 'pro', 'low')
        host_api.validate_config(config)
        self.assertEqual(('flash', 'pro', 'low'),
                         (config.generation_model, config.review_model, config.review_reasoning))
        self.assertEqual(('pro', 'high'), (config.explanation_model, config.explanation_reasoning))

    def test_cli_review_model_options_are_explicit_and_default_to_flash_off(self):
        default = host_api.parse_config(self.flags())
        self.assertEqual(('flash', 'off'), (default.review_model, default.review_reasoning))
        for model in ('flash', 'pro'):
            for reasoning in ('off', 'low', 'high', 'max'):
                with self.subTest(model=model, reasoning=reasoning):
                    config = host_api.parse_config(self.flags() + ['--review-model', model, '--review-reasoning', reasoning])
                    host_api.validate_config(config)
                    self.assertEqual((model, reasoning), (config.review_model, config.review_reasoning))
                    self.assertEqual(('pro', 'high'), (config.explanation_model, config.explanation_reasoning))

    def test_cli_explanation_settings_default_to_pro_high_and_do_not_change_blind_review(self):
        default = host_api.parse_config(self.flags())
        self.assertEqual(('pro', 'high'), (default.explanation_model, default.explanation_reasoning))
        for model in ('flash', 'pro'):
            for reasoning in ('off', 'low', 'high', 'max'):
                with self.subTest(model=model, reasoning=reasoning):
                    config = host_api.parse_config(self.flags() + ['--explanation-model', model, '--explanation-reasoning', reasoning])
                    host_api.validate_config(config)
                    self.assertEqual((model, reasoning), (config.explanation_model, config.explanation_reasoning))
                    self.assertEqual(('flash', 'off'), (config.review_model, config.review_reasoning))

    def test_cli_generation_model_options_default_to_flash_and_allow_pro(self):
        default = host_api.parse_config(self.flags())
        self.assertEqual('flash', default.generation_model)
        for model in ('flash', 'pro'):
            with self.subTest(model=model):
                config = host_api.parse_config(self.flags() + ['--generation-model', model])
                host_api.validate_config(config)
                self.assertEqual(model, config.generation_model)
                self.assertEqual(('flash', 'off'), (config.review_model, config.review_reasoning))

    def test_quality_revision_policy_is_bounded_and_explicit(self):
        self.assertEqual(1, host_api.parse_config(self.flags()).quality_revisions)
        for value in (0, 1):
            config=host_api.parse_config(self.flags()+['--quality-revisions',str(value)])
            host_api.validate_config(config)
            self.assertEqual(value,config.quality_revisions)
        for value in (True,2,-1,None):
            with self.assertRaises(host_api.HostError):host_api.validate_config(replace(self.config,quality_revisions=value))

    def test_invalid_model_config_is_rejected_before_reading_credentials(self):
        invalid = (replace(self.config, generation_model='deepseek-v4-pro'), replace(self.config, generation_model='other'),
                   replace(self.config, generation_model=None), replace(self.config, generation_model=[]),
                   replace(self.config, generation_model=True),
                   replace(self.config, review_model='deepseek-v4-pro'), replace(self.config, review_model='other'),
                   replace(self.config, review_model=None), replace(self.config, review_model=[]),
                   replace(self.config, review_reasoning='ultra'), replace(self.config, review_reasoning=True),
                   replace(self.config, explanation_model='deepseek-v4-pro'), replace(self.config, explanation_model=None),
                   replace(self.config, explanation_model=[]), replace(self.config, explanation_reasoning='ultra'),
                   replace(self.config, explanation_reasoning=True))
        with patch.object(host_api, 'read_credential', side_effect=AssertionError('must not read credentials')) as reader:
            for config in invalid:
                with self.subTest(generation_model=config.generation_model,
                                  model=config.review_model, reasoning=config.review_reasoning):
                    with self.assertRaisesRegex(host_api.HostError, 'host_model_invalid'):
                        host_api.serve(config, install_signals=False)
            reader.assert_not_called()

    def test_invalid_cli_model_option_never_reads_or_echoes_credentials(self):
        for option in ('--generation-model', '--generation-reasoning', '--review-model', '--review-reasoning',
                       '--explanation-model', '--explanation-reasoning'):
            output, errors = io.StringIO(), io.StringIO()
            with patch.object(host_api, 'read_credential', side_effect=AssertionError('must not read credentials')) as reader, \
                 redirect_stdout(output), redirect_stderr(errors):
                code = host_api.main(self.flags() + [option, 'PRIVATE_INLINE_SECRET'])
            self.assertEqual(2, code)
            self.assertEqual('', output.getvalue())
            self.assertNotIn('PRIVATE_INLINE_SECRET', errors.getvalue())
            reader.assert_not_called()

    def test_config_rejects_urls_hostname_and_unbounded_budgets(self):
        for config in (replace(self.config, bind='https://example.invalid'), replace(self.config, bind='localhost'),
                       replace(self.config, port=0), replace(self.config, max_calls=17),
                       replace(self.config, timeout=True), replace(self.config, shutdown_timeout=0)):
            with self.assertRaises(host_api.HostError): host_api.validate_config(config)

    def test_stage_timeout_matches_runtime_boundary_before_any_startup_work(self):
        host_api.validate_config(replace(self.config, timeout=600))
        rejected = host_api.parse_config(self.flags() + ['--timeout', '601'])
        with patch.object(host_api, 'read_credential', side_effect=AssertionError('must reject before reading credentials')):
            with self.assertRaisesRegex(host_api.HostError, 'host_limit_invalid'):
                host_api.serve(rejected, install_signals=False)

    def test_cli_errors_never_echo_inline_secret_or_missing_credential_path(self):
        output, errors = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(errors):
            code = host_api.main(['--api-key', 'PRIVATE_INLINE_SECRET'])
        self.assertEqual(2, code)
        self.assertEqual('', output.getvalue())
        self.assertNotIn('PRIVATE_INLINE_SECRET', errors.getvalue())

    def test_credential_reader_is_bounded_and_rejects_multiple_lines(self):
        self.assertEqual(self.key, host_api.read_credential(self.key_path))
        for value in ('a\nb', 'x' * 4097, '中文', ''):
            self.key_path.write_text(value)
            with self.assertRaises(host_api.HostError): host_api.read_credential(self.key_path)

    def test_lock_is_not_inherited_and_cannot_follow_a_symlink(self):
        path = self.state / '.owner-test.lock'
        owner = host_api.OwnershipLock(path).acquire()
        self.assertFalse(os.get_inheritable(owner.fd))
        owner.release()
        if hasattr(os, 'O_NOFOLLOW'):
            link = self.state / '.owner-link.lock'; link.symlink_to(path)
            with self.assertRaises(host_api.HostError): host_api.OwnershipLock(link).acquire()

    def test_module_import_has_no_server_worker_or_runtime_side_effects(self):
        source = Path(host_api.__file__).read_text()
        name = 'serve_ielts_import_probe'
        module = types.ModuleType(name); module.__file__ = host_api.__file__
        sys.modules[name] = module
        try:
            with patch('ielts_http.create_server', side_effect=AssertionError('import started a server')), \
                 patch('ielts_pipeline.run_job', side_effect=AssertionError('import started a model')):
                exec(compile(source, host_api.__file__, 'exec'), module.__dict__)
        finally:
            del sys.modules[name]


if __name__ == '__main__':
    unittest.main()
