"""Offline candidate-artifact and terminal binding tests; no native or API call."""
import copy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import harness_ielts as native
from ielts_contract import canonical_sha256
from ielts_candidate import compile_candidate
from test_ielts_candidate import explanation_fixture, locator_fixture
from test_ielts_review import review_fixture


TOOLS = ('lexicon_ielts_retrieve', 'lexicon_ielts_read', 'lexicon_ielts_validate')


def native_conclusion_fixture(plugin, session_id='session-synthetic-artifact'):
    """Synthetic event binding only; actual Session authority is tested in Node."""
    return dict(schema='lexicon.ielts.native-tool-conclusion.v1', sessionId=session_id,
        turnId=1, stepId=3, callId='synthetic-validation', callEventSeq=9, eventSeq=10,
        toolName='lexicon_ielts_validate', allowedOrdinal=plugin['allowedCount'],
        candidateSha256=plugin['candidateSha256'], candidateBytes=plugin['candidateBytes'],
        toolsResultObserved=True, sessionEventCommitted=True)


def effective_profile_fixture(model='deepseek-v4-pro', reasoning_effort='off'):
    limits = native.stage_execution_limits(reasoning_effort)
    active = ('llm', 'session', 'agent', 'agent-default-model', 'session-persistence-jsonl',
              'session-projection', 'tools', 'system-prompt', 'agent-loop', 'llm-deepseek',
              'headless-startup', 'headless-runner', 'lexicon-ielts', 'lexicon-session-query')
    rows = []
    for name in active:
        rows.append('- id: ' + name + '\n')
        if name == 'agent-default-model':
            rows.append('  config:\n    provider: deepseek-official\n    model: ' + model +
                        "\n    reasoningEffort: '" + reasoning_effort + "'\n")
        if name == 'llm-deepseek':
            rows.append("  config:\n    baseURL: http://127.0.0.1:18000/v1\n    apiKeyEnv: LEXICON_DUMMY_KEY\n    reasoningEffort: '" + reasoning_effort + "'\n" +
                        '    maxTokens: ' + str(limits['token_limit']) + '\n' +
                        '    streamIdleTimeoutMs: ' + str(limits['stream_idle_timeout_ms']) + '\n' +
                        '    retryPolicy:\n      mode: normal\n      maxRetries: 0\n' +
                        '    models:\n      - id: ' + model + '\n        inputModalities:\n          - text\n        contextWindow: 1048576\n        maxTokens: ' + str(limits['token_limit']) + '\n')
    return ''.join(rows).encode()


def historical_prefix(digest):
    return ('The candidate compiled successfully (structurally valid). The reference readings confirm all '
            'question types and formats are correctly aligned. The candidate was saved with digest `'
            + digest + '`.\n\n')


class CandidateArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix='ielts-artifact-binding-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.counter = 0
        self.request, self.package, self.review = review_fixture()
        self.explanation_review = explanation_fixture()[3]

    def invoke(self, *, mode='generation', confirmation=None, artifact=None,
               plugin_change=None, prepare_change=None, event_change=None, execution_change=None,
               delivery_kind=native.LEGACY_DELIVERY):
        self.counter += 1
        state = self.root / str(self.counter); state.mkdir()
        candidate = {'generation': self.package, 'review': self.review,
                     'explanation_review': self.explanation_review}[mode]
        raw = json.dumps(candidate, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
        digest = hashlib.sha256(raw).hexdigest()
        (state / 'validated-candidate.json').write_bytes(raw if artifact is None else artifact)
        prepared = dict(state=state, manifest_sha256='a' * 64, profile_sha256='b' * 64,
                        plugin_sha256='c' * 64, corpus_sha256='d' * 64, schema_module_sha256='e' * 64,
                        candidate_module_sha256='7' * 64,
                        command=['not-executed'], cwd=state, env={}, model='deepseek-flash', reasoning_effort='off',
                        **native.stage_execution_limits('off'))
        plugin = dict(manifestSha256=prepared['manifest_sha256'], mode=mode, pluginReady=True,
                      globalGuard=True, registrationIdentityVerified=True, candidateSchemaStrict=True,
                      schemaModuleSha256=prepared['schema_module_sha256'], deniedCount=0, identityFailures=0,
                      candidateCompilerReady=True, candidateModuleSha256=prepared['candidate_module_sha256'],
                      perTool=dict.fromkeys(TOOLS, 1), completedPerTool=dict.fromkeys(TOOLS, 1),
                      requiredTools=list(native.required_stage_tools(mode)),
                      toolNames=list(TOOLS),
                      allowedCount=3, completedCount=3, failedCount=0, registrationChecks=6,
                      candidateSha256=digest, candidateBytes=len(raw))
        if mode == 'explanation_review':
            prepared.update(explanation_review_module_sha256='8' * 64, blind_review_input_sha256='9' * 64)
            plugin.update(explanationReviewModuleSha256='8' * 64, blindReviewInputSha256='9' * 64)
        if delivery_kind == native.NATIVE_DELIVERY:
            prepared['delivery_kind'] = delivery_kind
            plugin.update(candidateDelivery=delivery_kind, nativeToolConclusion=native_conclusion_fixture(plugin))
        if plugin_change: plugin_change(plugin)
        (state / 'plugin-receipt.json').write_text(json.dumps(plugin))
        if prepare_change: prepare_change(prepared)
        final = {'candidate_sha256': digest} if confirmation is None else confirmation
        events = [dict(type='session', sessionId='session-synthetic-artifact'),
                  dict(type='status', phase='turn_start'),
                  dict(type='status', phase='turn_end', reason={'kind': 'completed'}),
                  dict(type='final', text=json.dumps(final, ensure_ascii=False))]
        if delivery_kind == native.NATIVE_DELIVERY:
            events[2:2] = [dict(type='tool_call', tool=name) for name in TOOLS]
            events[-1]['text'] = ''
        if event_change: event_change(events)
        output = ('\n'.join(json.dumps(event, ensure_ascii=False) for event in events) + '\n').encode()
        execution = dict(exit_code=0, process_reaped=True, stop_reason=None,
                         input_write_failed=False, elapsed_ms=1, stderr_bytes_discarded=0)
        if execution_change: execution_change(execution)
        with patch.object(native, 'verify_native_version', return_value=native.RUNTIME_VERSION), \
             patch.object(native, 'run_owned_process', return_value=(output, execution)):
            result, receipt = native.run_native_stage(prepared, 'offline synthetic prompt', mode=mode)
        self.assertEqual(receipt, json.loads((state / 'runtime-receipt.json').read_text()))
        return result, receipt

    def rejected(self, **changes):
        result, receipt = self.invoke(**changes)
        self.assertIsNone(result)
        self.assertEqual('failed', receipt['status'])
        self.assertNotEqual(True, receipt.get('candidate_confirmation_verified'))
        self.assertIn('error', receipt)
        return receipt

    def test_both_stages_return_tool_owned_full_candidate_from_short_confirmation(self):
        for mode, expected in (('generation', self.package), ('review', self.review)):
            with self.subTest(mode=mode):
                result, receipt = self.invoke(mode=mode)
                self.assertEqual(expected, result)
                self.assertEqual('completed', receipt['status'])
                self.assertIs(receipt['candidate_confirmation_verified'], True)
                self.assertEqual(canonical_sha256(expected), receipt['response_sha256'])
                self.assertEqual(canonical_sha256(expected), receipt['candidate_sha256'])
                self.assertEqual(88, receipt['final_response_bytes'])
                self.assertLess(receipt['final_response_bytes'], receipt['candidate_bytes'])

    def test_actual_trial12_prefix_shape_accepts_only_the_bound_trailing_confirmation(self):
        for mode, expected in (('generation', self.package), ('review', self.review)):
            digest = canonical_sha256(expected)
            def prefix(events):
                events[-1]['text'] = historical_prefix(digest) + json.dumps({'candidate_sha256': digest}, separators=(',', ':'))
            result, receipt = self.invoke(mode=mode, event_change=prefix)
            self.assertEqual(expected, result)
            self.assertEqual('completed', receipt['status'])
            self.assertEqual(335, receipt['final_response_bytes'])
            self.assertEqual(len(historical_prefix(digest).encode()), receipt['candidate_confirmation_prefix_bytes'])
            self.assertEqual(digest, receipt['candidate_sha256'])

    def test_prefix_cannot_replace_an_incorrect_final_digest_with_a_matching_earlier_hash(self):
        digest = canonical_sha256(self.package)
        def prefix(events):
            events[-1]['text'] = historical_prefix(digest) + json.dumps({'candidate_sha256': 'f' * 64})
        self.assertEqual('candidate_receipt_mismatch', self.rejected(event_change=prefix)['error'])

    def test_prefix_does_not_bypass_last_validation_or_required_tools(self):
        def prefix(events): events[-1]['text'] = '已校验。\n' + events[-1]['text']
        self.rejected(event_change=prefix, plugin_change=lambda p: p.update(candidateSha256=None))
        self.rejected(event_change=prefix, plugin_change=lambda p: p['completedPerTool'].update(lexicon_ielts_read=0))

    def test_a_large_final_candidate_is_not_accepted_as_the_delivery_channel(self):
        receipt = self.rejected(confirmation=self.package)
        self.assertIn(receipt['error'], ('candidate_confirmation_required', 'candidate_confirmation_bound'))

    def test_missing_extra_nonstring_uppercase_and_wrong_digest_are_rejected(self):
        for final in ({}, {'candidate_sha256': 123}, {'candidate_sha256': 'A' * 64},
                      {'candidate_sha256': 'f' * 64}, {'candidate_sha256': canonical_sha256(self.package), 'candidate': {}}):
            with self.subTest(final=final): self.rejected(confirmation=final)

    def test_confirmation_does_not_accept_an_unvalidated_or_old_receipt(self):
        for value in (None, 'f' * 64, 1):
            with self.subTest(value=value):
                receipt = self.rejected(plugin_change=lambda p: p.update(candidateSha256=value))
                self.assertEqual('candidate_receipt_mismatch', receipt['error'])

    def test_raw_locator_digest_cannot_confirm_the_compiled_artifact(self):
        request, raw_candidate, _ = locator_fixture()
        self.package = compile_candidate(request, raw_candidate, 'generation')
        self.assertNotEqual(canonical_sha256(raw_candidate), canonical_sha256(self.package))
        receipt = self.rejected(confirmation={'candidate_sha256': canonical_sha256(raw_candidate)})
        self.assertEqual('candidate_receipt_mismatch', receipt['error'])

    def test_artifact_size_and_content_are_bound_to_receipt(self):
        for raw in (b'{}', b'', b' ' * 200_001):
            with self.subTest(size=len(raw)): self.rejected(artifact=raw)
        for size in (None, True, -1, 10, '10'):
            with self.subTest(size=size): self.rejected(plugin_change=lambda p: p.update(candidateBytes=size))

    def test_matching_noncanonical_bytes_are_not_accepted(self):
        raw = json.dumps(self.package, ensure_ascii=False, indent=2).encode()
        digest = hashlib.sha256(raw).hexdigest()
        receipt = self.rejected(artifact=raw, confirmation={'candidate_sha256': digest},
                               plugin_change=lambda p: p.update(candidateSha256=digest, candidateBytes=len(raw)))
        self.assertEqual('candidate_not_canonical', receipt['error'])

    def test_a_symlink_does_not_substitute_a_tool_owned_artifact(self):
        def replace(prepared):
            target = prepared['state'] / 'validated-candidate.json'
            other = prepared['state'] / 'other.json'; target.rename(other); target.symlink_to(other)
        self.rejected(prepare_change=replace)

    def test_missing_file_fails_with_a_reaped_runtime_receipt(self):
        receipt = self.rejected(prepare_change=lambda p: (p['state'] / 'validated-candidate.json').unlink())
        self.assertIs(receipt['process_reaped'], True)

    def test_successful_retrieve_read_and_validate_are_all_mandatory(self):
        for name in TOOLS:
            for count in (0, True, None):
                def change(p): p['completedPerTool'][name] = count
                with self.subTest(tool=name, count=count):
                    receipt = self.rejected(plugin_change=change)
                    self.assertEqual('tool_policy_or_retrieval_incomplete', receipt['error'])
        for counts in (None, [], {}, 1):
            with self.subTest(counts=counts): self.rejected(plugin_change=lambda p: p.update(completedPerTool=counts))

    def test_review_stages_accept_real_zero_optional_calls_but_still_require_validation(self):
        def only_validation(plugin):
            counts = {name: int(name == 'lexicon_ielts_validate') for name in TOOLS}
            plugin.update(perTool=dict(counts), completedPerTool=dict(counts), allowedCount=1, completedCount=1)
        for mode in ('review', 'explanation_review'):
            result, receipt = self.invoke(mode=mode, plugin_change=only_validation)
            self.assertIsNotNone(result)
            self.assertEqual('completed', receipt['status'])
            self.assertEqual(['lexicon_ielts_validate'], receipt['required_tools'])
            self.assertEqual(0, receipt['tool_receipt']['completedPerTool']['lexicon_ielts_read'])
            self.assertEqual(0, receipt['tool_receipt']['completedPerTool']['lexicon_ielts_retrieve'])
            def missing_validation(plugin):
                only_validation(plugin)
                plugin['completedPerTool']['lexicon_ielts_validate'] = 0
                plugin['completedCount'] = 0
            self.rejected(mode=mode, plugin_change=missing_validation)
        self.rejected(plugin_change=only_validation)

    def test_optional_call_count_is_checked_even_when_not_required(self):
        for mode in ('review', 'explanation_review'):
            for table in ('perTool', 'completedPerTool'):
                for name in TOOLS:
                    for value in (-1, True, None, '0'):
                        with self.subTest(mode=mode, table=table, name=name, value=value):
                            self.rejected(mode=mode, plugin_change=lambda p: p[table].update({name: value}))
                    self.rejected(mode=mode, plugin_change=lambda p: p[table].pop(name))
            self.rejected(mode=mode, plugin_change=lambda p: p['completedPerTool'].update(other=0))

    def test_host_tool_policy_and_counter_totals_cannot_be_replaced(self):
        for mode in ('generation', 'review', 'explanation_review'):
            for field in ('allowedCount', 'deniedCount', 'completedCount', 'failedCount', 'registrationChecks', 'identityFailures'):
                for value in (-1, True, None):
                    with self.subTest(mode=mode, field=field, value=value):
                        self.rejected(mode=mode, plugin_change=lambda p: p.update({field: value}))
                self.rejected(mode=mode, plugin_change=lambda p: p.pop(field))
            self.rejected(mode=mode, plugin_change=lambda p: p.update(requiredTools=[]))
            self.rejected(mode=mode, plugin_change=lambda p: p.update(allowedCount=4))
            self.rejected(mode=mode, plugin_change=lambda p: p.update(completedCount=2))
            self.rejected(mode=mode, plugin_change=lambda p: p['completedPerTool'].update(lexicon_ielts_validate=2))

    def test_required_tools_are_fixed_by_stage_and_unknown_stages_fail(self):
        self.assertEqual(TOOLS, native.required_stage_tools('generation'))
        for mode in ('review', 'explanation_review'):
            self.assertEqual(('lexicon_ielts_validate',), native.required_stage_tools(mode))
        for mode in ('other', None, '', []):
            with self.assertRaises(native.HarnessError): native.required_stage_tools(mode)

    def test_incomplete_schema_or_registration_receipt_is_rejected(self):
        for field in ('pluginReady', 'globalGuard', 'registrationIdentityVerified', 'candidateSchemaStrict', 'candidateCompilerReady'):
            for value in (False, 1):
                with self.subTest(field=field, value=value): self.rejected(plugin_change=lambda p: p.update({field: value}))
        self.rejected(plugin_change=lambda p: p.update(schemaModuleSha256='f' * 64))
        self.rejected(plugin_change=lambda p: p.update(candidateModuleSha256='f' * 64))

    def test_final_confirmation_cannot_hide_excessive_whitespace_or_duplicate_keys(self):
        def padding(events): events[-1]['text'] = ' ' * 1024 + events[-1]['text']
        self.assertEqual('candidate_confirmation_bound', self.rejected(event_change=padding)['error'])
        def duplicate(events): events[-1]['text'] = '{"candidate_sha256":"a","candidate_sha256":"b"}'
        self.assertEqual('model_json_invalid', self.rejected(event_change=duplicate)['error'])

    def test_late_candidate_is_not_published_after_process_cancellation(self):
        receipt = self.rejected(execution_change=lambda e: e.update(stop_reason='cancelled'))
        self.assertEqual('cancelled', receipt['error'])

    def test_review_model_and_reasoning_receipt_come_from_host_preparation(self):
        result, receipt = self.invoke(mode='review', prepare_change=lambda p: p.update(model='deepseek-v4-pro', reasoning_effort='high', **native.stage_execution_limits('high')))
        self.assertEqual(self.review, result)
        self.assertEqual('deepseek-v4-pro', receipt['model']); self.assertEqual('high', receipt['reasoning_effort'])
        self.assertNotIn('model', self.review); self.assertNotIn('reasoning_effort', self.review)
        self.assertEqual(native.stage_execution_limits('high'),
                         {key: receipt[key] for key in native.stage_execution_limits('high')})

    def test_mismatched_budget_is_rejected_before_starting_native_process(self):
        for field in native.stage_execution_limits('off'):
            for value in (None, True, 0, 16385):
                with self.subTest(field=field, value=value), patch.object(native, 'run_owned_process') as process:
                    with self.assertRaisesRegex(native.HarnessError, 'runtime_budget_binding_mismatch'):
                        native.run_native_stage(dict(model='deepseek-flash', reasoning_effort='off',
                                                     **dict(native.stage_execution_limits('off'), **{field: value})),
                                                'synthetic', mode='generation')
                    process.assert_not_called()

    def test_generation_models_are_allowlisted_but_reasoning_stays_off(self):
        for model in native.GENERATION_MODELS.values():
            native.validate_stage_model('generation', model, 'off')
        for model in native.REVIEW_MODELS.values():
            for effort in native.REASONING_EFFORTS:
                native.validate_stage_model('review', model, effort)
                native.validate_stage_model('explanation_review', model, effort)
        for mode, model, effort in (('generation', 'deepseek-v4-pro', 'high'), ('generation', 'deepseek-flash', 'high'),
                                    ('generation', 'deepseek-chat', 'off'), ('generation', 'pro', 'off'),
                                    ('review', 'deepseek-chat', 'off'), ('review', 'deepseek-v4-pro', 'ultra'),
                                    ('explanation_review', 'deepseek-chat', 'off'),
                                    ('explanation_review', 'deepseek-v4-pro', 'ultra')):
            with self.subTest(mode=mode, model=model, effort=effort), self.assertRaises(native.HarnessError):
                native.validate_stage_model(mode, model, effort)

    def test_pro_generation_receipt_uses_the_host_prepared_model(self):
        result, receipt = self.invoke(prepare_change=lambda p: p.update(model='deepseek-v4-pro'))
        self.assertEqual(self.package, result)
        self.assertEqual(('deepseek-v4-pro', 'off'), (receipt['model'], receipt['reasoning_effort']))

    def test_explanation_stage_preserves_complete_rejection_as_completed_runtime(self):
        self.explanation_review['explanation_reviews'][0]['unit_reviews'][0].update(
            relation='unsupported', reason='Synthetic finding: the explanation changes a quantifier.')
        result, receipt = self.invoke(mode='explanation_review', prepare_change=lambda p: p.update(model='deepseek-v4-pro'))
        self.assertEqual(self.explanation_review, result)
        self.assertEqual(('completed', 'explanation_review', 'deepseek-v4-pro'),
                         (receipt['status'], receipt['stage'], receipt['model']))
        self.assertEqual('8' * 64, receipt['explanation_review_module_sha256'])
        self.assertEqual('9' * 64, receipt['blind_review_input_sha256'])

    def test_explanation_stage_requires_own_module_and_frozen_blind_input_receipts(self):
        for field in ('explanationReviewModuleSha256', 'blindReviewInputSha256'):
            for value in (None, 'a' * 64):
                with self.subTest(field=field, value=value):
                    receipt = self.rejected(mode='explanation_review', plugin_change=lambda p: p.update({field: value}))
                    self.assertEqual('explanation_review_binding_mismatch', receipt['error'])
        self.rejected(mode='explanation_review', prepare_change=lambda p: p.pop('explanation_review_module_sha256'))

    def test_explanation_stage_cannot_publish_incomplete_tools_or_runtime(self):
        self.rejected(mode='explanation_review', plugin_change=lambda p: p['completedPerTool'].update(lexicon_ielts_validate=0))
        self.rejected(mode='explanation_review', execution_change=lambda e: e.update(stop_reason='timeout'))
        self.rejected(mode='explanation_review', event_change=lambda events: events.pop(-2))


class StagePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix='ielts-stage-preparation-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.request, self.package, self.blind, self.explanation = explanation_fixture()
        self.corpus = Path(__file__).resolve().parents[1] / 'dist/validation-ielts-corpus-2026-11-08/corpus-01/reference.json'
        from ielts_corpus import load, style_source_bundle
        sources = ['ielts.format.academic_reading', 'ielts.writing.academic_task_2']
        styles = style_source_bundle(load(self.corpus, expected_sha256=native.sha256_file(self.corpus)), sources)
        self.request.update(style_sources=styles['style_sources'], style_blueprint_sha256=styles['style_blueprint_sha256'])
        self.package.update(style_source_ids=sources, request_sha256=canonical_sha256(self.request))
        self.blind.update(request_sha256=canonical_sha256(self.request), package_sha256=canonical_sha256(self.package))
        self.route = SimpleNamespace(model='deepseek-v4-pro', token_limit=8192, provider_deadline_seconds=90,
                                     base_url='http://127.0.0.1:18000/v1', token='synthetic-route-token',
                                     server=SimpleNamespace(server_port=18000))

    def test_thinking_route_requires_its_own_cap_and_deadline_before_writing(self):
        for tokens, deadline in ((8192, 180), (16384, 90), (16384, 181), (True, 180)):
            self.route.token_limit, self.route.provider_deadline_seconds = tokens, deadline
            directory = self.root / 'wrong-limits'
            with self.subTest(tokens=tokens, deadline=deadline), self.assertRaisesRegex(native.HarnessError, 'stage_output_bound'):
                native.prepare_stage(directory, self.request, self.corpus, self.route,
                                     mode='explanation_review', package=self.package, blind_review=self.blind,
                                     review_id=self.explanation['review_id'], reasoning_effort='high')
            self.assertFalse(directory.exists())

    def test_explanation_stage_does_not_prepare_before_a_complete_agreeing_blind_review(self):
        for blind in (None, {'review_sha256': canonical_sha256(self.blind)},
                      dict(self.blind, reading_reviews=[])):
            directory = self.root / 'not-created'
            with self.subTest(blind=blind), self.assertRaises(ValueError):
                native.prepare_stage(directory, self.request, self.corpus, self.route,
                                     mode='explanation_review', package=self.package, blind_review=blind,
                                     review_id=self.explanation['review_id'])
            self.assertFalse(directory.exists())
        rejected = copy.deepcopy(self.blind)
        rejected['reading_reviews'][0]['observed_answer'] = 'B'
        with self.assertRaisesRegex(native.HarnessError, 'blind_review_rejected'):
            native.prepare_stage(self.root / 'rejected', self.request, self.corpus, self.route,
                                 mode='explanation_review', package=self.package, blind_review=rejected,
                                 review_id=self.explanation['review_id'])
        self.assertFalse((self.root / 'rejected').exists())

    def test_preparation_pins_explanation_module_and_blind_result_without_native_execution(self):
        active = ('llm', 'session', 'agent', 'agent-default-model', 'session-persistence-jsonl',
                  'session-projection', 'tools', 'system-prompt', 'agent-loop', 'llm-deepseek',
                  'headless-startup', 'headless-runner', 'lexicon-ielts', 'lexicon-session-query')
        profile = effective_profile_fixture()
        calls = [SimpleNamespace(returncode=0, stdout='[]'), SimpleNamespace(returncode=0, stdout=profile)]
        with patch.object(native.subprocess, 'run', side_effect=calls) as process:
            prepared = native.prepare_stage(self.root / 'explanation', self.request, self.corpus, self.route,
                                            mode='explanation_review', package=self.package, blind_review=self.blind,
                                            review_id=self.explanation['review_id'])
        self.assertEqual(2, process.call_count)
        manifest = json.loads((prepared['directory'] / 'inputs/manifest.json').read_text())
        self.assertEqual('explanation_review', manifest['mode'])
        self.assertEqual(self.explanation['review_id'], manifest['reviewId'])
        for key, file_name, field in (('explanationReviewModule', 'ielts_explanation_review.py', 'explanation_review_module_sha256'),
                                      ('blindReview', 'blindReview.json', 'blind_review_input_sha256')):
            pinned = manifest['files'][key]
            self.assertEqual(file_name, Path(pinned['path']).name)
            self.assertEqual(native.sha256_file(pinned['path']), pinned['sha256'])
            self.assertEqual(pinned['sha256'], prepared[field])
        self.assertEqual(self.blind, json.loads(Path(manifest['files']['blindReview']['path']).read_text()))


class EffectiveModelProfileTest(unittest.TestCase):
    def validate(self, raw, effort='high'):
        native.validate_effective_model_profile(raw, model='deepseek-v4-pro', reasoning_effort=effort,
                                               limits=native.stage_execution_limits(effort))

    def test_fixed_actual_dump_values_match_every_reasoning_level(self):
        for effort in native.REASONING_EFFORTS:
            self.validate(effective_profile_fixture(reasoning_effort=effort), effort)

    def test_duplicate_or_changed_caps_and_timeout_fail_closed(self):
        raw = effective_profile_fixture(reasoning_effort='high')
        changes = [
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 8192', 1),
            raw.replace(b'        maxTokens: 16384', b'        maxTokens: 8192'),
            raw.replace(b'    streamIdleTimeoutMs: 190000', b'    streamIdleTimeoutMs: 100000'),
            raw.replace(b'      maxRetries: 0', b'      maxRetries: 1'),
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 16384\n    maxTokens: 8192', 1),
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 16384\n    maxTokens:', 1),
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 16384\n    "maxTokens": 8192', 1),
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 16384\n  config: {maxTokens: 8192}', 1),
            raw.replace(b'    maxTokens: 16384', b'    maxTokens: 16384\n    <<: *untrusted', 1),
            raw.replace(b'      - id: deepseek-v4-pro', b'      - id: deepseek-v4-pro\n      - id: deepseek-flash'),
            raw + raw,
            raw.replace(b"    reasoningEffort: 'high'", b'    reasoningEffort: !!js/function >', 1),
        ]
        for changed in changes:
            with self.subTest(profile=changed), self.assertRaisesRegex(native.HarnessError, 'profile_model_limits_mismatch'):
                self.validate(changed)


class FinalConfirmationParserTest(unittest.TestCase):
    def setUp(self):
        self.value = {'candidate_sha256': 'a' * 64}
        self.exact = json.dumps(self.value, separators=(',', ':'))

    def test_plain_json_and_delimited_plain_prose_prefixes_are_accepted(self):
        for text in (self.exact, ' \n' + self.exact + '\n', 'Saved.\n\n' + self.exact,
                     '已保存并完成校验。\n' + self.exact):
            self.assertEqual(self.value, native.parse_candidate_confirmation(text))

    def test_multiple_objects_arrays_embedded_json_and_suffix_are_rejected(self):
        for text in ('{}\n' + self.exact, 'Saved {}\n' + self.exact,
                     '[]\n' + self.exact, 'Saved [1,2]\n' + self.exact,
                     self.exact + '\n' + self.exact, '[' + self.exact + ']',
                     'Saved.\n' + self.exact + '\nFinished.',
                     '```json\n' + self.exact + '\n```', 'Saved:' + self.exact,
                     '"Saved"\n' + self.exact, 'null\n' + self.exact,
                     'false\n' + self.exact, '12\n' + self.exact):
            with self.subTest(text=text), self.assertRaises(native.HarnessError):
                native.parse_candidate_confirmation(text)

    def test_missing_extra_duplicate_malformed_and_wrong_hash_shape_are_rejected(self):
        invalid = ('{}', '{"candidate_sha256":null}', '{"candidate_sha256":"a"}',
                   '{"candidate_sha256":"' + 'A' * 64 + '"}',
                   '{"candidate_sha256":"' + 'a' * 64 + '","extra":true}',
                   '{"candidate_sha256":"' + 'a' * 64 + '","candidate_sha256":"' + 'a' * 64 + '"}',
                   self.exact[:-1], self.exact + ',', '{candidate_sha256:"' + 'a' * 64 + '"}')
        for candidate in invalid:
            for prefix in ('', 'Saved.\n'):
                with self.subTest(candidate=candidate, prefix=prefix), self.assertRaises(native.HarnessError):
                    native.parse_candidate_confirmation(prefix + candidate)

    def test_byte_limit_applies_before_parsing_and_counts_utf8_prefix(self):
        exact_limit = 'x' * (1024 - len(self.exact.encode()) - 1) + '\n' + self.exact
        self.assertEqual(self.value, native.parse_candidate_confirmation(exact_limit))
        for text in ('x' + exact_limit, '界' * 320 + '\n' + self.exact, ' ' * 1024 + self.exact):
            with self.assertRaisesRegex(native.HarnessError, 'candidate_confirmation_bound'):
                native.parse_candidate_confirmation(text)

    def test_generic_terminal_json_parser_does_not_accept_a_prose_prefix(self):
        events = [dict(type='session', sessionId='session-synthetic-prefix'),
                  dict(type='status', phase='turn_start'),
                  dict(type='status', phase='turn_end', reason={'kind': 'completed'}),
                  dict(type='final', text='Saved.\n' + self.exact)]
        raw = ('\n'.join(json.dumps(e) for e in events) + '\n').encode()
        execution = dict(exit_code=0, process_reaped=True, stop_reason=None, input_write_failed=False)
        with self.assertRaisesRegex(native.HarnessError, 'model_json_invalid'):
            native.validate_terminal(raw, execution)
        self.assertEqual(self.value, native.validate_candidate_terminal(raw, execution)[0])
        execution['stop_reason'] = 'cancelled'
        with self.assertRaisesRegex(native.HarnessError, 'cancelled'):
            native.validate_candidate_terminal(raw, execution)


if __name__ == '__main__':
    unittest.main()
