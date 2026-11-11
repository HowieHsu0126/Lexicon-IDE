"""Offline pipeline binding and failure tests.

Only the native-process boundary and model route are mocked. Contracts, corpus,
private stage preparation, durable SQLite transitions and output gate are real.
No server, actual Harness process, API request, Android tool or credential file
is used. Values labelled real below are deliberately adversarial metadata, never
observations of real provider activity.
"""
from __future__ import annotations

import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import harness_ielts as native
from test_harness_ielts import effective_profile_fixture
import ielts_pipeline as pipeline
from ielts_contract import canonical_sha256, validate_package
from ielts_corpus import build
from ielts_http import HttpContractError, verified_package
from ielts_jobs import ExerciseJobs, JobError
from test_ielts_review import review_fixture
from test_ielts_explanation_review import result_fixture as explanation_fixture, REVIEW_ID as EXPLANATION_ID


ACTIVE_ROWS = ('llm', 'session', 'agent', 'agent-default-model', 'session-persistence-jsonl',
               'session-projection', 'tools', 'system-prompt', 'agent-loop', 'llm-deepseek',
               'headless-startup', 'headless-runner', 'lexicon-ielts', 'lexicon-session-query')


def synthetic_tool_receipt(mode):
    required = native.required_stage_tools(mode)
    counts = {name: int(name in required) for name in native.HARNESS_TOOL_NAMES}
    return {'mode': mode, 'requiredTools': list(required), 'toolNames': list(native.HARNESS_TOOL_NAMES),
            'perTool': dict(counts), 'completedPerTool': dict(counts),
            'allowedCount': len(required), 'completedCount': len(required), 'failedCount': 0,
            'deniedCount': 0, 'registrationChecks': len(required) * 2, 'identityFailures': 0}


def make_native_receipt(receipt, result):
    raw = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    digest = canonical_sha256(result)
    receipt.pop('candidate_confirmation_verified', None)
    plugin = receipt['tool_receipt']
    count = plugin['allowedCount']
    receipt.update(delivery_kind=native.NATIVE_DELIVERY, native_tool_conclusion_verified=True,
        errors=0, malformed_frames=0, turn_count=1, terminal_count=1, final_count=1,
        terminal='completed', input_write_failed=False, stop_reason=None,
        tool_calls=count, candidate_bytes=len(raw))
    plugin.update(candidateDelivery=native.NATIVE_DELIVERY, candidateSha256=digest, candidateBytes=len(raw),
        nativeToolConclusion=dict(schema='lexicon.ielts.native-tool-conclusion.v1',
            sessionId=receipt['session_id'], turnId=1, stepId=1, callId='synthetic-final',
            callEventSeq=3, eventSeq=4, toolName='lexicon_ielts_validate', allowedOrdinal=count,
            candidateSha256=digest, candidateBytes=len(raw), toolsResultObserved=True, sessionEventCommitted=True))
    return receipt


def answers(request, review_id):
    _, package, review = review_fixture()
    for name in ('request_id', 'article_id', 'body_hash'):
        package[name] = request[name]
    package['request_sha256'] = canonical_sha256(request)
    package['style_source_ids'] = [source['source_id'] for source in request['style_sources']]
    review['request_sha256'] = canonical_sha256(request)
    review['package_sha256'] = canonical_sha256(package)
    review['review_id'] = review_id
    return package, review


def runtime_receipts(request, package, review, review_id, corpus_sha='c' * 64):
    receipts = []
    explanation = explanation_fixture(request, package, review)
    for index, (mode, result) in enumerate((('generation', package), ('review', review), ('explanation_review', explanation)), start=1):
        if mode == 'explanation_review':
            # Construct runtime metadata even for rejected blind fixtures; the gate must still reject them.
            try: prompt = pipeline.stage_prompt(request, delivery_kind=native.LEGACY_DELIVERY, package=package, review_id=EXPLANATION_ID, blind_review=review)
            except ValueError: prompt = 'rejected-blind-fixture'
        else:
            prompt = pipeline.stage_prompt(request, delivery_kind=native.LEGACY_DELIVERY, package=package if mode == 'review' else None,
                                           review_id=review_id if mode == 'review' else None)
        receipts.append({'schema': 'lexicon.ielts.runtime-receipt.v1', 'stage': mode, 'native_attempt_count': 1,
                         'required_tools': list(native.required_stage_tools(mode)), 'tool_receipt': synthetic_tool_receipt(mode),
                         'status': 'completed', 'exit_code': 0, 'process_reaped': True,
                         'prompt_sha256': hashlib.sha256(prompt.encode()).hexdigest(),
                         'response_sha256': canonical_sha256(result), 'session_id': 'session-' + mode,
                         'candidate_confirmation_verified': True, 'candidate_sha256': canonical_sha256(result),
                         'runtime_version': native.RUNTIME_VERSION, 'corpus_sha256': corpus_sha,
                         'model': 'deepseek-flash', 'reasoning_effort': 'off',
                         'model_request_ordinals': [index], **native.stage_execution_limits('off')})
    return receipts


class OutputBindingTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.review = review_fixture()
        self.review_id = self.review['review_id']
        self.receipts = runtime_receipts(self.request, self.package, self.review, self.review_id)
        self.records = [dict(ordinal=i + 1, phase='completed', http_status=200,
                             real_provider_request=False) for i in range(3)]

    def gate(self, receipts=None, records=None, review=None, **settings):
        return pipeline.verified_output(self.request, self.package, self.review if review is None else review,
                                        self.review_id, self.receipts if receipts is None else receipts,
                                        self.records if records is None else records,
                                        explanation_review=settings.pop('explanation_review', explanation_fixture(self.request, self.package, self.review if review is None else review)),
                                        explanation_review_id=EXPLANATION_ID, **settings)

    def test_matching_synthetic_metadata_is_labelled_fixture_not_real_provider(self):
        result = self.gate()
        self.assertEqual('synthetic_runtime_fixture', result['provenance']['kind'])
        self.assertEqual(0, result['provenance']['real_provider_request_count'])
        self.assertEqual(canonical_sha256(self.request), result['provenance']['request_sha256'])
        self.assertEqual(canonical_sha256(self.package), result['provenance']['package_sha256'])

    def test_three_exact_ordered_stages_are_required(self):
        for receipts in ([], self.receipts[:1], self.receipts[::-1], self.receipts + self.receipts[:1]):
            with self.subTest(count=len(receipts)), self.assertRaisesRegex(native.HarnessError, 'three_stages_required'):
                self.gate(receipts=receipts)

    def test_each_runtime_prompt_and_final_object_hash_is_recomputed(self):
        for stage in (0, 1, 2):
            for field in ('prompt_sha256', 'response_sha256'):
                receipts = copy.deepcopy(self.receipts); receipts[stage][field] = 'e' * 64
                with self.subTest(stage=stage, field=field), self.assertRaisesRegex(native.HarnessError, 'runtime_binding_mismatch'):
                    self.gate(receipts=receipts)

    def test_missing_changed_and_old_thinking_budget_cannot_publish(self):
        for stage in range(3):
            for field in native.stage_execution_limits('off'):
                for value in (None, True, -1, 999999):
                    receipts = copy.deepcopy(self.receipts); receipts[stage][field] = value
                    with self.subTest(stage=stage, field=field, value=value), self.assertRaisesRegex(native.HarnessError, 'runtime_budget_binding_mismatch'):
                        self.gate(receipts=receipts)
        receipts = copy.deepcopy(self.receipts); receipts[2]['reasoning_effort'] = 'high'
        with self.assertRaisesRegex(native.HarnessError, 'runtime_budget_binding_mismatch'):
            self.gate(receipts=receipts)

    def test_fixed_stage_tool_policy_is_bound_at_publication(self):
        for index in range(3):
            for value in (None, [], ['lexicon_ielts_read']):
                receipts = copy.deepcopy(self.receipts); receipts[index]['required_tools'] = value
                with self.subTest(index=index, value=value), self.assertRaisesRegex(native.HarnessError, 'runtime_binding_mismatch'):
                    self.gate(receipts=receipts)
        for index in (1, 2):
            receipts = copy.deepcopy(self.receipts)
            receipts[index]['tool_receipt']['completedPerTool']['lexicon_ielts_validate'] = 0
            receipts[index]['tool_receipt']['completedCount'] = 0
            with self.assertRaisesRegex(native.HarnessError, 'tool_policy_or_retrieval_incomplete'):
                self.gate(receipts=receipts)

    def test_review_prompts_use_complete_frozen_inputs_without_mandatory_repeat_retrieval(self):
        generation = pipeline.stage_prompt(self.request, delivery_kind=native.LEGACY_DELIVERY)
        blind = pipeline.stage_prompt(self.request, package=self.package, review_id=self.review_id)
        explanation = pipeline.stage_prompt(self.request, package=self.package, review_id=EXPLANATION_ID, blind_review=self.review)
        self.assertIn('Do ONE lexicon_ielts_retrieve search', generation)
        self.assertIn('After all three tools have succeeded', generation)
        self.assertIn('one or two source-grounded sentences about the correct answer', generation)
        self.assertIn('Do not enumerate or justify rejected options', generation)
        self.assertNotIn('An explanation must discuss each option', generation)
        self.assertIn('Independently assess all options and the uniqueness of the answer', blind)
        self.assertNotIn('Do not enumerate or justify rejected options', blind)
        for prompt in (blind, explanation):
            self.assertNotIn('Do ONE lexicon_ielts_retrieve search', prompt)
            self.assertNotIn('After all three tools have succeeded', prompt)
            self.assertIn('concludes the native turn', prompt)
            self.assertNotIn('finish with exactly', prompt)
            self.assertIn('lexicon_ielts_read', prompt)
            for paragraph in self.request['paragraphs']:
                self.assertIn(paragraph['text'], prompt)
        for source in self.request['style_sources']:
            self.assertIn(source['summary'], blind)
        self.assertNotIn('SECRET_RATIONALE_Q1', blind)
        self.assertIn('SECRET_RATIONALE_Q1', explanation)

    def test_writing_draft_guidance_is_generation_only_and_review_sees_final_prompt(self):
        generation = pipeline.stage_prompt(self.request, delivery_kind=native.LEGACY_DELIVERY)
        blind = pipeline.stage_prompt(self.request, package=self.package, review_id=self.review_id)
        self.assertIn('replace writing_task2.prompt with prompt_draft', generation)
        self.assertIn('one independently debatable', generation)
        self.assertIn('To what extent do you agree or disagree with this statement?', generation)
        self.assertIn('independent reviewer still assesses', generation)
        self.assertIn(self.package['writing_task2']['prompt'], blind)
        for field in ('prompt_draft', 'target_statement'):
            self.assertNotIn(field, blind)

    def test_failed_nonzero_or_unreaped_stage_cannot_pass(self):
        for field, value in (('status', 'failed'), ('exit_code', 1), ('process_reaped', False), ('session_id', None)):
            receipts = copy.deepcopy(self.receipts); receipts[1][field] = value
            with self.subTest(field=field), self.assertRaises(native.HarnessError): self.gate(receipts=receipts)

    def test_missing_or_replaced_candidate_confirmation_cannot_publish(self):
        for field, value in (('candidate_confirmation_verified', False),
                             ('candidate_confirmation_verified', 1), ('candidate_sha256', 'e' * 64)):
            receipts = copy.deepcopy(self.receipts); receipts[1][field] = value
            with self.subTest(field=field, value=value), self.assertRaisesRegex(native.HarnessError, 'runtime_binding_mismatch'):
                self.gate(receipts=receipts)
        receipts = copy.deepcopy(self.receipts); del receipts[1]['candidate_confirmation_verified']
        with self.assertRaisesRegex(native.HarnessError, 'runtime_binding_mismatch'): self.gate(receipts=receipts)

    def test_generation_and_review_sessions_must_be_independent(self):
        receipts = copy.deepcopy(self.receipts)
        receipts[1]['session_id'] = receipts[0]['session_id']
        with self.assertRaisesRegex(native.HarnessError, 'review_session_not_independent'):
            self.gate(receipts=receipts)

    def test_pro_pair_preserves_legacy_offline_verification_and_distinct_sessions(self):
        receipts = copy.deepcopy(self.receipts)
        for receipt in receipts: receipt['model'] = 'deepseek-v4-pro'
        self.assertEqual('synthetic_runtime_fixture', self.gate(receipts=receipts)['provenance']['kind'])
        self.assertEqual('synthetic_runtime_fixture',
                         self.gate(receipts=receipts, generation_model='deepseek-v4-pro')['provenance']['kind'])
        receipts[1]['session_id'] = receipts[0]['session_id']
        with self.assertRaisesRegex(native.HarnessError, 'review_session_not_independent'):
            self.gate(receipts=receipts, generation_model='deepseek-v4-pro')

    def test_output_gate_binds_explicit_host_generator_and_still_rejects_generation_thinking(self):
        with self.assertRaisesRegex(native.HarnessError, 'runtime_model_binding_mismatch'):
            self.gate(generation_model='deepseek-v4-pro')
        receipts = copy.deepcopy(self.receipts)
        receipts[0].update(model='deepseek-v4-pro', reasoning_effort='low')
        with self.assertRaisesRegex(native.HarnessError, 'generation_reasoning_fixed'):
            self.gate(receipts=receipts)

    def test_corpus_cannot_change_between_stages(self):
        receipts = copy.deepcopy(self.receipts); receipts[1]['corpus_sha256'] = 'e' * 64
        with self.assertRaisesRegex(native.HarnessError, 'corpus_changed_between_stages'):
            self.gate(receipts=receipts)

    def test_runtime_version_cannot_change_between_stages(self):
        receipts = copy.deepcopy(self.receipts); receipts[1]['runtime_version'] = 'different-runtime'
        with self.assertRaises(native.HarnessError): self.gate(receipts=receipts)

    def test_two_identical_but_unpinned_runtime_versions_are_also_rejected(self):
        receipts = copy.deepcopy(self.receipts)
        for receipt in receipts: receipt['runtime_version'] = 'different-runtime'
        with self.assertRaisesRegex(native.HarnessError, 'runtime_version_mismatch'): self.gate(receipts=receipts)

    def test_a_single_claimed_real_request_cannot_attest_two_model_stages(self):
        records = [dict(ordinal=1, phase='completed', http_status=200, real_provider_request=True)]
        try:
            result = self.gate(records=records)
        except native.HarnessError:
            return
        self.assertNotEqual('deepseek_harness', result['provenance']['kind'])

    def test_inflight_failed_and_mixed_records_never_become_real_execution(self):
        variants = [[], [dict(phase='inflight', http_status=None, real_provider_request=True)],
                    [dict(phase='completed', http_status=500, real_provider_request=True)],
                    [dict(ordinal=1, phase='completed', http_status=200, real_provider_request=True), self.records[1]]]
        for records in variants:
            with self.subTest(records=records):
                try: result = self.gate(records=records)
                except native.HarnessError: continue
                self.assertEqual('synthetic_runtime_fixture', result['provenance']['kind'])

    def test_every_request_ordinal_belongs_to_one_nonempty_ordered_stage(self):
        variants = [([1], []), ([1], [1]), ([2], [1]), ([1, 2], [3]), ([True], [2]), ([1, 1], [2])]
        for first, second in variants:
            receipts = copy.deepcopy(self.receipts)
            receipts[0]['model_request_ordinals'], receipts[1]['model_request_ordinals'] = first, second
            with self.subTest(first=first, second=second), self.assertRaisesRegex(native.HarnessError, 'model_request_binding'):
                self.gate(receipts=receipts)

    def test_duplicate_missing_or_unclaimed_record_ordinals_are_rejected(self):
        variants = [[self.records[0], self.records[0]],
                    [dict(self.records[0], ordinal=1), dict(self.records[1], ordinal=3)],
                    self.records + [dict(self.records[1], ordinal=3)],
                    [dict(self.records[0], ordinal=True), self.records[1]],
                    [dict(self.records[0], real_provider_request=1), self.records[1]]]
        for records in variants:
            with self.subTest(records=records), self.assertRaisesRegex(native.HarnessError, 'model_request_binding'):
                self.gate(records=records)

    def test_failed_or_inflight_bound_requests_block_a_completed_runtime_claim(self):
        for changed in (dict(self.records[0], phase='inflight'), dict(self.records[0], http_status=500), dict(self.records[0], http_status=True)):
            with self.subTest(changed=changed), self.assertRaisesRegex(native.HarnessError, 'model_request_incomplete'):
                self.gate(records=[changed, *self.records[1:]])

    def test_real_kind_requires_successful_real_metadata_in_both_bound_stages(self):
        # Metadata-contract test only: these are NOT actual provider observations.
        claimed = [dict(record, real_provider_request=True) for record in self.records]
        result = self.gate(records=claimed)
        self.assertEqual('deepseek_harness', result['provenance']['kind'])
        self.assertEqual(3, result['provenance']['real_provider_request_count'])
        for index in (0, 1, 2):
            mixed = copy.deepcopy(claimed); mixed[index]['real_provider_request'] = False
            self.assertEqual('synthetic_runtime_fixture', self.gate(records=mixed)['provenance']['kind'])

    def test_review_answer_mismatch_and_failed_checks_do_not_publish(self):
        wrong = copy.deepcopy(self.review); wrong['reading_reviews'][0]['observed_answer'] = 'B'
        failed = copy.deepcopy(self.review); failed['writing_task2_review']['checks']['prompt_clear']['status'] = 'fail'
        for report in (wrong, failed):
            receipts = runtime_receipts(self.request, self.package, report, self.review_id)
            with self.assertRaisesRegex(ValueError, 'review_rejected'):
                self.gate(receipts=receipts, review=report)

    def test_review_id_and_package_digest_cannot_be_substituted(self):
        for field in ('review_id', 'package_sha256'):
            report = copy.deepcopy(self.review); report[field] = 'other' if field == 'review_id' else 'e' * 64
            receipts = runtime_receipts(self.request, self.package, report, self.review_id)
            with self.subTest(field=field), self.assertRaises(ValueError): self.gate(receipts=receipts, review=report)


class PipelineLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = TemporaryDirectory(prefix='ielts-pipeline-corpus-')
        cls.corpus_path = Path(cls.shared.name) / 'corpus.json'
        cls.corpus_sha = build(cls.corpus_path)['corpus_sha256']

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temp = TemporaryDirectory(prefix='ielts-pipeline-offline-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jobs = ExerciseJobs(self.root / 'jobs.sqlite')
        self.key = 'sk-offline-pipeline-secret-not-a-credential'
        raw, _, _ = review_fixture()
        self.payload = {key: copy.deepcopy(raw[key]) for key in
                        ('request_id', 'article_id', 'paragraphs', 'reading_requests', 'writing_task2_request')}
        self.payload.update(schema='lexicon.ielts.submit.v1', body='\n\n'.join(p['text'] for p in raw['paragraphs']))
        self.request, self.body, _ = pipeline.normalize_submission(self.payload, self.corpus_path)
        self.job = self.jobs.submit(self.request, self.body, self.corpus_sha)
        self.modes, self.prepared, self.routes, self.prompts, self.subprocess_inputs = [], [], [], [], []
        self.result_change = self.receipt_change = None
        self.raise_stage = self.cancel_stage = None
        self.cancel_after_prepare = False

    def subprocess(self, command, **kwargs):
        # The native boundary is mocked. Actual prepare_stage still writes the
        # pinned modules, manifest, sandbox policy and credential-free profile.
        self.subprocess_inputs.append((list(map(str, command)), copy.deepcopy(kwargs)))
        if '--dump-config' in command:
            options = json.loads(self.subprocess_inputs[-2][1]['input'])
            return SimpleNamespace(returncode=0, stdout=effective_profile_fixture(options['model'], options['reasoningEffort']))
        return SimpleNamespace(returncode=0, stdout='[]\n')

    def route_type(self):
        owner = self
        class Route:
            def __init__(self, key, *, budget, transport=None, model='deepseek-flash', token_limit=8192, provider_deadline_seconds=90):
                self.budget, self.model, self.token_limit = budget, model, token_limit
                self.provider_deadline_seconds = provider_deadline_seconds
                self.token, self.base_url = 'synthetic-route-token', 'http://127.0.0.1:12345'
                self.server = SimpleNamespace(server_port=12345)
                self.closed = False
                owner.assertEqual(owner.key, key)
                owner.routes.append(self)
            def __enter__(self): return self
            def __exit__(self, *_): self.closed = True
        return Route

    def native_stage(self, prepared, prompt, *, mode, timeout, cancelled, model_budget=None, first_request_ordinal=None):
        self.modes.append(mode); self.prepared.append(prepared); self.prompts.append(prompt)
        manifest = json.loads((prepared['directory'] / 'inputs/manifest.json').read_text())
        request = json.loads(Path(manifest['files']['request']['path']).read_text())
        review_id = manifest.get('reviewId', 'review-placeholder')
        package, review = answers(request, review_id)
        if mode == 'review':
            package = json.loads(Path(manifest['files']['package']['path']).read_text())
            review['package_sha256'] = canonical_sha256(package)
        if mode == 'explanation_review':
            package = json.loads(Path(manifest['files']['package']['path']).read_text())
            blind = json.loads(Path(manifest['files']['blindReview']['path']).read_text())
            result = explanation_fixture(request, package, blind)
            result['review_id'] = review_id
        else:
            result = package if mode == 'generation' else review
        route = self.routes[-1]
        ordinal = route.budget.reserve(); route.budget.started(ordinal, False)
        if self.raise_stage == mode:
            raise RuntimeError('private exception with ' + self.key)
        if self.result_change:
            result = self.result_change(mode, copy.deepcopy(result))
        route.budget.record(ordinal, 200, .001, len(json.dumps(result)), False,
            dict(format='anthropic_sse', metadata_status='observed', stop_reason='tool_use',
                 input_tokens=120, output_tokens=80, message_stop_observed=True))
        receipt = dict(schema='lexicon.ielts.runtime-receipt.v1', stage=mode, status='completed', native_attempt_count=1,
                       required_tools=list(native.required_stage_tools(mode)), tool_receipt=synthetic_tool_receipt(mode),
                       exit_code=0, process_reaped=True, prompt_sha256=hashlib.sha256(prompt.encode()).hexdigest(),
                       response_sha256=canonical_sha256(result), session_id='session-' + mode,
                       candidate_confirmation_verified=True, candidate_sha256=canonical_sha256(result),
                       runtime_version=native.RUNTIME_VERSION, corpus_sha256=self.corpus_sha,
                       schema_module_sha256=prepared['schema_module_sha256'],
                       model=prepared['model'], reasoning_effort=prepared['reasoning_effort'],
                       **{key: prepared[key] for key in native.stage_execution_limits(prepared['reasoning_effort'])})
        if 'revision-01' in prepared['directory'].parts:
            receipt['session_id'] += '-revision'
        if 'quality_revision_sha256' in prepared:
            receipt['quality_revision_sha256'] = prepared['quality_revision_sha256']
        if prepared.get('delivery_kind') == native.NATIVE_DELIVERY:
            make_native_receipt(receipt, result)
        if self.cancel_stage == mode:
            self.jobs.cancel(self.job['id']); self.assertTrue(cancelled())
        if self.receipt_change: self.receipt_change(mode, receipt)
        return result, receipt

    def run_pipeline(self, **settings):
        with patch.object(pipeline, 'ModelRoute', self.route_type()), \
             patch.object(pipeline, 'run_native_stage', side_effect=self.native_stage), \
             patch.object(native.subprocess, 'run', side_effect=self.subprocess):
            return pipeline.run_job(self.jobs, self.job['id'], corpus_path=self.corpus_path, key=self.key,
                                    work_root=self.root / 'work', max_calls=settings.pop('max_calls', 12), **settings)

    def unavailable(self):
        with self.assertRaisesRegex(JobError, 'package_unavailable'): self.jobs.package(self.job['id'])

    def assert_work_has_no_key(self):
        work = self.root / 'work'
        for path in work.rglob('*'):
            if path.is_file(): self.assertNotIn(self.key.encode(), path.read_bytes(), str(path.relative_to(work)))
        self.assertNotIn(self.key, json.dumps(self.jobs.receipts(self.job['id'])))

    def test_three_stages_use_frozen_separate_inputs_and_synthetic_output_stays_fixture(self):
        result = self.run_pipeline()
        self.assertEqual('ready', result['state']); self.assertEqual(['generation', 'review', 'explanation_review'], self.modes)
        self.assertNotEqual(self.prepared[0]['cwd'], self.prepared[1]['cwd'])
        output = self.jobs.package(self.job['id'])
        self.assertEqual('synthetic_runtime_fixture', output['provenance']['kind'])
        self.assertEqual(0, output['provenance']['real_provider_request_count'])
        self.assertEqual(3, len(self.jobs.receipts(self.job['id'])))
        self.assertEqual([[1], [2], [3]], [receipt['model_request_ordinals'] for receipt in self.jobs.receipts(self.job['id'])])
        self.assertTrue(all(route.closed for route in self.routes))
        self.assertNotIn('SECRET_RATIONALE_Q1', self.prompts[1])
        capabilities = dict(runtime_version=native.RUNTIME_VERSION, corpus_sha256=self.corpus_sha)
        with self.assertRaises(HttpContractError): verified_package(output, result, capabilities)

    def test_default_model_configuration_uses_pro_high_only_for_explanations(self):
        self.assertEqual('ready', self.run_pipeline()['state'])
        self.assertEqual([('deepseek-flash', 'off'), ('deepseek-flash', 'off'), ('deepseek-v4-pro', 'high')],
                         [(p['model'], p['reasoning_effort']) for p in self.prepared])
        self.assertEqual([8192, 8192, 16384], [r.token_limit for r in self.routes])
        self.assertEqual([90, 90, 180], [r.provider_deadline_seconds for r in self.routes])
        self.assertEqual([100000, 100000, 190000], [p['stream_idle_timeout_ms'] for p in self.prepared])

    def test_stronger_review_configuration_changes_only_thinking_stage_limits(self):
        self.assertEqual('ready', self.run_pipeline(review_model='deepseek-v4-pro', review_reasoning='high')['state'])
        self.assertEqual([('deepseek-flash', 'off'), ('deepseek-v4-pro', 'high'), ('deepseek-v4-pro', 'high')],
                         [(p['model'], p['reasoning_effort']) for p in self.prepared])
        self.assertEqual([8192, 16384, 16384], [r.token_limit for r in self.routes])
        self.assertEqual([90, 180, 180], [r.provider_deadline_seconds for r in self.routes])
        options = [json.loads(kwargs['input']) for command, kwargs in self.subprocess_inputs if '--input-type=module' in command]
        self.assertEqual([('deepseek-flash', 'off'), ('deepseek-v4-pro', 'high'), ('deepseek-v4-pro', 'high')],
                         [(p['model'], p['reasoningEffort']) for p in options])
        self.assertEqual('synthetic_runtime_fixture', self.jobs.package(self.job['id'])['provenance']['kind'])

    def test_explicit_pro_generation_and_review_have_separate_sessions_and_unchanged_budgets(self):
        self.assertEqual('ready', self.run_pipeline(generation_model='deepseek-v4-pro',
                         review_model='deepseek-v4-pro', review_reasoning='off', explanation_reasoning='off')['state'])
        self.assertEqual([('deepseek-v4-pro', 'off')] * 3,
                         [(p['model'], p['reasoning_effort']) for p in self.prepared])
        options = [json.loads(kwargs['input']) for command, kwargs in self.subprocess_inputs if '--input-type=module' in command]
        self.assertEqual([('deepseek-v4-pro', 'off')] * 3, [(p['model'], p['reasoningEffort']) for p in options])
        self.assertEqual([8192, 8192, 8192], [r.token_limit for r in self.routes])
        self.assertIs(self.routes[0].budget, self.routes[1].budget)
        self.assertIs(self.routes[0].budget, self.routes[2].budget)
        self.assertEqual(12, self.routes[0].budget.max_calls)
        self.assertEqual(3, self.routes[0].budget.calls)
        receipts = self.jobs.receipts(self.job['id'])
        self.assertEqual(3, len({r['session_id'] for r in receipts}))
        for prepared in self.prepared:
            manifest = json.loads((prepared['directory'] / 'inputs/manifest.json').read_text())
            self.assertEqual(24, manifest['limits']['maxToolCalls'])
        self.assertNotIn('SECRET_RATIONALE_Q1', self.prompts[1])

    def test_generation_model_does_not_silently_change_the_default_reviewer(self):
        self.assertEqual('ready', self.run_pipeline(generation_model='deepseek-v4-pro')['state'])
        self.assertEqual([('deepseek-v4-pro', 'off'), ('deepseek-flash', 'off'), ('deepseek-v4-pro', 'high')],
                         [(p['model'], p['reasoning_effort']) for p in self.prepared])

    def test_explicit_explanation_route_does_not_change_generation_or_blind_review(self):
        self.assertEqual('ready', self.run_pipeline(explanation_model='deepseek-flash', explanation_reasoning='low')['state'])
        expected = [('deepseek-flash', 'off'), ('deepseek-flash', 'off'), ('deepseek-flash', 'low')]
        self.assertEqual(expected, [(p['model'], p['reasoning_effort']) for p in self.prepared])
        self.assertEqual(expected, [(r['model'], r['reasoning_effort']) for r in self.jobs.receipts(self.job['id'])])
        self.assertTrue(all(route.budget is self.routes[0].budget for route in self.routes))
        self.assertEqual(12, self.routes[0].budget.max_calls)
        self.assertEqual([8192, 8192, 16384], [route.token_limit for route in self.routes])

    def test_blind_review_effort_does_not_silently_downgrade_the_default_explanation_effort(self):
        self.assertEqual('ready', self.run_pipeline(review_model='deepseek-v4-pro', review_reasoning='low')['state'])
        self.assertEqual([('deepseek-flash', 'off'), ('deepseek-v4-pro', 'low'), ('deepseek-v4-pro', 'high')],
                         [(p['model'], p['reasoning_effort']) for p in self.prepared])

    def test_explanation_receipt_must_match_the_independently_selected_route(self):
        self.receipt_change = lambda mode, r: r.update(reasoning_effort='off') if mode == 'explanation_review' else None
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('runtime_model_binding_mismatch', result['error'])
        self.assertEqual(['generation', 'review', 'explanation_review'], self.modes)
        self.unavailable()

    def test_wrong_generator_receipt_stops_before_review_without_retry(self):
        self.receipt_change = lambda mode, r: r.update(model='deepseek-flash') if mode == 'generation' else None
        result = self.run_pipeline(generation_model='deepseek-v4-pro', review_model='deepseek-v4-pro')
        self.assertEqual('failed', result['state']); self.assertEqual('runtime_model_binding_mismatch', result['error'])
        self.assertEqual(['generation'], self.modes)
        self.assertEqual(1, self.routes[0].budget.calls)
        self.unavailable()

    def test_budget_receipt_mismatch_stops_without_starting_next_stage(self):
        self.receipt_change = lambda mode, r: r.update(provider_deadline_seconds=180) if mode == 'generation' else None
        result = self.run_pipeline()
        self.assertEqual(('failed', 'runtime_budget_binding_mismatch'), (result['state'], result['error']))
        self.assertEqual(['generation'], self.modes)
        self.assertEqual(1, self.routes[0].budget.calls)
        self.unavailable()

    def test_model_receipt_must_match_the_host_selected_stage(self):
        self.receipt_change = lambda mode, r: r.update(model='deepseek-flash') if mode == 'review' else None
        result = self.run_pipeline(review_model='deepseek-v4-pro', review_reasoning='high')
        self.assertEqual('failed', result['state']); self.assertEqual('runtime_model_binding_mismatch', result['error'])
        self.unavailable()

    def test_incomplete_provider_response_is_reported_as_unknown_and_never_starts_review(self):
        def unknown(_mode, receipt):
            self.routes[-1].budget.completion_unknown(1, 200, .2, False, 'response_deadline')
            receipt.update(status='failed', error='incomplete_runtime_result')
        self.receipt_change = unknown
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('provider_completion_unknown', result['error'])
        self.assertEqual(['generation'], self.modes); self.assertTrue(self.routes[0].budget.halted)
        usage = json.loads((self.root / 'work' / self.job['id'] / 'model-requests.json').read_text())
        self.assertEqual(1, usage['provider_completion_unconfirmed'])
        self.assertEqual('completion_unknown', usage['requests'][0]['phase'])
        self.assertEqual(200, usage['requests'][0]['http_status'])
        self.unavailable()

    def test_invalid_review_configuration_does_not_claim_or_start_a_route(self):
        for settings in (dict(review_model='deepseek-chat'), dict(review_reasoning='ultra'), dict(review_model=None)):
            with self.subTest(settings=settings), self.assertRaises(native.HarnessError):
                self.run_pipeline(**settings)
            self.assertEqual('queued', self.jobs.get(self.job['id'])['state'])
            self.assertFalse((self.root / 'work').exists()); self.assertEqual([], self.routes)

    def test_invalid_explanation_configuration_is_rejected_before_claim_or_process(self):
        for settings in (dict(explanation_model='deepseek-chat'), dict(explanation_reasoning='ultra'),
                         dict(explanation_model=None), dict(explanation_model=[]), dict(explanation_reasoning=True)):
            with self.subTest(settings=settings), self.assertRaises(native.HarnessError):
                self.run_pipeline(**settings)
            self.assertEqual('queued', self.jobs.get(self.job['id'])['state'])
            self.assertFalse((self.root / 'work').exists()); self.assertEqual([], self.routes)

    def test_invalid_generation_configuration_does_not_claim_or_start_a_route(self):
        for model in ('deepseek-chat', 'pro', None, [], True):
            with self.subTest(model=model), self.assertRaises(native.HarnessError):
                self.run_pipeline(generation_model=model)
            self.assertEqual('queued', self.jobs.get(self.job['id'])['state'])
            self.assertFalse((self.root / 'work').exists()); self.assertEqual([], self.routes)

    def test_pipeline_cli_maps_stage_aliases_and_preserves_default_budgets(self):
        key_file = self.root / 'synthetic-cli-key.txt'; key_file.write_text(self.key)
        for alias, model, explanation_alias, explanation_effort in (
                (None, 'deepseek-flash', None, None), ('pro', 'deepseek-v4-pro', 'flash', 'low')):
            out = self.root / ('cli-' + (alias or 'default'))
            argv = ['ielts_pipeline.py', '--input', str(self.root / 'not-read.json'), '--out', str(out),
                    '--corpus', str(self.corpus_path), '--key-file', str(key_file)]
            if alias: argv.extend(['--generation-model', alias])
            if explanation_alias: argv.extend(['--explanation-model', explanation_alias, '--explanation-reasoning', explanation_effort])
            jobs = Mock(); jobs.submit.return_value = {'id': 'synthetic-cli-job'}
            with patch('sys.argv', argv), patch.object(pipeline, 'load_json', return_value=self.payload), \
                 patch.object(pipeline, 'normalize_submission', return_value=(self.request, self.body, self.corpus_sha)), \
                 patch.object(pipeline, 'ExerciseJobs', return_value=jobs), \
                 patch.object(pipeline, 'run_job', return_value={'state': 'failed'}) as run, redirect_stdout(io.StringIO()):
                self.assertEqual(1, pipeline.main())
            self.assertEqual(model, run.call_args.kwargs['generation_model'])
            self.assertEqual('deepseek-flash', run.call_args.kwargs['review_model'])
            self.assertEqual('off', run.call_args.kwargs['review_reasoning'])
            self.assertEqual(native.REVIEW_MODELS[explanation_alias or 'pro'], run.call_args.kwargs['explanation_model'])
            self.assertEqual(explanation_effort or 'high', run.call_args.kwargs['explanation_reasoning'])
            self.assertEqual(12, run.call_args.kwargs['max_calls'])
            self.assertEqual(240, run.call_args.kwargs['timeout'])

    def test_pipeline_cli_rejects_unknown_generation_model_or_effort_before_loading_files(self):
        for extra in (['--generation-model', 'deepseek-v4-pro'], ['--generation-model', 'other'],
                      ['--generation-reasoning', 'high'], ['--explanation-model', 'other'],
                      ['--explanation-reasoning', 'ultra']):
            argv = ['ielts_pipeline.py', '--input', 'unread.json', '--out', str(self.root / 'uncreated'),
                    '--corpus', 'unread-corpus.json', '--key-file', 'unread-key.txt', *extra]
            with patch('sys.argv', argv), patch.object(pipeline, 'load_json') as load, \
                 patch.object(pipeline, 'run_job') as run, redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error: pipeline.main()
                self.assertEqual(2, error.exception.code)
            load.assert_not_called(); run.assert_not_called()
            self.assertFalse((self.root / 'uncreated').exists())

    def test_real_secret_never_enters_prepared_env_profile_or_child_arguments(self):
        self.run_pipeline()
        for prepared in self.prepared:
            self.assertEqual('synthetic-route-token', prepared['env']['LEXICON_DUMMY_KEY'])
            self.assertNotIn('DEEPSEEK_API_KEY', prepared['env'])
            self.assertNotIn(self.key, json.dumps(prepared['env']))
            manifest = json.loads((prepared['directory'] / 'inputs/manifest.json').read_text())
            self.assertIn('schemaModule', manifest['files'])
            self.assertEqual(prepared['schema_module_sha256'], native.sha256_file(manifest['files']['schemaModule']['path']))
            self.assertIn('candidateModule', manifest['files'])
            self.assertEqual(prepared['candidate_module_sha256'], native.sha256_file(manifest['files']['candidateModule']['path']))
        self.assertNotIn(self.key, repr(self.subprocess_inputs))
        self.assert_work_has_no_key()

    def test_generation_output_credential_is_rejected_before_candidate_file_or_review(self):
        def mutate(mode, result):
            if mode == 'generation': result['reading_questions'][0]['explanation'] = self.key
            return result
        self.result_change = mutate
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('credential_in_output', result['error'])
        self.assertEqual(['generation'], self.modes); self.unavailable()
        self.assertFalse((self.root / 'work' / self.job['id'] / 'generation-candidate.json').exists())
        self.assert_work_has_no_key()

    def test_review_output_credential_is_rejected_before_review_receipt_or_publication(self):
        def mutate(mode, result):
            if mode == 'review': result['writing_task2_review']['conclusion'] = self.key
            return result
        self.result_change = mutate
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('credential_in_output', result['error'])
        self.unavailable(); self.assert_work_has_no_key()
        self.assertFalse((self.root / 'work' / self.job['id'] / 'review-receipt.json').exists())

    def test_wrong_generation_final_object_does_not_start_review(self):
        self.result_change = lambda mode, value: {'unexpected': 'synthetic'} if mode == 'generation' else value
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual(['generation'], self.modes); self.unavailable()

    def test_receipt_final_hash_mismatch_blocks_publication_even_with_valid_json(self):
        self.receipt_change = lambda mode, receipt: receipt.update(response_sha256='e' * 64) if mode == 'review' else None
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('runtime_binding_mismatch', result['error']); self.unavailable()

    def test_parent_budget_overwrites_untrusted_stage_ordinal_claims(self):
        self.receipt_change = lambda mode, receipt: receipt.update(model_request_ordinals=[999])
        result = self.run_pipeline()
        self.assertEqual('ready', result['state'])
        self.assertEqual([[1], [2], [3]], [receipt['model_request_ordinals'] for receipt in self.jobs.receipts(self.job['id'])])

    def test_independent_review_answer_mismatch_keeps_private_audit_but_no_package(self):
        def mutate(mode, result):
            if mode == 'review': result['reading_reviews'][0]['observed_answer'] = 'B'
            return result
        self.result_change = mutate
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('review_rejected', result['error']); self.unavailable()
        audit = json.loads((self.root / 'work' / self.job['id'] / 'review-receipt.json').read_text())
        self.assertEqual('rejected', audit['status'])
        self.assertTrue(any(item['code'] == 'answer_disagreement' for item in audit['blockers']))

    def test_generation_cancellation_discards_a_late_complete_result_and_never_reviews(self):
        self.cancel_stage = 'generation'
        result = self.run_pipeline()
        self.assertEqual('cancelled', result['state']); self.assertEqual(1, result['process_reaped'])
        self.assertEqual(['generation'], self.modes); self.unavailable()

    def test_review_cancellation_discards_even_an_otherwise_publishable_output(self):
        self.cancel_stage = 'review'
        result = self.run_pipeline()
        self.assertEqual('cancelled', result['state']); self.assertEqual(1, result['process_reaped']); self.unavailable()

    def test_native_exception_preserves_unconfirmed_process_state_and_no_retry(self):
        self.raise_stage = 'generation'
        result = self.run_pipeline()
        self.assertEqual('interrupted', result['state']); self.assertEqual(0, result['process_reaped'])
        self.assertEqual('process_completion_unconfirmed', result['error']); self.assertEqual(['generation'], self.modes)
        self.unavailable(); self.assert_work_has_no_key()
        with self.assertRaisesRegex(JobError, 'job_not_queued'): self.run_pipeline()

    def test_unreaped_native_receipt_is_interrupted_without_claiming_shutdown(self):
        self.receipt_change = lambda mode, receipt: receipt.update(process_reaped=False)
        result = self.run_pipeline()
        self.assertEqual('interrupted', result['state']); self.assertEqual(0, result['process_reaped']); self.unavailable()
        self.assertEqual(['generation'], self.modes)

    def test_integer_reaping_claim_is_not_accepted_as_a_boolean_owner_receipt(self):
        self.receipt_change = lambda mode, receipt: receipt.update(process_reaped=1)
        result = self.run_pipeline()
        self.assertEqual('interrupted', result['state']); self.assertEqual(0, result['process_reaped']); self.unavailable()
        self.assertEqual(['generation'], self.modes)

    def test_failed_stage_receipt_commit_preserves_unconfirmed_process_ownership(self):
        with patch.object(self.jobs, 'record_stage', side_effect=OSError('synthetic receipt write failure')):
            result = self.run_pipeline()
        self.assertEqual('interrupted', result['state']); self.assertEqual(0, result['process_reaped']); self.unavailable()

    def test_failed_blind_review_never_exposes_explanations_to_a_third_session(self):
        def change(mode, value):
            if mode == 'review': value['reading_reviews'][0]['observed_answer'] = 'B'
            return value
        self.result_change = change
        result = self.run_pipeline()
        self.assertEqual('failed', result['state'])
        self.assertEqual(['generation', 'review'], self.modes)
        self.assertFalse((self.root / 'work' / self.job['id'] / 'explanation_review').exists())
        self.unavailable()

    def test_writing_instruction_ambiguity_blocks_even_when_generic_checks_pass(self):
        def change(mode, value):
            if mode == 'review':
                alignment = value['writing_task2_review']['instruction_alignment']
                second = copy.deepcopy(alignment['target_readings'][0])
                second['interpretation'] = 'Whether other public spending should cease altogether.'
                alignment.update(relation='ambiguous', target_readings=[alignment['target_readings'][0], second],
                                 reason='Synthetic ambiguity fixture; no actual semantic assessment.')
            return value
        self.result_change = change
        result = self.run_pipeline()
        self.assertEqual('failed', result['state'])
        self.assertEqual(['generation', 'review'], self.modes)
        self.assertFalse((self.root / 'work' / self.job['id'] / 'explanation_review').exists())
        self.unavailable()
        audit = json.loads((self.root / 'work' / self.job['id'] / 'review-receipt.json').read_text())
        self.assertIn('writing_instruction_ambiguous', [item['code'] for item in audit['blockers']])

    def test_agreed_answers_with_rejected_explanation_never_publish(self):
        def change(mode, value):
            if mode == 'explanation_review':
                value['explanation_reviews'][0]['unit_reviews'][0].update(
                    relation='unsupported', reason='Synthetic finding: the explanation strengthens a partial negative to none.')
            return value
        self.result_change = change
        result = self.run_pipeline()
        self.assertEqual('failed', result['state'])
        self.assertEqual('explanation_review_rejected', result['error'])
        self.assertEqual(['generation', 'review', 'explanation_review'], self.modes)
        self.assertEqual(1, result['process_reaped']); self.unavailable()
        audit = json.loads((self.root / 'work' / self.job['id'] / 'explanation-review-receipt.json').read_text())
        self.assertEqual('rejected', audit['status']); self.assertTrue(audit['blocked_items'])

    def test_ambiguous_explanation_unit_is_preserved_and_blocks_publication(self):
        def change(mode, value):
            if mode == 'explanation_review':
                value['explanation_reviews'][0]['unit_reviews'][0].update(
                    scope='ambiguous', relation='ambiguous',
                    interpretations=['The passage does not report a measurement.', 'No measurement took place.'],
                    reason='Synthetic ambiguity fixture; neither reading is selected by the host.')
            return value
        self.result_change = change
        result = self.run_pipeline()
        self.assertEqual(('failed', 'explanation_review_rejected'), (result['state'], result['error']))
        self.assertEqual(['generation', 'review', 'explanation_review'], self.modes)
        self.unavailable()
        audit = json.loads((self.root / 'work' / self.job['id'] / 'explanation-review-receipt.json').read_text())
        self.assertEqual('insufficient', audit['explanation_results'][0]['status'])
        self.assertEqual('explanation_insufficient', audit['blocked_items'][0]['code'])
        self.assertEqual(2, len(audit['explanation_results'][0]['unit_results'][0]['interpretations']))

    def test_explanation_stage_failure_does_not_repeat_generation(self):
        self.raise_stage = 'explanation_review'
        result = self.run_pipeline()
        self.assertEqual('interrupted', result['state']); self.assertEqual(0, result['process_reaped'])
        self.assertEqual(['generation', 'review', 'explanation_review'], self.modes)
        self.unavailable()
        with self.assertRaisesRegex(JobError, 'job_not_queued'): self.run_pipeline()

    def test_explanation_stage_cancellation_keeps_package_unavailable(self):
        self.cancel_stage = 'explanation_review'
        result = self.run_pipeline()
        self.assertEqual('cancelled', result['state']); self.assertEqual(1, result['process_reaped'])
        self.unavailable()

    def test_completed_blind_runtime_with_unknown_provider_response_never_starts_third_stage(self):
        def unknown(mode, receipt):
            if mode == 'review':
                self.routes[-1].budget.completion_unknown(2, 200, .2, False, 'response_deadline')
        self.receipt_change = unknown
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual(1, result['process_reaped'])
        self.assertEqual('provider_completion_unknown', result['error'])
        self.assertEqual(['generation', 'review'], self.modes)
        self.assertFalse((self.root / 'work' / self.job['id'] / 'explanation_review').exists())
        self.unavailable()

    def test_invalid_prior_runtime_receipt_stops_before_explanation_stage(self):
        self.receipt_change = lambda mode, receipt: receipt.update(response_sha256='e' * 64) if mode == 'review' else None
        result = self.run_pipeline()
        self.assertEqual('runtime_binding_mismatch', result['error'])
        self.assertEqual(['generation', 'review'], self.modes); self.unavailable()

    def test_explanation_output_credential_is_not_published_or_written(self):
        def change(mode, value):
            if mode == 'explanation_review': value['explanation_reviews'][0]['unit_reviews'][0]['reason'] = self.key
            return value
        self.result_change = change
        result = self.run_pipeline()
        self.assertEqual('credential_in_output', result['error']); self.unavailable()
        self.assert_work_has_no_key()

    def test_invalid_budget_does_not_claim_a_queued_job_or_create_work(self):
        for maximum in (0, 17, True):
            with self.subTest(maximum=maximum), self.assertRaises(ValueError):
                pipeline.run_job(self.jobs, self.job['id'], corpus_path=self.corpus_path, key=self.key,
                                 work_root=self.root / 'work', max_calls=maximum)
            self.assertEqual('queued', self.jobs.get(self.job['id'])['state'])
            self.assertFalse((self.root / 'work').exists())

    def test_preexisting_work_directory_is_preserved_and_job_does_not_stay_generating(self):
        work = self.root / 'work' / self.job['id']; work.mkdir(parents=True)
        evidence = work / 'model-requests.json'; evidence.write_bytes(b'previous immutable evidence')
        marker = work / 'marker.txt'; marker.write_bytes(b'keep this user file')
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('work_directory_exists', result['error'])
        self.assertEqual(b'previous immutable evidence', evidence.read_bytes())
        self.assertEqual(b'keep this user file', marker.read_bytes())
        self.assertEqual({'model-requests.json', 'marker.txt'}, {p.name for p in work.iterdir()})
        self.assertEqual([], self.routes); self.assertEqual([], self.modes); self.unavailable()

    def test_cancel_requested_before_first_stage_never_starts_a_route(self):
        original = self.jobs.cancellation_requested
        def cancelled(job_id, lease):
            self.jobs.cancel(job_id)
            return original(job_id, lease)
        with patch.object(self.jobs, 'cancellation_requested', side_effect=cancelled):
            result = self.run_pipeline()
        self.assertEqual('cancelled', result['state']); self.assertEqual([], self.routes); self.assertEqual([], self.modes); self.unavailable()

    def test_failed_generation_receipt_cannot_progress_or_publish(self):
        self.receipt_change = lambda mode, receipt: receipt.update(status='failed', error='model_json_invalid') if mode == 'generation' else None
        result = self.run_pipeline()
        self.assertEqual('failed', result['state']); self.assertEqual('model_json_invalid', result['error'])
        self.assertEqual(['generation'], self.modes); self.unavailable()

    def test_changed_corpus_after_submission_is_rejected_before_route_or_native(self):
        changed = self.root / 'changed-corpus.json'; changed.write_text('{}')
        with patch.object(pipeline, 'ModelRoute', self.route_type()), patch.object(pipeline, 'run_native_stage', side_effect=self.native_stage):
            result = pipeline.run_job(self.jobs, self.job['id'], corpus_path=changed, key=self.key, work_root=self.root / 'work')
        self.assertEqual('failed', result['state']); self.assertEqual('corpus_changed_after_submission', result['error'])
        self.assertEqual([], self.modes); self.assertEqual([], self.routes); self.unavailable()


if __name__ == '__main__':
    unittest.main()
