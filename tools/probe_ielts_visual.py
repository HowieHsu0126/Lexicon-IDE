"""Installed Harness Task 1 compatibility probe, synthetic provider and no network."""
import argparse,copy,json
from pathlib import Path
from harness_ielts import write_json_new,sha256_file
from ielts_contract import canonical_sha256
from ielts_visual import compile_task
from ielts_candidate import compile_candidate
from ielts_jobs import ExerciseJobs
from ielts_pipeline import normalize_submission,run_job
from probe_ielts_runtime import sse_response,locator_input,bind_review_to_compiled_prompt
from test_ielts_visual import visual_fixture
from test_ielts_explanation_review import result_fixture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--malformed-generation-once',action='store_true')
    parser.add_argument('--out',type=Path,required=True);parser.add_argument('--corpus',type=Path,required=True)
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=False,mode=0o700)
    request,package,review=visual_fixture()
    body='\n\n'.join(p['text'] for p in request['paragraphs'])
    submit={k:request[k] for k in ['request_id','article_id','paragraphs','reading_requests','writing_task2_request','writing_task1_request']}
    submit.update(schema='lexicon.ielts.submit.v1',body=body)
    frozen,body,corpus_sha=normalize_submission(submit,args.corpus)
    package.update(request_sha256=canonical_sha256(frozen),body_hash=frozen['body_hash'],style_source_ids=[s['source_id'] for s in frozen['style_sources']])
    package['writing_task1']['task']=compile_task(frozen,'w1',package['writing_task1']['task']['visual'])
    runs=[]
    def factory(mode,request,generated,review_id,blind):
        if mode=='generation':
            value=copy.deepcopy(package)
            value['writing_task1']={'status':'available','visual':value['writing_task1']['task']['visual']}
        elif mode=='review':
            value=bind_review_to_compiled_prompt(copy.deepcopy(review),generated)
            value.update(review_id=review_id,request_sha256=canonical_sha256(request),package_sha256=canonical_sha256(generated))
        else:
            value=result_fixture(request,generated,blind);value['review_id']=review_id
        if mode!='explanation_review':value=locator_input(request,value,mode)
        calls=0
        def transport(raw):
            nonlocal calls
            calls+=1;runs.append({'stage':mode,'request':calls})
            if mode=='generation' and calls==1:return sse_response(call=('lexicon_ielts_retrieve',{'module':'writing','query':'task 1','limit':2}),index=calls,model='deepseek-flash')
            if mode=='generation' and calls==2:return sse_response(call=('lexicon_ielts_read',{'document_id':'ielts.writing.academic_task_1'}),index=calls,model='deepseek-flash')
            if mode=='generation' and calls==3 and args.malformed_generation_once:
                from probe_ielts_pipeline import malformed_validate_response
                return malformed_validate_response(value,index=calls,model='deepseek-flash')
            if calls==((4 if args.malformed_generation_once else 3) if mode=='generation' else 1):return sse_response(call=('lexicon_ielts_validate',{'candidate':value}),index=calls,model='deepseek-flash')
            raise AssertionError('Unexpected provider request')
        return transport
    jobs=ExerciseJobs(args.out/'jobs.sqlite');job=jobs.submit(frozen,body,corpus_sha)
    status=run_job(jobs,job['id'],corpus_path=args.corpus,key='synthetic-no-provider-key',work_root=args.out/'work',
        max_calls=8,timeout=60,transport_factory=factory,explanation_model='deepseek-flash',explanation_reasoning='off')
    passed=status['state']=='ready'
    if passed:
        output=jobs.package(job['id']);write_json_new(args.out/'synthetic-package.json',output)
        passed=output['package']['writing_task1']['status']=='available' and output['provenance']['real_provider_request_count']==0
    report={'schema':'lexicon.ielts.visual-native-probe.v1','status':'passed' if passed else 'failed','job':status,
        'actual_installed_harness':True,'real_provider_calls':0,'runs':runs,'runtime_receipts':jobs.receipts(job['id']),
        'semantic_quality_measured':False,'source_sha256':{p.name:sha256_file(p) for p in Path(__file__).parent.glob('*.py')}}
    write_json_new(args.out/'receipt.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ['runtime_receipts','source_sha256']}))
    return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
