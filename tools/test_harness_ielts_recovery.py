"""Offline native-boundary tests for one same-session malformed-tool correction."""
import copy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import harness_ielts as native
from harness_model_proxy import ModelBudget
from ielts_contract import canonical_sha256
from test_ielts_review import review_fixture
from test_harness_ielts import native_conclusion_fixture


class NativeRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix='ielts-native-recovery-')
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.request, self.package, self.review = review_fixture()
        self.prepared = dict(state=self.state, manifest_sha256='a' * 64, profile_sha256='b' * 64,
            plugin_sha256='c' * 64, corpus_sha256='d' * 64, schema_module_sha256='e' * 64,
            candidate_module_sha256='f' * 64, command=['fixed-native', '--json', '-'], cwd=self.state,
            env={'DSH_HOME': str(self.state / 'private-session')}, model='deepseek-v4-pro', reasoning_effort='off',
            **native.stage_execution_limits('off'))
        self.prepared['continuation_path'] = self.state / 'continuation.json'
        self.prompt = 'synthetic frozen review'
        self.budget = ModelBudget(16)
        self.calls = []
        self.first_error = {'code': 'MALFORMED_RESPONSE', 'message': 'DeepSeek Messages stream: tool input is invalid JSON'}
        self.first_plugin_change = None
        self.first_reads = 0
        self.first_retrievals = 0
        self.second_plugin_change = None
        self.first_event_delta = 0
        self.second_session = 'session-original'
        self.second_missing_plugin = False
        self.unknown = False
        self.second_fails = False
        self.extra_candidate = False
        self.mode = 'review'

    def plugin(self, completed):
        counts = {name: int(completed and name == 'lexicon_ielts_validate') for name in native.HARNESS_TOOL_NAMES}
        value = dict(manifestSha256=self.prepared['manifest_sha256'], mode=self.mode, pluginReady=True,
            globalGuard=True, registrationIdentityVerified=True, candidateSchemaStrict=True, candidateCompilerReady=True,
            schemaModuleSha256=self.prepared['schema_module_sha256'], candidateModuleSha256=self.prepared['candidate_module_sha256'],
            requiredTools=list(native.required_stage_tools(self.mode)), toolNames=list(native.HARNESS_TOOL_NAMES),
            perTool=dict(counts), completedPerTool=dict(counts), allowedCount=int(completed), completedCount=int(completed),
            failedCount=0, deniedCount=0, registrationChecks=int(completed)*2, identityFailures=0,
            candidateSha256=canonical_sha256(self.review) if completed else None,
            limits={'maxToolCalls': native.MAX_STAGE_TOOL_CALLS}, carriedToolCalls=0, continuationSha256=None,
            candidateBytes=len(json.dumps(self.review,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()) if completed else 0)
        if self.prepared.get('delivery_kind') == native.NATIVE_DELIVERY:
            value.update(candidateDelivery=native.NATIVE_DELIVERY,
                nativeToolConclusion=native_conclusion_fixture(value, self.second_session) if completed else None)
        return value

    def record(self, stop):
        ordinal = self.budget.reserve(); self.budget.started(ordinal, False)
        if self.unknown:
            self.budget.completion_unknown(ordinal, 200, .01, False, 'response_deadline')
        else:
            self.budget.record(ordinal, 200, .01, 100, False, dict(format='anthropic_sse', metadata_status='observed',
                stop_reason=stop, input_tokens=12, output_tokens=30, message_stop_observed=True))

    def process(self, command, *, cwd, env, prompt, timeout, cancelled):
        self.calls.append(dict(command=list(command), cwd=cwd, env=dict(env), prompt=prompt, timeout=timeout))
        first = len(self.calls) == 1
        failed = first or self.second_fails
        self.record('tool_use')
        if first and self.first_reads + self.first_retrievals:
            self.record('tool_use')
        is_native = self.prepared.get('delivery_kind') == native.NATIVE_DELIVERY
        if not failed and not is_native: self.record('end_turn')
        plugin = self.plugin(not failed)
        if first:
            for name, count in [('lexicon_ielts_read', self.first_reads), ('lexicon_ielts_retrieve', self.first_retrievals)]:
                plugin['perTool'][name] = plugin['completedPerTool'][name] = count
            used = sum(plugin['perTool'].values())
            plugin.update(allowedCount=used, completedCount=used, registrationChecks=used * 2)
        else:
            seed = json.loads(self.prepared['continuation_path'].read_text())
            if self.mode == 'generation':
                plugin['carriedCompletedPerTool'] = {'lexicon_ielts_retrieve': self.first_retrievals, 'lexicon_ielts_read': self.first_reads, 'lexicon_ielts_validate': 0}
            plugin.update(carriedToolCalls=seed['carriedToolCalls'],
                          continuationSha256=native.sha256_file(self.prepared['continuation_path']))
        if first and self.first_plugin_change: self.first_plugin_change(plugin)
        if not first and self.second_plugin_change: self.second_plugin_change(plugin)
        if first or not self.second_missing_plugin:
            (self.state / 'plugin-receipt.json').write_text(json.dumps(plugin))
        if not failed or self.extra_candidate:
            (self.state / 'validated-candidate.json').write_text(json.dumps(self.review,ensure_ascii=False,sort_keys=True,separators=(',',':')))
        events = [dict(type='session',sessionId='session-original' if first else self.second_session),dict(type='status',phase='turn_start')]
        if first:
            events += [dict(type='tool_call',tool='lexicon_ielts_read') for _ in range(self.first_reads + self.first_event_delta)]
            events += [dict(type='tool_call',tool='lexicon_ielts_retrieve') for _ in range(self.first_retrievals)]
        if not failed: events.append(dict(type='tool_call',tool='lexicon_ielts_validate'))
        reason = dict(kind='error',error=self.first_error) if failed else dict(kind='completed')
        events += [dict(type='status',phase='turn_end',reason=reason),
                   dict(type='final',text='' if failed or is_native else json.dumps({'candidate_sha256':canonical_sha256(self.review)}))]
        raw = ('\n'.join(json.dumps(v) for v in events)+'\n').encode()
        return raw, dict(exit_code=1 if failed else 0,process_reaped=True,stop_reason=None,
            elapsed_ms=0,stderr_bytes_discarded=0,input_write_failed=False)

    def invoke(self, *, cancelled=lambda: False, timeout=60):
        with patch.object(native,'verify_native_version',return_value=native.RUNTIME_VERSION), \
             patch.object(native,'run_owned_process',side_effect=self.process):
            return native.run_native_stage(self.prepared,self.prompt,mode=self.mode,timeout=timeout,
                cancelled=cancelled,model_budget=self.budget,first_request_ordinal=1)

    def fresh_case(self):
        case = type(self)()
        case.setUp()
        self.addCleanup(case.doCleanups)
        return case

    def test_same_session_profile_route_and_prompt_hashes_with_original_failures_preserved(self):
        value, receipt = self.invoke()
        self.assertEqual(self.review,value)
        self.assertEqual(2,len(self.calls)); self.assertEqual(3,self.budget.calls)
        self.assertEqual(['fixed-native','--session-id','session-original','--json','-'],self.calls[1]['command'])
        for field in ('cwd','env'): self.assertEqual(self.calls[0][field],self.calls[1][field])
        self.assertEqual(native.TOOL_JSON_REPAIR_PROMPT,self.calls[1]['prompt'])
        self.assertLess(self.calls[1]['timeout'],self.calls[0]['timeout'])
        self.assertEqual([[1],[2,3]],[a['model_request_ordinals'] for a in receipt['recovery']['attempts']])
        self.assertEqual(2,receipt['native_attempt_count'])
        self.assertEqual(hashlib.sha256(self.prompt.encode()).hexdigest(),receipt['prompt_sha256'])
        for i, attempt in enumerate(receipt['recovery']['attempts'],start=1):
            folder = self.state / ('attempt-%02d'%i)
            self.assertEqual(attempt['runtime_receipt_sha256'],native.sha256_file(folder/'runtime-receipt.json'))
            self.assertEqual(attempt['plugin_receipt_sha256'],native.sha256_file(folder/'plugin-receipt.json'))
        native.validate_native_recovery_receipt(receipt,self.budget.snapshot())
        self.assertEqual('failed',receipt['recovery']['attempts'][0]['receipt']['status'])
        self.assertEqual('completed',receipt['recovery']['attempts'][1]['receipt']['status'])

    def test_unknown_completion_and_insufficient_shared_budget_never_resume(self):
        self.unknown=True
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls));self.assertNotIn('recovery',receipt)

    def test_less_than_two_remaining_model_calls_never_resume(self):
        self.budget=ModelBudget(2)
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls));self.assertEqual(1,self.budget.calls)

    def test_cancel_after_failure_never_sends_correction(self):
        value,receipt=self.invoke(cancelled=lambda:len(self.calls)>0)
        self.assertIsNone(value);self.assertEqual(1,len(self.calls));self.assertTrue(receipt['process_reaped'])

    def test_cancel_during_archival_preserves_reaped_failure_without_second_process(self):
        value,receipt=self.invoke(cancelled=Mock(side_effect=[False,True,True]))
        self.assertIsNone(value);self.assertEqual(1,len(self.calls))
        self.assertEqual('cancelled_before_recovery',receipt['error'])
        self.assertTrue((self.state/'attempt-01/runtime-receipt.json').is_file())
        self.assertTrue((self.state/'runtime-receipt.json').is_file())

    def test_deadline_consumed_during_archival_does_not_restart_full_clock(self):
        with patch.object(native.time,'monotonic',side_effect=[0,.1,1.2]):
            value,receipt=self.invoke(timeout=2)
        self.assertIsNone(value);self.assertEqual(1,len(self.calls))
        self.assertEqual('timeout_before_recovery',receipt['error'])

    def test_other_adapter_error_does_not_trigger_repair(self):
        self.first_error={'code':'MALFORMED_RESPONSE','message':'DeepSeek Messages stream: event mismatch'}
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls));self.assertNotIn('runtime_error_type',receipt)

    def test_unaccounted_prior_tool_attempt_prevents_reexecution(self):
        self.first_plugin_change=lambda p:p.update(allowedCount=1)
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls))

    def test_completed_mixed_reads_carry_budget_without_double_counting(self):
        self.first_reads = 3
        self.first_retrievals = 1
        value, receipt = self.invoke()
        self.assertEqual(self.review, value)
        self.assertEqual(4, self.budget.calls)
        first, second = [a['receipt'] for a in receipt['recovery']['attempts']]
        self.assertEqual((4, 1), (first['tool_receipt']['allowedCount'], second['tool_receipt']['allowedCount']))
        self.assertEqual(4, second['tool_receipt']['carriedToolCalls'])
        self.assertEqual(4, second['carried_tool_calls'])
        self.assertEqual([[1, 2], [3, 4]], [a['model_request_ordinals'] for a in receipt['recovery']['attempts']])
        seed = json.loads(self.prepared['continuation_path'].read_text())
        self.assertEqual(4, seed['carriedToolCalls'])
        self.assertEqual(24, seed['maxToolCalls'])
        for name in ('runtime', 'plugin'):
            self.assertEqual(native.sha256_file(self.state / 'attempt-01' / (name + '-receipt.json')),
                             seed[name + 'ReceiptSha256'])
        self.assertEqual(native.sha256_file(self.prepared['continuation_path']), receipt['recovery']['continuation_sha256'])
        native.validate_native_recovery_receipt(receipt, self.budget.snapshot())

    def test_twenty_three_reads_leave_one_call_and_twenty_four_never_resume(self):
        self.first_reads = 23
        value, receipt = self.invoke()
        self.assertEqual(self.review, value)
        self.assertEqual(23, receipt['tool_receipt']['carriedToolCalls'])
        self.assertEqual(1, receipt['tool_receipt']['allowedCount'])
        native.validate_native_recovery_receipt(receipt, self.budget.snapshot())
        full = self.fresh_case()
        full.first_reads = 24
        value, receipt = full.invoke()
        self.assertIsNone(value)
        self.assertEqual(1, len(full.calls))
        self.assertFalse(full.prepared['continuation_path'].exists())

    def test_partial_reads_validation_and_invalid_receipt_counts_never_resume(self):
        mutations = {
            'partial_read': lambda p: p['completedPerTool'].update(lexicon_ielts_read=0),
            'validate_attempt': lambda p: p['perTool'].update(lexicon_ielts_validate=1),
            'validate_completed': lambda p: (p['perTool'].update(lexicon_ielts_validate=1), p['completedPerTool'].update(lexicon_ielts_validate=1)),
            'registration_mismatch': lambda p: p.update(registrationChecks=1),
            'failed_tool': lambda p: p.update(failedCount=1),
            'denied_tool': lambda p: p.update(deniedCount=1),
            'identity_failure': lambda p: p.update(identityFailures=1),
            'boolean_count': lambda p: p.update(allowedCount=True),
            'float_budget': lambda p: p.update(limits={'maxToolCalls':24.0}),
            'larger_budget': lambda p: p.update(limits={'maxToolCalls':25}),
            'prior_continuation': lambda p: p.update(carriedToolCalls=1),
            'prior_seed': lambda p: p.update(continuationSha256='9'*64),
            'module_mismatch': lambda p: p.update(candidateModuleSha256='0'*64),
        }
        for name, change in mutations.items():
            with self.subTest(name=name):
                case = self.fresh_case()
                case.first_reads = 1
                case.first_plugin_change = change
                value, receipt = case.invoke()
                self.assertIsNone(value)
                self.assertEqual(1, len(case.calls))
                self.assertNotIn('recovery', receipt)

    def test_native_event_count_must_match_completed_reads(self):
        self.first_reads = 2
        self.first_event_delta = -1
        value, receipt = self.invoke()
        self.assertIsNone(value)
        self.assertEqual(1, len(self.calls))
        self.assertNotIn('recovery', receipt)

    def test_second_plugin_must_bind_the_actual_seed_and_carried_count(self):
        for name, change in {
            'undercounted': lambda p: p.update(carriedToolCalls=0),
            'overcounted': lambda p: p.update(carriedToolCalls=2),
            'seed_changed': lambda p: p.update(continuationSha256='9'*64),
            'seed_missing': lambda p: p.pop('continuationSha256'),
        }.items():
            with self.subTest(name=name):
                case = self.fresh_case()
                case.first_reads = 1
                case.second_plugin_change = change
                value, receipt = case.invoke()
                self.assertIsNone(value)
                self.assertEqual(2, len(case.calls))
                self.assertEqual('continuation_binding_mismatch', receipt['error'])

    def test_second_plugin_overall_tool_budget_is_enforced_at_acceptance_too(self):
        self.first_reads = 23
        def add_read(p):
            p['perTool']['lexicon_ielts_read'] = p['completedPerTool']['lexicon_ielts_read'] = 1
            p.update(allowedCount=2, completedCount=2, registrationChecks=4)
        self.second_plugin_change = add_read
        value, receipt = self.invoke()
        self.assertIsNone(value)
        self.assertEqual('failed', receipt['status'])
        self.assertEqual(2, len(self.calls))

    def test_preexisting_seed_and_missing_fixed_seed_path_never_resume(self):
        self.prepared['continuation_path'].write_text('{}')
        value, _ = self.invoke()
        self.assertIsNone(value)
        self.assertEqual(1, len(self.calls))
        case = self.fresh_case()
        del case.prepared['continuation_path']
        value, _ = case.invoke()
        self.assertIsNone(value)
        self.assertEqual(1, len(case.calls))

    def test_seed_write_failure_retains_first_originals_without_second_process(self):
        original = native.write_json_new
        def writing(path, value):
            if path == self.prepared['continuation_path']:
                raise OSError('synthetic-write-failure')
            return original(path, value)
        with patch.object(native, 'write_json_new', side_effect=writing):
            value, receipt = self.invoke()
        self.assertIsNone(value)
        self.assertEqual(1, len(self.calls))
        self.assertEqual('continuation_write_failed', receipt['error'])
        self.assertTrue((self.state / 'attempt-01/runtime-receipt.json').is_file())
        self.assertTrue((self.state / 'attempt-01/plugin-receipt.json').is_file())
        self.assertTrue((self.state / 'runtime-receipt.json').is_file())

    def test_missing_second_plugin_receipt_is_incomplete_and_never_publishable(self):
        self.second_missing_plugin = True
        value, receipt = self.invoke()
        self.assertIsNone(value)
        self.assertEqual(2, len(self.calls))
        self.assertEqual('recovery_plugin_receipt_missing', receipt['error'])
        self.assertTrue(receipt['recovery_incomplete'])
        self.assertTrue((self.state / 'attempt-01/plugin-receipt.json').is_file())
        self.assertTrue((self.state / 'attempt-02/runtime-receipt.json').is_file())
        with self.assertRaises(native.HarnessError):
            native.validate_native_recovery_receipt(receipt, self.budget.snapshot())

    def test_changed_second_session_does_not_pass_recovery_binding(self):
        self.second_session = 'session-other'
        _, receipt = self.invoke()
        with self.assertRaises(native.HarnessError):
            native.validate_native_recovery_receipt(receipt, self.budget.snapshot())

    def test_nested_or_stripped_continuation_receipts_are_rejected(self):
        _, receipt = self.invoke()
        second = receipt['recovery']['attempts'][1]['receipt']
        with self.assertRaises(native.HarnessError):
            native.validate_native_recovery_receipt(second, self.budget.snapshot())
        first = copy.deepcopy(receipt['recovery']['attempts'][0]['receipt'])
        first['recovery'] = {}
        self.assertFalse(native._malformed_attempt_can_resume(first))

    def test_rebound_second_seed_or_budget_cannot_hide_first_read_cost(self):
        self.first_reads = 2
        _, receipt = self.invoke()
        for key, value in [('continuation_sha256', 'e'*64), ('carried_tool_calls', 0)]:
            with self.subTest(key=key):
                changed = copy.deepcopy(receipt)
                attempt = changed['recovery']['attempts'][1]
                attempt['receipt'][key] = value
                changed[key] = value
                attempt['runtime_receipt_sha256'] = hashlib.sha256(json.dumps(
                    attempt['receipt'], ensure_ascii=False, sort_keys=True, indent=2).encode()).hexdigest()
                with self.assertRaises(native.HarnessError):
                    native.validate_native_recovery_receipt(changed, self.budget.snapshot())

    def test_existing_candidate_prevents_reexecution(self):
        self.extra_candidate=True
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls));self.assertTrue(receipt['candidate_exists_after_attempt'])

    def test_second_malformed_result_is_terminal_no_third_attempt(self):
        self.second_fails=True
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(2,len(self.calls));self.assertEqual('failed',receipt['status'])
        self.assertEqual(2,receipt['native_attempt_count'])
        self.assertTrue((self.state/'attempt-02/runtime-receipt.json').exists())

    def test_generation_does_not_gain_native_recovery(self):
        self.mode='generation'
        value,receipt=self.invoke()
        self.assertIsNone(value);self.assertEqual(1,len(self.calls))

    def test_recovery_does_not_accept_dropped_history_or_different_session(self):
        _,receipt=self.invoke()
        changed=copy.deepcopy(receipt);del changed['recovery']
        with self.assertRaises(native.HarnessError):native.validate_native_recovery_receipt(changed,self.budget.snapshot())
        for key,value in [('session_id','session-other'),('prompt_sha256','f'*64),('native_attempt_count',1)]:
            changed=copy.deepcopy(receipt);changed[key]=value
            with self.subTest(key=key),self.assertRaises(native.HarnessError):
                native.validate_native_recovery_receipt(changed,self.budget.snapshot())

    def test_recovery_rechecks_original_exact_error_even_when_digest_is_rebound(self):
        _,receipt=self.invoke();changed=copy.deepcopy(receipt)
        a=changed['recovery']['attempts'][0];a['receipt']['runtime_error_type']='other_error'
        a['runtime_receipt_sha256']=hashlib.sha256(json.dumps(a['receipt'],ensure_ascii=False,sort_keys=True,indent=2).encode()).hexdigest()
        with self.assertRaises(native.HarnessError):native.validate_native_recovery_receipt(changed,self.budget.snapshot())

    def test_recovery_rechecks_complete_provider_stop_and_same_request_ordinals(self):
        _,receipt=self.invoke()
        for field,value in [('phase','completion_unknown'),('http_status',429)]:
            rows=copy.deepcopy(self.budget.snapshot());rows[0][field]=value
            with self.subTest(field=field),self.assertRaises(native.HarnessError):native.validate_native_recovery_receipt(receipt,rows)
        rows=copy.deepcopy(self.budget.snapshot());rows[0]['termination']['stop_reason']='max_tokens'
        with self.assertRaises(native.HarnessError):native.validate_native_recovery_receipt(receipt,rows)
        changed=copy.deepcopy(receipt);changed['recovery']['attempts'][1]['model_request_ordinals']=[1,2]
        with self.assertRaises(native.HarnessError):native.validate_native_recovery_receipt(changed,self.budget.snapshot())


class FinalToolRecoveryTest(unittest.TestCase):
    def case(self, reads=0):
        case=NativeRecoveryTest(); case.setUp(); self.addCleanup(case.doCleanups)
        case.prepared['delivery_kind']=native.NATIVE_DELIVERY
        case.first_reads=reads
        return case

    def test_same_session_correction_needs_only_one_remaining_provider_request(self):
        for reads in (0, 3, 23):
            case=self.case(reads)
            case.budget=ModelBudget(2 if not reads else 3)
            value, receipt=case.invoke()
            self.assertEqual(case.review,value)
            self.assertEqual(2,len(case.calls))
            self.assertEqual(case.budget.max_calls,case.budget.calls)
            self.assertEqual(native.tool_json_repair_prompt(native.NATIVE_DELIVERY),case.calls[1]['prompt'])
            first,second=[a['receipt'] for a in receipt['recovery']['attempts']]
            self.assertEqual(first['session_id'],second['session_id'])
            self.assertEqual(reads,second['tool_receipt']['carriedToolCalls'])
            self.assertEqual(1,second['tool_receipt']['nativeToolConclusion']['allowedOrdinal'])
            self.assertIsNone(first['tool_receipt']['nativeToolConclusion'])
            self.assertFalse(first['candidate_exists_after_attempt'])
            self.assertNotIn('candidate_confirmation_verified',receipt)
            native.validate_candidate_delivery_receipt(receipt)
            native.validate_native_recovery_receipt(receipt,case.budget.snapshot())

    def test_unknown_outcome_validate_attempt_or_full_tool_budget_never_resume(self):
        for problem in ('unknown','validate','limit','mixed','committed'):
            case=self.case()
            if problem=='unknown':case.unknown=True
            if problem=='limit':case.first_reads=24
            if problem=='validate':case.first_plugin_change=lambda p:p['perTool'].update(lexicon_ielts_validate=1)
            if problem=='mixed':case.first_plugin_change=lambda p:p.update(candidateDelivery=native.LEGACY_DELIVERY)
            if problem=='committed':case.first_plugin_change=lambda p:p.update(nativeToolConclusion={'committed':True})
            value,receipt=case.invoke()
            with self.subTest(problem=problem):
                self.assertIsNone(value);self.assertEqual(1,len(case.calls))

    def test_corrected_artifact_without_committed_native_binding_stays_failed(self):
        case=self.case(1)
        case.second_plugin_change=lambda p:p.update(nativeToolConclusion=None)
        value,receipt=case.invoke()
        self.assertIsNone(value);self.assertEqual(2,len(case.calls))
        self.assertEqual('failed',receipt['status'])
        self.assertTrue((case.state/'attempt-01/runtime-receipt.json').exists())
        self.assertTrue((case.state/'validated-candidate.json').exists())


class GenerationReadRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.case = NativeRecoveryTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
    def test_generation_recovers_only_after_complete_required_retrieval(self):
        c = self.case
        c.mode = 'generation'
        c.first_reads = 1
        c.first_retrievals = 1
        _, receipt = c.invoke()
        self.assertEqual(2, len(c.calls))
        self.assertEqual('completed', receipt['status'])
        native.validate_native_recovery_receipt(receipt, c.budget.snapshot())

    def test_generation_without_reference_read_does_not_resume(self):
        c = self.case
        c.mode = 'generation'
        c.first_retrievals = 1
        c.first_reads = 0
        _, receipt = c.invoke()
        self.assertEqual(1, len(c.calls))
        self.assertEqual('failed', receipt['status'])

if __name__=='__main__':unittest.main()
