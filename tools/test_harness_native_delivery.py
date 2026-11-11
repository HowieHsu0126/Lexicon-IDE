"""Native-final-tool acceptance, without any real provider or native execution."""
import copy
import hashlib
import unittest

import harness_ielts as native
import ielts_pipeline as pipeline
import ielts_review
import test_harness_ielts as artifact_tests
import test_ielts_pipeline as pipeline_tests
from test_ielts_explanation_review import result_fixture as explanation_fixture, REVIEW_ID as EXPLANATION_ID


class NativeCandidateTest(unittest.TestCase):
    def setUp(self):
        self.case = artifact_tests.CandidateArtifactTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)

    def invoke(self, **kw):
        return self.case.invoke(delivery_kind=native.NATIVE_DELIVERY, **kw)

    def reject(self, **kw):
        value, receipt = self.invoke(**kw)
        self.assertIsNone(value)
        self.assertEqual('failed', receipt['status'])
        self.assertNotIn('native_tool_conclusion_verified', receipt)
        self.assertNotIn('candidate_confirmation_verified', receipt)

    def test_empty_or_existing_assistant_preview_is_not_output_authority(self):
        for mode in ('generation', 'review', 'explanation_review'):
            for preview in ('', 'Prior preview; not the saved result.', '{"candidate_sha256":"wrong"}'):
                with self.subTest(mode=mode, preview=preview):
                    result, receipt = self.invoke(mode=mode, event_change=lambda e: e[-1].update(text=preview))
                    self.assertIsNotNone(result)
                    self.assertTrue(receipt['native_tool_conclusion_verified'])
                    self.assertNotIn('candidate_confirmation_verified', receipt)
                    self.assertNotIn('candidate_confirmation_prefix_bytes', receipt)
                    native.validate_candidate_delivery_receipt(receipt)

    def test_candidate_file_and_completed_turn_without_committed_tool_result_are_insufficient(self):
        for change in (lambda p: p.pop('nativeToolConclusion'), lambda p: p.update(nativeToolConclusion=None)):
            self.reject(plugin_change=change)

    def test_every_conclusion_field_is_required_and_no_extra_authority_is_allowed(self):
        _, valid = self.invoke()
        fields = valid['tool_receipt']['nativeToolConclusion']
        for field in fields:
            with self.subTest(field=field):
                self.reject(plugin_change=lambda p: p['nativeToolConclusion'].pop(field))
        self.reject(plugin_change=lambda p: p['nativeToolConclusion'].update(assistantConfirmed=True))

    def test_conclusion_identity_sequences_bytes_and_tool_count_are_bound(self):
        mutations = dict(schema='foreign', sessionId='session-other', turnId=True, stepId=0,
            callId='', callEventSeq=True, eventSeq=9, toolName='lexicon_ielts_read',
            allowedOrdinal=2, candidateSha256='e'*64, candidateBytes=1,
            toolsResultObserved=1, sessionEventCommitted=False)
        for field, value in mutations.items():
            with self.subTest(field=field):
                self.reject(plugin_change=lambda p: p['nativeToolConclusion'].update({field:value}))
        self.reject(event_change=lambda e: e.insert(2, dict(type='tool_call', tool='lexicon_ielts_read')))

    def test_tail_tool_denial_and_candidate_mutation_reject_even_after_conclusion(self):
        self.reject(plugin_change=lambda p: p.update(deniedCount=1))
        self.reject(artifact=b'{"synthetic":"changed"}')

    def test_delivery_modes_cannot_be_relabelled_or_mixed(self):
        for kind in (None, 'unknown', native.LEGACY_DELIVERY):
            self.reject(plugin_change=lambda p: p.update(candidateDelivery=kind))
        _, valid = self.invoke()
        for key in ('candidate_confirmation_verified', 'candidate_confirmation_prefix_bytes'):
            invalid = copy.deepcopy(valid); invalid[key] = True
            with self.assertRaises(native.HarnessError): native.validate_candidate_delivery_receipt(invalid)
        with self.assertRaises(native.HarnessError):
            native.candidate_delivery_kind({'delivery_kind':'unknown'})

    def test_cancel_failed_exit_write_and_missing_terminal_cannot_promote_candidate(self):
        for key, value in (('exit_code',1), ('process_reaped',False), ('input_write_failed',True),
                           ('stop_reason','cancelled'), ('stop_reason','timeout')):
            with self.subTest(key=key): self.reject(execution_change=lambda e:e.update({key:value}))
        self.reject(event_change=lambda e:e.pop(-2))
        self.reject(event_change=lambda e:e.append(dict(e[-1])))


class NativePublicationTest(unittest.TestCase):
    def setUp(self):
        self.case = pipeline_tests.OutputBindingTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        c = self.case
        results = (c.package, c.review, explanation_fixture(c.request,c.package,c.review))
        for index, (receipt, result) in enumerate(zip(c.receipts, results)):
            pipeline_tests.make_native_receipt(receipt, result)
            kwargs = {} if index == 0 else dict(package=c.package, review_id=c.review_id if index == 1 else EXPLANATION_ID)
            if index == 2: kwargs['blind_review'] = c.review
            receipt['prompt_sha256'] = hashlib.sha256(pipeline.stage_prompt(c.request, **kwargs).encode()).hexdigest()
            c.records[index]['termination'] = dict(format='anthropic_sse', metadata_status='observed',
                stop_reason='tool_use', input_tokens=120, output_tokens=80, message_stop_observed=True)

    def test_three_native_stages_accept_with_final_tool_and_independent_sessions(self):
        self.assertEqual('synthetic_runtime_fixture', self.case.gate()['provenance']['kind'])

    def test_completed_http_alone_cannot_replace_provider_tool_termination(self):
        for stage in range(3):
            for field, value in (('format','anthropic_json'), ('metadata_status','unknown'),
                                 ('stop_reason','max_tokens'), ('stop_reason','end_turn'),
                                 ('message_stop_observed',False), ('input_tokens',None),
                                 ('output_tokens',True), ('output_tokens',-1)):
                records = copy.deepcopy(self.case.records); records[stage]['termination'][field]=value
                with self.subTest(stage=stage,field=field), self.assertRaises(native.HarnessError):
                    self.case.gate(records=records)
            records=copy.deepcopy(self.case.records); records[stage].pop('termination')
            with self.assertRaises(native.HarnessError): self.case.gate(records=records)

    def test_unknown_model_request_ordinal_rejects_without_unhandled_key_error(self):
        for value in ([16],[0],[-1],[1,16]):
            receipts=copy.deepcopy(self.case.receipts); receipts[1]['model_request_ordinals']=value
            with self.assertRaises(native.HarnessError): self.case.gate(receipts=receipts)

    def test_legacy_prompt_stays_exact_and_native_guidance_is_generation_only(self):
        c=self.case
        self.assertEqual(pipeline._legacy_stage_prompt(c.request),
            pipeline.stage_prompt(c.request,delivery_kind=native.LEGACY_DELIVERY))
        generation=pipeline.stage_prompt(c.request)
        self.assertIn('not affirmatively reported', generation)
        self.assertIn('unstated physical comparison', generation)
        self.assertIn('same baseline does not establish equality', generation)
        self.assertIn('stop once that connection is clear', generation)
        for receipt in c.receipts:
            self.assertNotIn('candidate_confirmation_verified',receipt)
        review=pipeline.stage_prompt(c.request,package=c.package,review_id=c.review_id)
        self.assertNotIn('not affirmatively reported',review)
        self.assertNotIn('same baseline does not establish equality',review)
        self.assertNotIn('finish with exactly',review)

    def test_native_review_defines_not_given_checks_without_disclosing_keys(self):
        c=self.case
        prompt=pipeline.stage_prompt(c.request,package=c.package,review_id=c.review_id)
        self.assertIn('including not_given',prompt)
        self.assertIn('paragraph-by-paragraph absence audit',prompt)
        self.assertIn('not all does not mean none',prompt)
        for secret in ('SECRET_RATIONALE_Q1','PRIVATE_NG_JUSTIFICATION','PRIVATE_COMPLETION_EXPLANATION'):
            self.assertNotIn(secret,prompt)
        legacy=pipeline.stage_prompt(c.request,package=c.package,review_id=c.review_id,delivery_kind=native.LEGACY_DELIVERY)
        self.assertEqual(pipeline._legacy_stage_prompt(c.request,package=c.package,review_id=c.review_id),legacy)
        self.assertNotIn('paragraph-by-paragraph absence audit',legacy)
        self.assertNotIn('paragraph-by-paragraph absence audit',pipeline.stage_prompt(c.request))

    def test_not_given_does_not_override_failed_checks_or_incomplete_scope(self):
        c=self.case
        self.assertEqual('not_given',c.review['reading_reviews'][2]['observed_answer'])
        self.assertEqual('eligible_for_caller_publication',ielts_review.validate_review(c.request,c.package,c.review,c.review_id)['status'])
        for change in ('failed_check','missing_paragraph'):
            review=copy.deepcopy(c.review)
            q=review['reading_reviews'][2]
            if change=='failed_check':q['checks']['answerable']['status']='fail'
            else:
                q['not_given_review']['paragraph_reviews'].pop()
                with self.assertRaises(ielts_review.contract.ContractError):
                    ielts_review.validate_review(c.request,c.package,review,c.review_id)
                continue
            result=ielts_review.validate_review(c.request,c.package,review,c.review_id)
            self.assertTrue(result['blockers'])
            self.assertNotEqual('eligible_for_caller_publication',result['status'])


if __name__=='__main__': unittest.main()
