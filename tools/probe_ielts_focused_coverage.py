"""Actual installed Harness, synthetic responses: reject missing coverage, retain blind review independence."""
import argparse
import copy
import json
from pathlib import Path

from harness_ielts import write_json_new, sha256_file
from ielts_contract import canonical_sha256
from ielts_jobs import ExerciseJobs
from ielts_pipeline import normalize_submission, run_job, stage_prompt
from ielts_visual import compile_task
from probe_ielts_runtime import sse_response, generation_draft_input, bind_review_to_compiled_prompt
from test_ielts_focused_coverage import focused_fixture
from test_ielts_explanation_review import result_fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    args = parser.parse_args(); args.out.mkdir(parents=True, exist_ok=False, mode=0o700)
    request, package, blind = focused_fixture()
    body = '\n\n'.join(p['text'] for p in request['paragraphs'])
    submit = {k:request[k] for k in ['request_id','article_id','paragraphs','reading_requests','writing_task1_request','writing_task2_request']}
    submit.update(schema='lexicon.ielts.submit.v1', body=body)
    request, body, corpus_sha = normalize_submission(submit, args.corpus)
    package.update(request_sha256=canonical_sha256(request), body_hash=request['body_hash'],
                   style_source_ids=[s['source_id'] for s in request['style_sources']])
    package['writing_task1']['task'] = compile_task(request, 'w1', package['writing_task1']['task']['visual'])
    calls=[]; observed={'compiler_rejection_visible_to_generator':False,'coverage_guidance_hidden_from_blind_review':False}
    def factory(mode, frozen, generated, review_id, reviewed):
        if mode=='generation':
            value=generation_draft_input(package)
            value['writing_task1']={'status':'available','visual':value['writing_task1']['task']['visual']}
            deficient=copy.deepcopy(value)
            deficient['reading_questions'][2]=dict(copy.deepcopy(deficient['reading_questions'][1]), question_id='q3',
                prompt='The writer opposes investment in clean energy.')
        elif mode=='review':
            value=bind_review_to_compiled_prompt(blind,generated)
            value.update(review_id=review_id,request_sha256=canonical_sha256(frozen),package_sha256=canonical_sha256(generated))
            assert 'FOCUSED CONTRAST PRACTICE' not in stage_prompt(frozen,package=generated,review_id=review_id)
        else:
            value=result_fixture(frozen,generated,reviewed);value['review_id']=review_id
        counter=0
        def transport(raw):
            nonlocal counter
            counter+=1;calls.append({'stage':mode,'call':counter})
            if mode=='generation':
                if counter==1:return sse_response(call=('lexicon_ielts_retrieve',{'module':'reading','query':'information','limit':2}),index=counter)
                if counter==2:return sse_response(call=('lexicon_ielts_read',{'document_id':'ielts.reading.identifying_information'}),index=counter)
                if counter==3:return sse_response(call=('lexicon_ielts_validate',{'candidate':deficient}),index=counter)
                if counter==4:
                    assert b'candidate_focused_response_coverage' in raw
                    observed['compiler_rejection_visible_to_generator']=True
                    return sse_response(call=('lexicon_ielts_validate',{'candidate':value}),index=counter)
            else:
                assert counter==1
                if mode=='review':
                    assert b'FOCUSED CONTRAST PRACTICE' not in raw
                    observed['coverage_guidance_hidden_from_blind_review']=True
                return sse_response(call=('lexicon_ielts_validate',{'candidate':value}),index=counter)
            raise AssertionError('Unexpected provider request')
        return transport
    jobs=ExerciseJobs(args.out/'jobs.sqlite');job=jobs.submit(request,body,corpus_sha)
    status=run_job(jobs,job['id'],corpus_path=args.corpus,key='synthetic-no-network-key',work_root=args.out/'work',
                   max_calls=6,timeout=90,transport_factory=factory,explanation_model='deepseek-flash',explanation_reasoning='off')
    output=jobs.package(job['id']) if status['state']=='ready' else None
    passed=output is not None and len(calls)==6 and all(observed.values())
    if output:
        assert output['provenance']['real_provider_request_count']==0
        assert {q['answer'] for q in output['package']['reading_questions']}=={'true','false','not_given'}
        write_json_new(args.out/'synthetic-package.json',output)
    report={'schema':'lexicon.ielts.focused-native-probe.v1','status':'passed' if passed else 'failed','job':status,
            'actual_installed_harness':True,'real_provider_calls':0,'synthetic_calls':calls,'checks':observed,
            'runtime_receipts':jobs.receipts(job['id']),'semantic_quality_measured':False,
            'source_sha256':{p.name:sha256_file(p) for p in Path(__file__).parent.glob('*.py')}}
    write_json_new(args.out/'receipt.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ['runtime_receipts','source_sha256']}))
    return 0 if passed else 1

if __name__=='__main__':raise SystemExit(main())
