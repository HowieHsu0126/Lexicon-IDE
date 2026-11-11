"""Exercise all three installed Harness sessions with synthetic responses, offline."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

from harness_ielts import (GENERATION_MODELS, REVIEW_MODELS, REASONING_EFFORTS, HARNESS_TOOL_NAMES,
                          tool_json_repair_prompt, required_stage_tools, stage_execution_limits, sha256_file, write_json_new,
                          DELIVERY_KINDS, NATIVE_DELIVERY, LEGACY_DELIVERY, validate_candidate_delivery_receipt)
from ielts_contract import canonical_sha256, validate_package
from ielts_candidate import compile_candidate
from ielts_explanation_review import validate_explanation_review
from ielts_jobs import ExerciseJobs
from ielts_pipeline import normalize_submission, run_job, stage_prompt
from probe_ielts_runtime import (sse_response, confirmation_text, locator_input,
                                 synthetic_compiled_writing_prompt, bind_review_to_compiled_prompt)
from test_ielts_review import review_fixture
from test_ielts_explanation_review import result_fixture as explanation_fixture


def malformed_validate_response(candidate, *, index, model):
    """Break only tool arguments; retain the complete HTTP 200/SSE envelope."""
    status, mime, body = sse_response(call=('lexicon_ielts_validate', {'candidate': candidate}),
                                    index=index, model=model)
    lines, modified = [], 0
    for line in body.decode().splitlines():
        if line.startswith('data: '):
            event = json.loads(line[6:])
            if (event.get('type') == 'content_block_delta' and
                    event.get('delta', {}).get('type') == 'input_json_delta'):
                event['delta']['partial_json'] = event['delta']['partial_json'][:-1]
                modified += 1
            line = 'data: ' + json.dumps(event, ensure_ascii=False)
        lines.append(line)
    assert modified == 1
    return status, mime, ('\n'.join(lines) + '\n').encode()


def message_texts(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from message_texts(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from message_texts(item)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--generation-model', choices=tuple(GENERATION_MODELS), default='flash')
    parser.add_argument('--confirmation-prefix', action='store_true')
    parser.add_argument('--delivery-kind', choices=DELIVERY_KINDS, default=NATIVE_DELIVERY)
    parser.add_argument('--review-lookups', action='store_true',
                        help='Also exercise optional retrieve/read in both reviews; default uses only their required validate tool')
    malformed = parser.add_mutually_exclusive_group()
    malformed.add_argument('--malformed-review-once', action='store_true',
                           help='Synthetic review sends invalid tool JSON once, then uses native same-session correction')
    malformed.add_argument('--malformed-review-after-read', action='store_true',
                           help='Synthetic review completes one article read before invalid tool JSON and same-session correction')
    parser.add_argument('--review-model', choices=tuple(REVIEW_MODELS), default='flash')
    parser.add_argument('--review-reasoning', choices=REASONING_EFFORTS, default='off')
    parser.add_argument('--explanation-model', choices=tuple(REVIEW_MODELS), default='pro')
    parser.add_argument('--explanation-reasoning', choices=REASONING_EFFORTS, default='high')
    args = parser.parse_args()
    if args.confirmation_prefix and args.delivery_kind != LEGACY_DELIVERY:
        parser.error('--confirmation-prefix requires --delivery-kind assistant_digest_confirmation')
    if args.malformed_review_after_read and args.review_lookups:
        parser.error('--malformed-review-after-read uses exactly one review read; omit --review-lookups')
    args.out.mkdir(parents=True, exist_ok=False, mode=0o700)
    source, package, review = review_fixture()
    body = '\n\n'.join(p['text'] for p in source['paragraphs'])
    submit = {key: source[key] for key in ('request_id', 'article_id', 'paragraphs',
                                          'reading_requests', 'writing_task2_request')}
    submit.update(schema='lexicon.ielts.submit.v1', body=body)
    request, body, corpus_hash = normalize_submission(submit, args.corpus)
    package['request_sha256'] = canonical_sha256(request)
    package['style_source_ids'] = [item['source_id'] for item in request['style_sources']]
    validate_package(request, package)
    calls = {'generation': 0, 'review': 0, 'explanation_review': 0}
    observed_settings = {'generation': [], 'review': [], 'explanation_review': []}
    recovery_context = []
    compiled_bindings = {}
    explanation_protocol = {}
    writing_prompt_protocol = {}
    source_files = ('harness_ielts.py', 'harness_model_proxy.py', 'harness_ielts_plugin.mjs', 'ielts_pipeline.py',
                    'ielts_candidate.py', 'ielts_review.py', 'ielts_json_schema.py',
                    'ielts_explanation_review.py', 'probe_ielts_runtime.py', 'probe_ielts_pipeline.py')
    source_hashes = {name: sha256_file(Path(__file__).resolve().parent / name) for name in source_files}
    stage_settings = {
        'generation': (GENERATION_MODELS[args.generation_model], 'off'),
        'review': (REVIEW_MODELS[args.review_model], args.review_reasoning),
        'explanation_review': (REVIEW_MODELS[args.explanation_model], args.explanation_reasoning),
    }

    def transport_factory(mode, frozen_request, generated, review_id, blind_review):
        original_prompt = None
        if mode == 'review' and (args.malformed_review_once or args.malformed_review_after_read):
            original_prompt = stage_prompt(frozen_request, package=generated, review_id=review_id, delivery_kind=args.delivery_kind)
            write_json_new(args.out / 'synthetic-review-prompt.json', {
                'prompt': original_prompt, 'sha256': hashlib.sha256(original_prompt.encode()).hexdigest()})
        value = copy.deepcopy(package if mode == 'generation' else review)
        if mode == 'review':
            value = bind_review_to_compiled_prompt(value, generated)
            value.update(review_id=review_id, request_sha256=canonical_sha256(frozen_request),
                         package_sha256=canonical_sha256(generated))
            visible_prompt = stage_prompt(frozen_request, package=generated, review_id=review_id, delivery_kind=args.delivery_kind)
            visible, _ = json.JSONDecoder().raw_decode(visible_prompt.split('\n\nANSWER-BLIND CANDIDATE DATA:\n', 1)[1])
            assert visible['writing_task2']['prompt'] == generated['writing_task2']['prompt']
            assert '"prompt_draft"' not in visible_prompt and '"target_statement"' not in visible_prompt
            writing_prompt_protocol['blind_review_compiled_prompt_only'] = True
        if mode == 'explanation_review':
            value = explanation_fixture(frozen_request, generated, blind_review)
            value['review_id'] = review_id
        if mode == 'explanation_review':
            for item in value['explanation_reviews']:
                item['source_refs'] = [{key: ref[key] for key in ('paragraph_id', 'quote')} for ref in item['source_refs']]
        else:
            value = locator_input(frozen_request, value, mode)
        compiled = compile_candidate(frozen_request, value, mode)
        if mode == 'generation':
            assert 'prompt' not in value['writing_task2'] and 'prompt_draft' in value['writing_task2']
            assert 'prompt_draft' not in compiled['writing_task2']
            assert compiled['writing_task2']['prompt'] == synthetic_compiled_writing_prompt()
            writing_prompt_protocol.update(native_draft_only=True, compiled_prompt_only=True,
                                          fixed_instruction_verified=True, synthetic_target=True)
        compiled_bindings[mode] = {'raw_candidate_sha256': canonical_sha256(value),
                                  'compiled_candidate_sha256': canonical_sha256(compiled)}
        if mode == 'explanation_review':
            # This exercises coverage/aggregation only. The transport is synthetic
            # and does not establish source-faithful or model-generated judgments.
            audit = validate_explanation_review(frozen_request, generated, blind_review, compiled, review_id)
            explanation_protocol.update(schema=compiled['schema'], receipt_schema=audit['schema'],
                                        unit_count=sum(len(item['unit_results']) for item in audit['explanation_results']),
                                        semantic_assessment_performed=False)

        def transport(raw):
            payload = json.loads(raw)
            expected_model, expected_reasoning = stage_settings[mode]
            assert payload['model'] == expected_model
            assert payload.get('max_tokens') == stage_execution_limits(expected_reasoning)['token_limit']
            assert payload.get('thinking') == {'type': 'disabled' if expected_reasoning == 'off' else 'enabled'}
            assert payload.get('output_config') == (None if expected_reasoning == 'off' else {'effort': expected_reasoning})
            calls[mode] += 1
            ordinal = calls[mode]
            if mode == 'review' and ordinal == 1:
                assert visible_prompt in list(message_texts(payload['messages']))
                writing_prompt_protocol['blind_review_native_prompt_verified'] = True
            observed_settings[mode].append({'model': payload['model'], 'thinking': payload.get('thinking'),
                'output_config': payload.get('output_config'), 'max_tokens': payload['max_tokens'],
                'stage_request_ordinal': ordinal, 'shared_request_ordinal': sum(calls.values())})

            def observe_recovery_context():
                actual_texts = list(message_texts(payload['messages']))
                observation = {'original_prompt_retained': original_prompt in actual_texts,
                               'fixed_repair_prompt_present': tool_json_repair_prompt(args.delivery_kind) in actual_texts,
                               'original_prompt_sha256': hashlib.sha256(original_prompt.encode()).hexdigest(),
                               'stage_request_ordinal': ordinal,
                               'shared_request_ordinal': sum(calls.values())}
                assert observation['original_prompt_retained'] and observation['fixed_repair_prompt_present']
                recovery_context.append(observation)

            if args.malformed_review_after_read and mode == 'review':
                if ordinal == 1:
                    return sse_response(call=('lexicon_ielts_read', {
                        'document_id': 'article:' + frozen_request['paragraphs'][0]['paragraph_id']}),
                        index=ordinal, model=expected_model)
                if ordinal == 2:
                    return malformed_validate_response(value, index=ordinal, model=expected_model)
                if ordinal == 3:
                    observe_recovery_context()
                    return sse_response(call=('lexicon_ielts_validate', {'candidate': value}),
                                        index=ordinal, model=expected_model)
                if ordinal == 4 and args.delivery_kind == LEGACY_DELIVERY:
                    return sse_response(text=confirmation_text(payload, compiled, prefix=args.confirmation_prefix),
                                        index=ordinal, model=expected_model)
                raise AssertionError('Read-then-malformed review must finish within four synthetic requests')
            if args.malformed_review_once and mode == 'review':
                if ordinal == 1:
                    return malformed_validate_response(value, index=ordinal, model=expected_model)
                if ordinal == 2:
                    observe_recovery_context()
                ordinal -= 1
            lookups = mode == 'generation' or args.review_lookups
            if lookups and ordinal == 1:
                return sse_response(call=('lexicon_ielts_retrieve', {
                    'module': 'reading', 'query': 'multiple choice', 'limit': 2}), index=ordinal, model=expected_model)
            if lookups and ordinal == 2:
                return sse_response(call=('lexicon_ielts_read', {
                    'document_id': 'ielts.reading.multiple_choice'}), index=ordinal, model=expected_model)
            if ordinal == (3 if lookups else 1):
                return sse_response(call=('lexicon_ielts_validate', {'candidate': value}), index=ordinal, model=expected_model)
            if ordinal == (4 if lookups else 2) and args.delivery_kind == LEGACY_DELIVERY:
                return sse_response(text=confirmation_text(payload, compiled, prefix=args.confirmation_prefix), index=ordinal, model=expected_model)
            raise AssertionError('Synthetic provider response budget exceeded for the selected tool path')
        return transport

    jobs = ExerciseJobs(args.out / 'jobs.sqlite')
    job = jobs.submit(request, body, corpus_hash)
    status = run_job(jobs, job['id'], corpus_path=args.corpus,
                     key='synthetic-probe-not-a-provider-credential', work_root=args.out / 'work',
                     max_calls=12, timeout=45, transport_factory=transport_factory, delivery_kind=args.delivery_kind,
                     generation_model=GENERATION_MODELS[args.generation_model],
                     review_model=REVIEW_MODELS[args.review_model], review_reasoning=args.review_reasoning,
                     explanation_model=REVIEW_MODELS[args.explanation_model], explanation_reasoning=args.explanation_reasoning)
    result = jobs.package(job['id']) if status['state'] == 'ready' else None
    receipts = jobs.receipts(job['id'])
    expected_calls = {'generation': 4, 'review': 4 if args.review_lookups else 2,
                      'explanation_review': 4 if args.review_lookups else 2}
    if args.malformed_review_once: expected_calls['review'] += 1
    if args.malformed_review_after_read: expected_calls['review'] = 4
    if args.delivery_kind == NATIVE_DELIVERY:
        expected_calls = {mode: count - 1 for mode,count in expected_calls.items()}
    def expected_tool_counts(mode):
        used = HARNESS_TOOL_NAMES if mode == 'generation' or args.review_lookups else required_stage_tools(mode)
        return {name: int(name in used) for name in HARNESS_TOOL_NAMES}
    recovery_evidence = None
    recovery_passed = not (args.malformed_review_once or args.malformed_review_after_read)
    review_receipt = next((r for r in receipts if r['stage'] == 'review'), {})
    if not recovery_passed and len(review_receipt.get('recovery', {}).get('attempts', [])) == 2:
        recovery = review_receipt['recovery']
        attempts = recovery['attempts']
        local = [a['receipt'].get('tool_receipt', {}) for a in attempts]
        expected_first = {name: int(args.malformed_review_after_read and name == 'lexicon_ielts_read')
                          for name in HARNESS_TOOL_NAMES}
        first_requests = 2 if args.malformed_review_after_read else 1
        review_start = expected_calls['generation'] + 1
        expected_ordinals = [list(range(review_start, review_start + first_requests)),
                             list(range(review_start + first_requests, review_start + expected_calls['review']))]
        recovery_evidence = {
            'same_session': len({a['receipt'].get('session_id') for a in attempts}) == 1,
            'session_id': review_receipt.get('session_id'),
            'original_prompt_sha256': recovery.get('original_prompt_sha256'),
            'continuation_sha256': recovery.get('continuation_sha256'),
            'shared_tool_limit': 24,
            'stage_total_allowed': sum(p.get('allowedCount', 0) for p in local),
            'stage_total_completed': sum(p.get('completedCount', 0) for p in local),
            'attempts': [{
                'status': a['receipt'].get('status'),
                'model_request_ordinals': a.get('model_request_ordinals'),
                'perTool': p.get('perTool'), 'completedPerTool': p.get('completedPerTool'),
                'allowedCount': p.get('allowedCount'), 'completedCount': p.get('completedCount'),
                'carriedToolCalls': p.get('carriedToolCalls'),
                'continuationSha256': p.get('continuationSha256'),
                'maxToolCalls': p.get('limits', {}).get('maxToolCalls'),
                'candidate_exists_after_attempt': a['receipt'].get('candidate_exists_after_attempt'),
            } for a, p in zip(attempts, local)],
        }
        recovery_passed = bool(
            review_receipt.get('native_attempt_count') == 2 and recovery_evidence['same_session'] and
            len(recovery_context) == 1 and recovery_context[0]['original_prompt_retained'] and
            recovery_context[0]['fixed_repair_prompt_present'] and
            recovery_context[0]['original_prompt_sha256'] == recovery.get('original_prompt_sha256') and
            recovery_context[0]['shared_request_ordinal'] == expected_ordinals[1][0] and
            [a['model_request_ordinals'] for a in attempts] == expected_ordinals and
            [a['receipt'].get('status') for a in attempts] == ['failed', 'completed'] and
            attempts[0]['receipt'].get('runtime_error_type') == 'tool_input_invalid_json' and
            attempts[0]['receipt'].get('candidate_exists_after_attempt') is False and
            local[0].get('perTool') == expected_first and
            local[1].get('perTool') == expected_tool_counts('review') and
            all(p.get('perTool') == p.get('completedPerTool') and
                p.get('allowedCount') == p.get('completedCount') == sum(p['perTool'].values()) and
                p.get('failedCount') == p.get('deniedCount') == 0 and
                p.get('limits', {}).get('maxToolCalls') == 24 for p in local) and
            [p.get('carriedToolCalls') for p in local] == [0, sum(expected_first.values())] and
            local[0].get('continuationSha256') is None and
            isinstance(recovery.get('continuation_sha256'), str) and
            len(recovery['continuation_sha256']) == 64 and
            local[1].get('continuationSha256') == recovery['continuation_sha256'] and
            recovery_evidence['stage_total_allowed'] <= 24)
        recovery_evidence['passed'] = recovery_passed
    def delivery_passed(receipt):
        try:
            validate_candidate_delivery_receipt(receipt)
        except ValueError:
            return False
        return (receipt.get('delivery_kind') == args.delivery_kind and
                (receipt.get('candidate_confirmation_prefix_bytes',0)>0) == args.confirmation_prefix)
    passed = bool(result and result['provenance']['kind'] == 'synthetic_runtime_fixture'
                  and result['provenance']['real_provider_request_count'] == 0
                  and calls == expected_calls
                  and len({r['session_id'] for r in receipts}) == 3
                  and recovery_passed
                  and [(r.get('model'), r.get('reasoning_effort')) for r in receipts] ==
                      [stage_settings[mode] for mode in ('generation', 'review', 'explanation_review')]
                  and all(r['tool_receipt']['candidateSchemaStrict'] is True and
                          all(r.get(key) == value for key, value in stage_execution_limits(r['reasoning_effort']).items()) and
                          r['tool_receipt']['candidateCompilerReady'] is True and
                          r['required_tools'] == list(required_stage_tools(r['stage'])) and
                          r['tool_receipt']['requiredTools'] == r['required_tools'] and
                          r['tool_receipt']['toolNames'] == list(HARNESS_TOOL_NAMES) and
                          r['tool_receipt']['perTool'] == expected_tool_counts(r['stage']) and
                          r['tool_receipt']['completedPerTool'] == expected_tool_counts(r['stage']) and
                          r['candidate_sha256'] == compiled_bindings[r['stage']]['compiled_candidate_sha256'] and
                          r['candidate_sha256'] != compiled_bindings[r['stage']]['raw_candidate_sha256'] and
                          delivery_passed(r) and
                          r['final_response_bytes'] < r['candidate_bytes'] for r in receipts)
                  and all(sha256_file(Path(__file__).resolve().parent / name) == digest for name, digest in source_hashes.items()))
    if result:
        write_json_new(args.out / 'synthetic-package.json', result)
    report = {'status': 'passed' if passed else 'failed', 'actual_harness_runtime': True,
              'delivery_kind': args.delivery_kind,
              'real_provider_calls': 0, 'synthetic_provider_calls': calls,
              'review_tool_path': ('read_then_malformed_validate' if args.malformed_review_after_read else
                                   'optional_retrieve_read' if args.review_lookups else 'validate_only'),
              'malformed_review_once': args.malformed_review_once,
              'malformed_review_after_read': args.malformed_review_after_read,
              'recovery_context': recovery_context,
              'recovery_evidence': recovery_evidence,
              'source_hashes': source_hashes,
              'observed_request_settings': observed_settings,
              'compiled_bindings': compiled_bindings,
              'explanation_protocol': explanation_protocol,
              'writing_prompt_protocol': writing_prompt_protocol,
              'final_response_bytes': sum(r.get('final_response_bytes', 0) for r in receipts),
              'candidate_bytes': sum(r.get('candidate_bytes', 0) for r in receipts),
              'job': status, 'runtime_receipts': receipts}
    write_json_new(args.out / 'receipt.json', report)
    print(json.dumps({k: v for k, v in report.items() if k != 'runtime_receipts'}, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
