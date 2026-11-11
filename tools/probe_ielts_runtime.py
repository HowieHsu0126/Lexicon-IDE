"""Actual installed Harness + synthetic in-memory Messages provider. Zero online calls."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys

from harness_model_proxy import ModelBudget, ModelRoute
from harness_ielts import (GENERATION_MODELS, prepare_stage, run_native_stage, write_json_new,
                          NATIVE_DELIVERY, LEGACY_DELIVERY, DELIVERY_KINDS, validate_candidate_delivery_receipt)
from ielts_contract import canonical_sha256, validate_package
from ielts_candidate import compile_candidate
from ielts_pipeline import stage_prompt
from ielts_corpus import load, style_source_bundle
from test_ielts_contract import fixture


SYNTHETIC_WRITING_BACKGROUND = 'Public budgets support several competing priorities.'
SYNTHETIC_WRITING_TARGET = 'Public funds should prioritise clean energy.'
SYNTHETIC_WRITING_INSTRUCTION = 'To what extent do you agree or disagree with this statement?'


def generation_draft_input(value):
    """Adapt only this probe's authored fixture; never infer a real prompt target."""
    raw = copy.deepcopy(value)
    writing = raw['writing_task2']
    assert 'prompt' in writing and 'prompt_draft' not in writing
    del writing['prompt']
    writing['prompt_draft'] = {'background': SYNTHETIC_WRITING_BACKGROUND,
                               'target_statement': SYNTHETIC_WRITING_TARGET}
    return raw


def synthetic_compiled_writing_prompt():
    return '\n\n'.join((SYNTHETIC_WRITING_BACKGROUND, SYNTHETIC_WRITING_TARGET, SYNTHETIC_WRITING_INSTRUCTION))


def bind_review_to_compiled_prompt(value, generated):
    """Synthetic blind-review observations bind only the compiled public prompt."""
    assert set(generated['writing_task2']) == {'task_id', 'task_type', 'prompt', 'minimum_words', 'suggested_minutes', 'source_refs'}
    prompt = generated['writing_task2']['prompt']
    assert prompt == synthetic_compiled_writing_prompt()
    result = copy.deepcopy(value)
    alignment = result['writing_task2_review']['instruction_alignment']
    alignment['instruction_quote'] = SYNTHETIC_WRITING_INSTRUCTION
    alignment['target_readings'] = [{'target_quote': SYNTHETIC_WRITING_TARGET,
                                     'interpretation': 'Whether public funds should prioritise clean energy.'}]
    result['package_sha256'] = canonical_sha256(generated)
    return result


def locator_input(request, value, mode):
    """Exercise compilation on synthetic data without replacing ambiguous refs."""
    raw = generation_draft_input(value) if mode == 'generation' else copy.deepcopy(value)
    items = 'reading_questions' if mode == 'generation' else 'reading_reviews'
    writing = 'writing_task2' if mode == 'generation' else 'writing_task2_review'
    raw[items][3]['source_refs'] = [{'paragraph_id': request['paragraphs'][0]['paragraph_id'],
                                    'quote': '🔬 The council approved solar panels.'}]
    raw[writing]['source_refs'] = [{k: ref[k] for k in ('paragraph_id', 'quote')}
                                   for ref in raw[writing]['source_refs']]
    if mode == 'review':
        scope = raw['reading_reviews'][2]['not_given_review']['paragraph_reviews'][1]
        scope['context_refs'] = [{k: ref[k] for k in ('paragraph_id', 'quote')} for ref in scope['context_refs']]
    return raw


def sse_response(*, call=None, text=None, index=1, model='deepseek-flash'):
    events = []
    def emit(kind, data): events.append('event: ' + kind + '\ndata: ' + json.dumps(data, ensure_ascii=False) + '\n\n')
    emit('message_start', {'type':'message_start','message':{'id':'msg-offline-'+str(index),'type':'message','role':'assistant',
        'content':[],'model':model,'stop_reason':None,'stop_sequence':None,'usage':{'input_tokens':12,'output_tokens':0}}})
    if call:
        name, arguments = call
        emit('content_block_start', {'type':'content_block_start','index':0,'content_block':{'type':'tool_use','id':'tool-offline-'+str(index),'name':name,'input':{}}})
        emit('content_block_delta', {'type':'content_block_delta','index':0,'delta':{'type':'input_json_delta','partial_json':json.dumps(arguments,ensure_ascii=False)}})
    else:
        emit('content_block_start', {'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}})
        emit('content_block_delta', {'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':text}})
    emit('content_block_stop', {'type':'content_block_stop','index':0})
    emit('message_delta', {'type':'message_delta','delta':{'stop_reason':'tool_use' if call else 'end_turn','stop_sequence':None},'usage':{'output_tokens':30}})
    emit('message_stop', {'type':'message_stop'})
    return 200, 'text/event-stream', ''.join(events).encode('utf-8')


def tool_confirmation(payload, expected):
    """The synthetic final repeats the tool receipt, rather than inventing it."""
    def find(value):
        if isinstance(value, str):
            try: return find(json.loads(value))
            except (ValueError, RecursionError): return None
        if isinstance(value, list):
            for item in reversed(value):
                result = find(item)
                if result: return result
        if isinstance(value, dict):
            if 'candidate_sha256' in value and (value.get('status') == 'structurally_valid' or value.get('structurally_valid') is True):
                return value['candidate_sha256']
            for key in ('content', 'text'):
                result = find(value.get(key))
                if result: return result
        return None
    digest = find(payload['messages'])
    assert digest == canonical_sha256(expected), 'Native tool must return the exact validated-candidate digest'
    return {'candidate_sha256': digest}


def confirmation_text(payload, expected, *, prefix=False):
    confirmed = tool_confirmation(payload, expected)
    text = json.dumps(confirmed, separators=(',', ':')) if prefix else json.dumps(confirmed)
    if prefix:
        return ('The candidate compiled successfully (structurally valid). The reference readings confirm all '
                'question types and formats are correctly aligned. The candidate was saved with digest `'
                + confirmed['candidate_sha256'] + '`.\n\n' + text)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--generation-model', choices=tuple(GENERATION_MODELS), default='flash')
    parser.add_argument('--confirmation-prefix', action='store_true')
    parser.add_argument('--delivery-kind', choices=DELIVERY_KINDS, default=NATIVE_DELIVERY)
    args = parser.parse_args()
    if args.confirmation_prefix and args.delivery_kind != LEGACY_DELIVERY:
        parser.error('--confirmation-prefix requires --delivery-kind assistant_digest_confirmation')
    args.out.mkdir(parents=True, exist_ok=False)
    generation_model = GENERATION_MODELS[args.generation_model]
    request, package, _ = fixture()
    corpus_sha = hashlib.sha256(args.corpus.read_bytes()).hexdigest()
    corpus = load(args.corpus, expected_sha256=corpus_sha)
    style = style_source_bundle(corpus, ['ielts.reading.multiple_choice', 'ielts.writing.academic_task_2'])
    request['style_sources'], request['style_blueprint_sha256'] = style['style_sources'], style['style_blueprint_sha256']
    package['request_sha256'] = canonical_sha256(request)
    package['style_source_ids'] = [s['source_id'] for s in request['style_sources']]
    validate_package(request, package)
    raw_candidate = locator_input(request, package, 'generation')
    package = compile_candidate(request, raw_candidate, 'generation')
    validate_package(request, package)
    assert package['writing_task2']['prompt'] == synthetic_compiled_writing_prompt()
    assert 'prompt_draft' not in package['writing_task2'] and 'prompt' not in raw_candidate['writing_task2']
    write_json_new(args.out/'request.json', request)
    calls = []
    def transport(raw):
        payload = json.loads(raw)
        assert payload['model'] == generation_model
        assert payload.get('max_tokens') == 8192
        assert payload.get('thinking') == {'type': 'disabled'}
        assert payload.get('output_config') is None
        calls.append({'ordinal':len(calls)+1,'message_count':len(payload['messages']),
                      'tools':[t['name'] for t in payload.get('tools', [])]})
        n = len(calls)
        if n == 1: return sse_response(call=('lexicon_ielts_retrieve', {'module':'reading','query':'multiple choice','limit':2}), index=n, model=generation_model)
        if n == 2: return sse_response(call=('lexicon_ielts_read', {'document_id':'ielts.reading.multiple_choice'}), index=n, model=generation_model)
        if n == 3: return sse_response(call=('lexicon_ielts_validate', {'candidate':raw_candidate}), index=n, model=generation_model)
        if n == 4 and args.delivery_kind == LEGACY_DELIVERY:
            return sse_response(text=confirmation_text(payload, package, prefix=args.confirmation_prefix), index=n, model=generation_model)
        raise AssertionError('Unexpected provider call after final validation')
    expected_calls = 3 if args.delivery_kind == NATIVE_DELIVERY else 4
    budget = ModelBudget(expected_calls)
    with ModelRoute('not-a-real-credential-offline-probe', model=generation_model, transport=transport, budget=budget) as route:
        prepared = prepare_stage(args.out/'generation', request, args.corpus, route, mode='generation', delivery_kind=args.delivery_kind)
        value, receipt = run_native_stage(prepared, stage_prompt(request,delivery_kind=args.delivery_kind), mode='generation',timeout=45)
    try:
        validate_candidate_delivery_receipt(receipt)
        passed = value == package and len(calls) == expected_calls
    except ValueError:
        passed = False
    report={'schema':'lexicon.ielts.native-offline-probe.v1','status':'passed' if passed else 'failed',
            'actual_harness_runtime':True,'real_provider_calls':0,'synthetic_provider_calls':len(calls),
            'delivery_kind':args.delivery_kind,
            'raw_candidate_sha256':canonical_sha256(raw_candidate),'compiled_candidate_sha256':canonical_sha256(package),
            'compiled_artifact_differs_from_input':canonical_sha256(raw_candidate)!=canonical_sha256(package),
            'writing_prompt_protocol': {'native_draft_only': True, 'compiled_prompt_only': True,
                                        'fixed_instruction_verified': True, 'synthetic_target': True},
            'runtime_receipt':receipt,'calls':calls,'budget':budget.records}
    write_json_new(args.out/'probe-receipt.json',report)
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 0 if report['status']=='passed' else 1


if __name__=='__main__': raise SystemExit(main())
