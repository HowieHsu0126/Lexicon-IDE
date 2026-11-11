"""Installed Harness with synthetic provider: one rejected round, feedback, fresh reviews."""
import argparse
import copy
import json
from pathlib import Path

from harness_ielts import write_json_new, sha256_file
from ielts_contract import canonical_sha256
from ielts_candidate import compile_candidate
from ielts_jobs import ExerciseJobs
from ielts_pipeline import normalize_submission, run_job
from probe_ielts_runtime import sse_response, locator_input, bind_review_to_compiled_prompt
from probe_ielts_pipeline import message_texts
from test_ielts_review import review_fixture
from test_ielts_explanation_review import result_fixture as explanation_fixture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--corpus',type=Path,required=True)
    parser.add_argument('--reject-stage',choices=('review','explanation_review'),default='review')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=False,mode=0o700)
    original,candidate,blind=review_fixture()
    submit={k:original[k] for k in ('request_id','article_id','paragraphs','reading_requests','writing_task2_request')}
    submit.update(schema='lexicon.ielts.submit.v1',body='\n\n'.join(p['text'] for p in original['paragraphs']))
    request,body,corpus_sha=normalize_submission(submit,args.corpus)
    candidate.update(request_sha256=canonical_sha256(request),style_source_ids=[s['source_id'] for s in request['style_sources']])
    runs=[];round_number=0;observed=[]
    def factory(mode,frozen,generated,review_id,blind_review):
        nonlocal round_number
        if mode=='generation':round_number+=1
        this_round=round_number
        if mode=='generation':
            value=copy.deepcopy(candidate)
            if this_round==2:value['reading_questions'][0]['explanation']='The approval statement names the equipment directly.'
        elif mode=='review':
            value=bind_review_to_compiled_prompt(copy.deepcopy(blind),generated)
            value.update(review_id=review_id,request_sha256=canonical_sha256(frozen),package_sha256=canonical_sha256(generated))
            if args.reject_stage=='review' and this_round==1:
                value['reading_reviews'][0]['checks']['unique_answer'].update(status='fail',reason='SYNTHETIC_REVISION_FINDING: inspect the candidate wording.')
        else:
            value=explanation_fixture(frozen,generated,blind_review);value['review_id']=review_id
            if args.reject_stage=='explanation_review' and this_round==1:
                value['explanation_reviews'][0]['unit_reviews'][0].update(relation='unsupported',reason='SYNTHETIC_REVISION_FINDING: inspect the explanation wording.',source_ref_indices=[])
        if mode!='explanation_review':value=locator_input(frozen,value,mode)
        compiled=compile_candidate(frozen,value,mode)
        run={'round':this_round,'stage':mode,'calls':0,'expected_candidate_sha256':canonical_sha256(compiled)};runs.append(run)
        def transport(raw):
            payload=json.loads(raw);run['calls']+=1;n=run['calls']
            if n==1:
                text='\n'.join(message_texts(payload['messages']))
                visible='SYNTHETIC_REVISION_FINDING' in text
                assert visible == (this_round==2 and mode=='generation')
                observed.append({'round':this_round,'stage':mode,'prior_feedback_visible':visible})
            if mode=='generation' and n==1:return sse_response(call=('lexicon_ielts_retrieve',{'module':'reading','query':'multiple choice','limit':2}),index=n,model='deepseek-flash')
            if mode=='generation' and n==2:return sse_response(call=('lexicon_ielts_read',{'document_id':'ielts.reading.multiple_choice'}),index=n,model='deepseek-flash')
            if n==(3 if mode=='generation' else 1):return sse_response(call=('lexicon_ielts_validate',{'candidate':value}),index=n,model='deepseek-flash')
            raise AssertionError('unexpected synthetic provider request')
        return transport
    jobs=ExerciseJobs(args.out/'jobs.sqlite');job=jobs.submit(request,body,corpus_sha)
    status=run_job(jobs,job['id'],corpus_path=args.corpus,key='synthetic-probe-no-provider-key',
        work_root=args.out/'work',max_calls=12,timeout=45,transport_factory=factory,
        explanation_model='deepseek-flash',explanation_reasoning='off',quality_revisions=1)
    receipts=jobs.receipts(job['id']);expected=5 if args.reject_stage=='review' else 6
    passed=status['state']=='ready' and len(receipts)==expected and len({r.get('session_id') for r in receipts})==expected
    if passed:
        output=jobs.package(job['id']);write_json_new(args.out/'synthetic-package.json',output)
        passed=(output['provenance']['kind']=='synthetic_runtime_fixture' and output['provenance']['real_provider_request_count']==0 and
            sum(v['calls'] for v in runs)==(9 if expected==5 else 10))
    report={'schema':'lexicon.ielts.quality-revision-probe.v1','status':'passed' if passed else 'failed',
        'actual_installed_harness':True,'reject_stage':args.reject_stage,'runs':runs,'feedback_observations':observed,
        'runtime_receipts':receipts,'job':status,'real_provider_calls':0,'semantic_quality_measured':False,
        'source_sha256':{p.name:sha256_file(p) for p in Path(__file__).resolve().parent.glob('*.py')}}
    write_json_new(args.out/'receipt.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ('runtime_receipts','source_sha256')}))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
