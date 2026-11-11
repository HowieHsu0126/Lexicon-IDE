"""One content revision, complete original evidence, fresh blind review and shared budget."""
import copy
import json
import unittest
from unittest.mock import patch

import harness_ielts as native
import ielts_pipeline as pipeline
import test_ielts_pipeline as fixtures
from ielts_contract import canonical_sha256


class QualityRevisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.PipelineLifecycleTest.setUpClass()

    @classmethod
    def tearDownClass(cls):
        fixtures.PipelineLifecycleTest.tearDownClass()

    def setUp(self):
        self.case=fixtures.PipelineLifecycleTest()
        self.case.setUp();self.addCleanup(self.case.doCleanups)

    def reject_first(self, stage='review', *, always=False):
        c=self.case
        def change(mode,result):
            if mode==stage and (always or c.modes.count(mode)==1):
                if mode=='review':
                    result['reading_reviews'][0]['checks']['unique_answer'].update(status='fail',reason='REPAIR_FINDING_TOKEN: ambiguous comparison')
                else:
                    result['explanation_reviews'][0]['unit_reviews'][0].update(relation='unsupported',reason='REPAIR_FINDING_TOKEN: magnitude not stated',source_ref_indices=[])
            if mode=='generation' and c.modes.count(mode)==2:
                result['reading_questions'][0]['explanation']='The cited source supports the answer without an additional comparison.'
            return result
        c.result_change=change

    def work(self):return self.case.root/'work'/self.case.job['id']

    def gate(self, rejected=None, receipts=None, records=None):
        c=self.case;w=self.work();output=c.jobs.package(c.job['id'])
        prior=json.loads((w/'quality-revision.json').read_text()) if rejected is None else rejected
        final=c.jobs.receipts(c.job['id'])[-3:] if receipts is None else receipts
        usage=json.loads((w/'model-requests.json').read_text())['requests'] if records is None else records
        review=json.loads((w/'revision-01/blind-review.json').read_text())
        explanation=fixtures.explanation_fixture(c.request,output['package'],review)
        explanation['review_id']=json.loads((w/'revision-01/explanation-review-receipt.json').read_text())['review_id']
        return pipeline.verified_output(c.request,output['package'],review,review['review_id'],final,usage,
            explanation_review=explanation,explanation_review_id=explanation['review_id'],rejected_round=prior)

    def test_rejected_answer_round_uses_feedback_and_two_fresh_reviews(self):
        self.reject_first();c=self.case
        result=c.run_pipeline(quality_revisions=1)
        self.assertEqual('ready',result['state'])
        self.assertEqual(['generation','review','generation','review','explanation_review'],c.modes)
        receipts=c.jobs.receipts(c.job['id'])
        self.assertEqual(5,len({r['session_id'] for r in receipts}))
        self.assertEqual(list(range(1,6)),[n for r in receipts for n in r['model_request_ordinals']])
        self.assertIn('REPAIR_FINDING_TOKEN',c.prompts[2])
        for prompt in (c.prompts[0],c.prompts[1],c.prompts[3],c.prompts[4]):self.assertNotIn('REPAIR_FINDING_TOKEN',prompt)
        prior=json.loads((self.work()/'quality-revision.json').read_text())
        self.assertEqual('fail',prior['review']['reading_reviews'][0]['checks']['unique_answer']['status'])
        self.assertNotEqual(prior['package'],c.jobs.package(c.job['id'])['package'])
        context=json.loads((self.work()/'revision-01/generation/inputs/quality-revision-context.json').read_text())
        self.assertEqual(canonical_sha256(context),receipts[2]['quality_revision_sha256'])
        self.assertEqual('synthetic_runtime_fixture',self.gate()['provenance']['kind'])
        c.assert_work_has_no_key()

    def test_rejected_explanation_requires_generation_and_blind_review_again(self):
        self.reject_first('explanation_review');c=self.case
        self.assertEqual('ready',c.run_pipeline(quality_revisions=1)['state'])
        self.assertEqual(['generation','review','explanation_review']*2,c.modes)
        prior=json.loads((self.work()/'quality-revision.json').read_text())
        self.assertIsNotNone(prior['explanation_review'])
        self.assertEqual('unsupported',prior['explanation_review']['explanation_reviews'][0]['unit_reviews'][0]['relation'])
        self.gate()

    def test_success_without_revision_has_original_three_stage_shape(self):
        self.assertEqual('ready',self.case.run_pipeline(quality_revisions=1)['state'])
        self.assertEqual(3,len(self.case.modes))
        self.assertFalse((self.work()/'quality-revision.json').exists())

    def test_second_rejection_stops_without_third_candidate(self):
        self.reject_first(always=True);c=self.case
        self.assertEqual('review_rejected',c.run_pipeline(quality_revisions=1)['error'])
        self.assertEqual(2,c.modes.count('generation'));self.assertEqual(2,c.modes.count('review'))
        self.assertEqual(4,len(c.jobs.receipts(c.job['id'])));c.unavailable()

    def test_low_remaining_model_budget_preserves_original_failure(self):
        self.reject_first();c=self.case
        self.assertEqual('review_rejected',c.run_pipeline(quality_revisions=1,max_calls=6)['error'])
        self.assertEqual(['generation','review'],c.modes)
        self.assertFalse((self.work()/'quality-revision.json').exists())

    def test_invalid_policy_cannot_claim_a_job(self):
        c=self.case
        for value in (True,2,-1,None,'1'):
            with self.assertRaises(native.HarnessError):c.run_pipeline(quality_revisions=value)
            self.assertEqual('queued',c.jobs.get(c.job['id'])['state'])
        with self.assertRaises(native.HarnessError):c.run_pipeline(quality_revisions=1,delivery_kind=native.LEGACY_DELIVERY)

    def test_unconfirmed_provider_response_does_not_allow_a_revision(self):
        self.reject_first();c=self.case
        def changed(mode,receipt):
            if mode=='review':
                route=c.routes[-1];row=route.budget.snapshot()[-1]
                route.budget.completion_unknown(row['ordinal'],200,.1,False,'response_deadline')
        c.receipt_change=changed
        self.assertEqual('provider_completion_unknown',c.run_pipeline(quality_revisions=1)['error'])
        self.assertEqual(['generation','review'],c.modes)

    def test_auth_failure_and_invalid_structure_never_trigger_content_revision(self):
        c=self.case
        self.reject_first()
        def changed(mode,receipt):
            if mode=='review':
                route=c.routes[-1];row=route.budget.snapshot()[-1]
                route.budget.record(row['ordinal'],401,.1,100,False,row['termination'])
        c.receipt_change=changed
        self.assertEqual('model_request_incomplete',c.run_pipeline(quality_revisions=1)['error'])
        self.assertEqual(['generation','review'],c.modes)
        self.assertFalse((self.work()/'quality-revision.json').exists())

    def test_malformed_review_cannot_be_used_as_revision_feedback(self):
        c=self.case
        def change(mode,result):
            if mode=='review':result['reading_reviews'].pop()
            return result
        c.result_change=change
        self.assertEqual('failed',c.run_pipeline(quality_revisions=1)['state'])
        self.assertEqual(['generation','review'],c.modes)
        self.assertFalse((self.work()/'quality-revision.json').exists())

    def test_cancel_after_review_does_not_start_revision(self):
        self.reject_first();c=self.case;c.cancel_stage='review'
        self.assertEqual('cancelled',c.run_pipeline(quality_revisions=1)['state'])
        self.assertEqual(['generation','review'],c.modes)

    def test_deadline_cannot_reset_for_revision(self):
        self.reject_first();c=self.case
        original_change=c.result_change
        clock=[0.0]
        def change(mode,result):
            value=original_change(mode,result)
            if mode=='review':clock[0]=31.0
            return value
        c.result_change=change
        with patch.object(pipeline.time,'monotonic',side_effect=lambda:clock[0]):
            result=c.run_pipeline(quality_revisions=1,timeout=10)
        self.assertEqual('job_deadline_exhausted',result['error'])
        self.assertEqual(['generation','review'],c.modes)

    def test_publication_rejects_changed_original_receipts_inputs_and_omitted_cost(self):
        self.reject_first();self.assertEqual('ready',self.case.run_pipeline(quality_revisions=1)['state'])
        original=json.loads((self.work()/'quality-revision.json').read_text())
        mutations=[lambda p:p['receipts'][0].update(process_reaped=False),
                   lambda p:p['receipts'][0].update(prompt_sha256='0'*64),
                   lambda p:p['review']['reading_reviews'][0]['checks']['unique_answer'].update(status='pass'),
                   lambda p:p['package']['reading_questions'][0].update(explanation='Changed original'),
                   lambda p:p['receipts'].pop(),lambda p:p.update(unchecked_context={})]
        for mutate in mutations:
            invalid=copy.deepcopy(original);mutate(invalid)
            with self.assertRaises(ValueError):self.gate(rejected=invalid)
        receipts=self.case.jobs.receipts(self.case.job['id'])[-3:]
        receipts[0]['quality_revision_sha256']='0'*64
        with self.assertRaises(ValueError):self.gate(receipts=receipts)
        records=json.loads((self.work()/'model-requests.json').read_text())['requests'][2:]
        with self.assertRaises(ValueError):self.gate(records=records)


if __name__=='__main__':unittest.main()
