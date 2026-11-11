"""Durable generation, blind solving and explanation checks in separate runtimes."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import time
import uuid

from harness_ielts import (HarnessError, RUNTIME_VERSION, GENERATION_MODELS, REVIEW_MODELS, REASONING_EFFORTS, prepare_stage,
                           required_stage_tools, run_native_stage, sha256_file, validate_stage_model,
                           stage_execution_limits, validate_stage_execution_limits, validate_stage_tool_receipt,
                           validate_native_recovery_receipt, write_json_new, LEGACY_DELIVERY, NATIVE_DELIVERY, DELIVERY_KINDS,
                           candidate_delivery_kind, validate_candidate_delivery_receipt)
from harness_model_proxy import ModelBudget, ModelRoute, contains_credential
from ielts_contract import (REQUEST_SCHEMA, ContractError, build_generation_prompt, canonical_sha256,
                            load_json, validate_package, validate_request)
from ielts_corpus import load, style_source_bundle
from ielts_jobs import ExerciseJobs, JobError, verify_body
from ielts_review import build_review_prompt, reviewed_candidate, validate_review
from ielts_explanation_review import build_explanation_review_prompt, validate_explanation_review
from ielts_quality_revision import revision_context as build_revision_context, revision_instruction, ROUND_SCHEMA


def normalize_submission(submission, corpus_path):
    fields = {'schema', 'request_id', 'article_id', 'body', 'paragraphs', 'reading_requests', 'writing_task2_request'}
    if not isinstance(submission, dict) or not fields <= set(submission) or not set(submission) <= fields | {'writing_task1_request'} or submission['schema'] != 'lexicon.ielts.submit.v1':
        raise JobError('submission_shape')
    corpus_path = Path(corpus_path)
    digest = sha256_file(corpus_path)
    corpus = load(corpus_path, expected_sha256=digest)
    source_ids = ['ielts.format.academic_reading', 'ielts.writing.academic_task_2']
    mapping = {'multiple_choice': 'ielts.reading.multiple_choice',
               'identifying_information': 'ielts.reading.identifying_information',
               'identifying_writers_views': 'ielts.reading.identifying_writers_views',
               'short_text_completion': 'ielts.reading.table_completion'}
    if not isinstance(submission['reading_requests'], list):
        raise JobError('reading_requests_shape')
    for question in submission['reading_requests']:
        if not isinstance(question, dict) or question.get('type') not in mapping:
            raise JobError('question_type_invalid')
        source = mapping[question['type']]
        if source not in source_ids: source_ids.append(source)
    if not isinstance(submission['body'], str):
        raise JobError('body_invalid')
    if 'writing_task1_request' in submission:
        source_ids.append('ielts.writing.academic_task_1')
    styles = style_source_bundle(corpus, source_ids)
    request = {'schema': REQUEST_SCHEMA, 'request_id': submission['request_id'], 'article_id': submission['article_id'],
               'body_hash': hashlib.sha256(submission['body'].encode('utf-8')).hexdigest(),
               'paragraphs': copy.deepcopy(submission['paragraphs']),
               'reading_requests': copy.deepcopy(submission['reading_requests']),
               'writing_task2_request': copy.deepcopy(submission['writing_task2_request']),
               'style_sources': styles['style_sources'], 'style_blueprint_sha256': styles['style_blueprint_sha256']}
    if 'writing_task1_request' in submission:
        request['writing_task1_request'] = copy.deepcopy(submission['writing_task1_request'])
    validate_request(request)
    verify_body(request, submission['body'])
    return request, submission['body'], digest


def _legacy_stage_prompt(request, *, package=None, review_id=None, blind_review=None):
    if blind_review is not None:
        return ('The complete frozen passage, questions, answers, explanations and output contract follow. '
                'Use this supplied material directly; do not repeat retrieval or document reading to satisfy a tool count. '
                'Audit the explanations against the entire supplied passage and the options as actually written. '
                'Examine every relevant supplied paragraph. Use lexicon_ielts_retrieve or lexicon_ielts_read only '
                'if a format rule needs clarification or an exact quote has multiple possible source positions. '
                'Do not regenerate or repair the package or change the frozen independent answers. '
                'You have at most 24 tool calls. Prefer paragraph_id/quote source locators with a unique exact '
                'quote; the validator compiles them to UTF-16 offsets. Do not count offsets mentally. '
                'Call lexicon_ielts_validate with the complete explanation review, including honest fail or '
                'insufficient findings. Structural validity is separate from content acceptance.\n\n' +
                build_explanation_review_prompt(request, package, blind_review, review_id) +
                '\n\nFINAL DELIVERY: After lexicon_ielts_validate has succeeded, finish with exactly '
                '{"candidate_sha256":"<digest returned by lexicon_ielts_validate>"}. '
                'Do not repeat the candidate JSON or invent the digest.')
    introduction = (
        'Do ONE lexicon_ielts_retrieve search and read ONE or TWO relevant reference documents with '
        'lexicon_ielts_read. The frozen input already contains the verified format summaries for ALL requested '
        'question types; do not repeat searches or read the whole reference library. Then solve the task using '
        'the frozen passage. ' if package is None else
        'The complete frozen passage, answer-blind questions, verified format summaries and output contract follow. '
        'Solve and review directly from this supplied material, examining every relevant paragraph. '
        'Do not repeat retrieval or document reading to satisfy a tool count. Use lexicon_ielts_retrieve or '
        'lexicon_ielts_read only if a format rule needs clarification or an exact quote has multiple source positions. ')
    explanation_guidance = (
        'Write each learner-facing explanation in one or two source-grounded sentences about the correct answer, '
        'preserving the source scope and quantifiers. Do not enumerate or justify rejected options in that explanation. '
        'For not_given, identify the specific missing proposition rather than infer a negative claim from silence. '
        if package is None else
        'Independently assess all options and the uniqueness of the answer; concise learner explanations do not '
        'reduce this blind review responsibility. ')
    candidate_input_guidance = (
        'For generation, replace writing_task2.prompt with prompt_draft containing exactly background and '
        'target_statement. Use background="" when no background is needed. Write one independently debatable '
        'policy or value claim in target_statement, as a single paragraph. A comparative or compound claim is '
        'allowed when it has one identifiable evaluative target; merely reporting opposing opinions is not '
        'such a claim. Keep contextual facts in background. Do not write the task instruction in either field. '
        'The host joins the nonempty background, target_statement and the fixed instruction '
        '"To what extent do you agree or disagree with this statement?" with paragraph breaks. '
        'The complete resulting prompt must fit 1800 UTF-16 units. This only organizes the draft; the independent '
        'reviewer still assesses the final learner-visible prompt for clarity, validity and topic relevance. '
        'The OUTPUT CONTRACT below describes the compiled artifact with prompt, not the raw prompt_draft input. '
        'All other fields and constraints remain unchanged. ' if package is None else
        'The OUTPUT CONTRACT below describes the compiled artifact. The candidate argument accepts the locator '
        'input shorthand above; all other fields and constraints remain unchanged. ')
    prefix = (introduction + 'You have at most 24 total tool calls, including validation and quote lookups. '
              'For source_refs, context_refs and source_ref, prefer a locator with exactly paragraph_id and quote. '
              'The tool compiles it to UTF-16 offsets only when the exact case-sensitive quote occurs once in that paragraph. '
              'Only an ambiguous quote needs an extra lexicon_ielts_read call with document_id="article:" plus paragraph_id '
              'and quote; copy the intended full range from that result. Do not count offsets mentally. '
              'For short_text_completion, source_refs may contain an exact answer reference or the supporting context. '
              'If the answer occurs exactly once as complete tokens within the union of supplied context ranges, the tool '
              'retains the contexts and appends an exact answer reference. Otherwise supply a disambiguated full answer range. '
              'The same applies to observed_answer and alternative_answers; an alternative context is retained in the parent '
              'source_refs. References stay capped at five; no context is dropped to make room. '
              'Quote complete source tokens, including internal apostrophes/hyphens; reflect cut from reflective is invalid. '
              'Choose the occurrence in the clause that supports the blank, and cite that clause separately when needed. '
              'Every reading stem and explanation must preserve the source facts, time limits and quantifiers. '
              'For each proposed correct option, verify every clause and comparison against the cited paragraph. '
              'If the passage states a property of A but says nothing about that property of B, do not infer that B '
              'lacks it: unlike, only, whereas, less/more and similar comparisons need support for both sides. '
              'A concessive word such as although does not by itself supply the missing negative claim. '
              'When independently reviewing, reject an option that needs such an unstated assumption even if it '
              'seems like the intended answer; explain the missing support in the existing checks. ' +
              explanation_guidance +
              'Do not assign a time span from one observation to another without an explicit link. '
              'Not every does not mean none; distinguish not measured from not mentioned. '
              'Formally, NOT ALL is not NONE: failure to measure every member is compatible with measuring some. '
              'For FALSE/NO, test whether the statement and passage could both be true under the same scope; '
              'if they can, there is no contradiction. Reporting one kind of result does not establish that '
              'no other kind of measurement occurred. Do not add only to a source that does not say only. '
              'When generating judgement questions, prefer explicit, unambiguous claims; avoid a negated-universal '
              'source unless the question preserves its quantifier and scope precisely. '
              'Remove unsupported claims or irrelevant quotations from explanations. '
              'Keep each prompt field to the actual question, statement or writing topic. The app already shows '
              'answer controls, word limits and writing time: do not repeat Choose the correct letter, TRUE/FALSE '
              'definitions, Write at least 250 words, or other examination boilerplate in prompt fields. '
              'For non-not_given judgement items omit reviewed_paragraph_ids and absence_justification entirely. ' +
              candidate_input_guidance +
              'Call lexicon_ielts_validate with that complete candidate object '
              'and correct EVERY reported error, including source ranges, completion_not_extractive and completion_token_boundary. '
              'Those are mandatory acceptance checks; do not reclassify them as optional semantic issues. '
              'A review must include every reading review AND the complete writing_task2_review, even when any '
              'reading question fails or has observed_answer=null. An honest rejection is still a complete review. '
              'The review validator deliberately does not reveal agreement with the generator. Do not infer hidden answers '
              'from tool status. The validator saves the successfully compiled candidate and returns its candidate_sha256. '
              'Do not add Markdown fences, commentary, runtime claims, or fields outside the contract.\n\n')
    candidate_prompt = build_generation_prompt(request) if package is None else build_review_prompt(request, package, review_id)
    completion = 'all three tools have succeeded' if package is None else 'lexicon_ielts_validate has succeeded'
    return prefix + candidate_prompt + ('\n\nFINAL DELIVERY: After ' + completion + ', finish with exactly '
        '{"candidate_sha256":"<digest returned by lexicon_ielts_validate>"}. The host reads the saved candidate. '
        'Do not repeat the candidate JSON in the final response or invent the digest.')


def stage_prompt(request, *, package=None, review_id=None, blind_review=None, delivery_kind=NATIVE_DELIVERY,
                 revision_context=None):
    if revision_context is not None and (package is not None or blind_review is not None or delivery_kind != NATIVE_DELIVERY):
        raise HarnessError('quality_revision_stage_invalid')
    if delivery_kind not in DELIVERY_KINDS:
        raise HarnessError('candidate_delivery_invalid')
    prompt = _legacy_stage_prompt(request, package=package, review_id=review_id, blind_review=blind_review)
    if delivery_kind == LEGACY_DELIVERY:
        return prompt
    # Preserve the historic instruction byte-for-byte for old receipts. New
    # generation/review protocols explicitly terminate through the final tool.
    prompt = prompt[:prompt.rindex('\n\nFINAL DELIVERY:')]
    if package is None:
        prompt = prompt.replace(
            'Write each learner-facing explanation in one or two source-grounded sentences about the correct answer, ',
            'Write a concise source-grounded explanation directly connecting the evidence to the correct answer, '
            'and stop once that connection is clear. Avoid a second sentence that merely restates the first. '
            'Preserve the comparator, direction and magnitude separately: two outcomes both exceeding the '
            'same baseline does not establish equality between those outcomes. Continue ')
        prompt = prompt.replace(
            'Do not enumerate or justify rejected options in that explanation. ',
            'Do not enumerate or justify rejected options in that explanation. '
            'Once the correct answer is established, omit unnecessary claims about other measurements or '
            'what the article does not mention. If a reporting limit is essential, state its precise scope '
            'and distinguish not affirmatively reported from not discussed at all. ')
        prompt = prompt.replace('Keep contextual facts in background. ',
            'Keep only the minimum contextual facts needed to understand the policy question in background. '
            'Preserve source comparisons exactly; a suitability limit does not imply an unstated physical comparison. ')
    elif blind_review is None:
        prompt = prompt.replace('Mark fail or insufficient whenever uncertain. ',
            'Distinguish uncertainty about the quality of an item from a determinate not_given answer. '
            'For information and writer-view judgements, answerable and unique_answer ask whether the complete '
            'passage determines exactly one permitted response, including not_given. A clear claim whose truth '
            'and opposite are both unresolved by the complete passage can therefore be answerable as not_given. '
            'For that answer, evidence_support assesses the complete paragraph-by-paragraph absence audit; '
            'it does not require a positive quotation proving the missing proposition. Do not fail a check '
            'solely because the claim cannot be confirmed or contradicted. Still mark fail or insufficient '
            'for an ambiguous claim, overlooked evidence, incomplete coverage or an unjustified absence inference. '
            'Do not treat not_given as a fallback for a poorly formed question. ')
        prompt = prompt.replace('For single choice independently assess ALL A-D options. ',
            'For single choice independently assess ALL A-D options. Label an option contradicted only when '
            'the passage entails an incompatible claim; absent evidence makes it not_supported. Mentioning '
            'a property of one group does not deny that property for another. Preserve every quantifier: '
            'not all does not mean none. ')
    if revision_context is not None:
        prompt += revision_instruction(revision_context)
    return prompt + ('\n\nFINAL DELIVERY: Call lexicon_ielts_validate with the complete candidate as your final '
        'tool call. A successful validation saves the exact compiled candidate and concludes the native turn. '
        'Do not request later tools in the same batch or send a separate assistant confirmation. '
        'Correct structural tool errors before concluding; semantic rejection is still an honest complete review.')


def _verify_runtime_binding(receipt, prompt, response, *, revision_context=None):
    if revision_context is None:
        if 'quality_revision_sha256' in receipt:
            raise HarnessError('quality_revision_binding_mismatch')
    elif (receipt.get('stage') != 'generation' or candidate_delivery_kind(receipt) != NATIVE_DELIVERY or
            receipt.get('quality_revision_sha256') != canonical_sha256(revision_context)):
        raise HarnessError('quality_revision_binding_mismatch')
    validate_stage_execution_limits(receipt, receipt.get('reasoning_effort'))
    validate_native_recovery_receipt(receipt)
    validate_candidate_delivery_receipt(receipt)
    if receipt.get('required_tools') != list(required_stage_tools(receipt.get('stage'))):
        raise HarnessError('runtime_binding_mismatch')
    validate_stage_tool_receipt(receipt['stage'], receipt.get('tool_receipt'))
    visual_hash = receipt.get('visual_module_sha256')
    visual_required = 'writing_task1_review' in response or response.get('writing_task1', {}).get('status') == 'available' or response.get('writing_task1', {}).get('reason') == 'insufficient_comparable_data'
    if (visual_required and visual_hash is None) or (visual_hash is not None and (
            not isinstance(visual_hash, str) or len(visual_hash) != 64 or any(c not in '0123456789abcdef' for c in visual_hash) or
            receipt['tool_receipt'].get('visualModuleSha256') != visual_hash)):
        raise HarnessError('visual_module_binding')
    if (receipt.get('status') != 'completed' or type(receipt.get('exit_code')) is not int or receipt['exit_code'] != 0 or
            receipt.get('process_reaped') is not True or receipt.get('prompt_sha256') != hashlib.sha256(prompt.encode()).hexdigest() or
            receipt.get('response_sha256') != canonical_sha256(response) or
            receipt.get('candidate_sha256') != canonical_sha256(response) or
            not isinstance(receipt.get('session_id'), str) or not receipt['session_id'].startswith('session-') or
            len(receipt['session_id']) > 128):
        raise HarnessError('runtime_binding_mismatch')


def _verify_model_bindings(receipts, model_records):
    # Ordinals come from the parent-owned budget counter, never from model text
    # or a native tool argument. Bind every reserved request to exactly one stage.
    if not isinstance(model_records, list) or not len(receipts) <= len(model_records) <= 16:
        raise HarnessError('model_request_binding')
    records_by_ordinal = {}
    for record in model_records:
        if not isinstance(record, dict):
            raise HarnessError('model_request_binding')
        ordinal = record.get('ordinal')
        if type(ordinal) is not int or not 1 <= ordinal <= 16 or ordinal in records_by_ordinal:
            raise HarnessError('model_request_binding')
        if type(record.get('real_provider_request')) is not bool:
            raise HarnessError('model_request_binding')
        if (record.get('phase') != 'completed' or type(record.get('http_status')) is not int or
                record['http_status'] != 200):
            raise HarnessError('model_request_incomplete')
        records_by_ordinal[ordinal] = record
    owned_ordinals = []
    for receipt in receipts:
        ordinals = receipt.get('model_request_ordinals')
        if (not isinstance(ordinals, list) or not ordinals or
                any(type(ordinal) is not int for ordinal in ordinals) or
                len(set(ordinals)) != len(ordinals) or any(n not in records_by_ordinal for n in ordinals)):
            raise HarnessError('model_request_binding')
        owned_ordinals.extend(ordinals)
        validate_native_recovery_receipt(receipt, model_records)
        if candidate_delivery_kind(receipt) == NATIVE_DELIVERY:
            termination = records_by_ordinal[ordinals[-1]].get('termination')
            if (not isinstance(termination, dict) or termination.get('metadata_status') != 'observed' or
                    termination.get('format') != 'anthropic_sse' or termination.get('stop_reason') != 'tool_use' or
                    termination.get('message_stop_observed') is not True or
                    any(type(termination.get(k)) is not int or not 0 <= termination[k] <= 2**53 - 1
                        for k in ('input_tokens', 'output_tokens'))):
                raise HarnessError('native_delivery_provider_termination')
    if (owned_ordinals != list(range(1, len(model_records) + 1)) or
            set(owned_ordinals) != set(records_by_ordinal)):
        raise HarnessError('model_request_binding')
    return records_by_ordinal


def verify_rejected_round(request, rejected, model_records):
    context = build_revision_context(request, rejected)
    receipts = rejected['receipts']
    results = [rejected['package'], rejected['review']]
    if rejected['explanation_review'] is not None:
        results.append(rejected['explanation_review'])
    for index, (receipt, result) in enumerate(zip(receipts, results)):
        if candidate_delivery_kind(receipt) != NATIVE_DELIVERY or receipt.get('runtime_version') != RUNTIME_VERSION:
            raise HarnessError('quality_revision_runtime_mismatch')
        validate_stage_model(receipt['stage'], receipt.get('model'), receipt.get('reasoning_effort'))
        prompt = stage_prompt(request, package=results[0] if index else None,
            review_id=result.get('review_id') if index else None,
            blind_review=results[1] if index == 2 else None)
        _verify_runtime_binding(receipt, prompt, result)
    _verify_model_bindings(receipts, model_records)
    if len({r['session_id'] for r in receipts}) != len(receipts) or len({r['corpus_sha256'] for r in receipts}) != 1:
        raise HarnessError('quality_revision_runtime_mismatch')
    return context


def verified_output(request, package, review, review_id, receipts, model_records, *, generation_model=None,
                    explanation_review=None, explanation_review_id=None, rejected_round=None):
    if (not isinstance(receipts, list) or len(receipts) != 3 or
            any(not isinstance(r, dict) for r in receipts) or
            [r.get('stage') for r in receipts] != ['generation', 'review', 'explanation_review']):
        raise HarnessError('three_stages_required')
    if any(receipt.get('runtime_version') != RUNTIME_VERSION for receipt in receipts):
        raise HarnessError('runtime_version_mismatch')
    for receipt in receipts:
        validate_stage_model(receipt['stage'], receipt.get('model'), receipt.get('reasoning_effort'))
    # Legacy six-argument offline verification still validates the model
    # allowlist and fixed generation effort. A live host additionally binds its
    # selected generator; this value is never taken from model-produced JSON.
    if generation_model is not None:
        validate_stage_model('generation', generation_model, 'off')
        if receipts[0].get('model') != generation_model:
            raise HarnessError('runtime_model_binding_mismatch')
    view = reviewed_candidate(request, package, review, review_id)
    if explanation_review is None or explanation_review_id is None:
        raise HarnessError('explanation_review_required')
    explanation_audit = validate_explanation_review(request, package, review, explanation_review, explanation_review_id)
    if explanation_audit['status'] != 'eligible_for_caller_publication':
        raise HarnessError('explanation_review_rejected')
    context, previous = None, []
    if rejected_round is not None:
        build_revision_context(request, rejected_round)
        previous = rejected_round['receipts']
        count = sum(len(r.get('model_request_ordinals', [])) for r in previous)
        context = verify_rejected_round(request, rejected_round, model_records[:count])
        for prior, final in zip(previous, receipts):
            if any(prior.get(key) != final.get(key) for key in ('model', 'reasoning_effort', 'corpus_sha256')):
                raise HarnessError('quality_revision_runtime_mismatch')
    prompts = [stage_prompt(request, delivery_kind=candidate_delivery_kind(receipts[0]), revision_context=context),
               stage_prompt(request, package=package, review_id=review_id, delivery_kind=candidate_delivery_kind(receipts[1])),
               stage_prompt(request, package=package, review_id=explanation_review_id, blind_review=review,
                            delivery_kind=candidate_delivery_kind(receipts[2]))]
    for index, (receipt, prompt, response) in enumerate(zip(receipts, prompts, [package, review, explanation_review])):
        _verify_runtime_binding(receipt, prompt, response, revision_context=context if index == 0 else None)
    all_receipts = previous + receipts
    if len({r['session_id'] for r in all_receipts}) != len(all_receipts):
        raise HarnessError('review_session_not_independent')
    if len({r['corpus_sha256'] for r in receipts}) != 1:
        raise HarnessError('corpus_changed_between_stages')
    records_by_ordinal = _verify_model_bindings(all_receipts, model_records)
    real_count = sum(record.get('real_provider_request') is True for record in model_records)
    all_real = real_count == len(model_records) and all(
        any(records_by_ordinal[ordinal]['real_provider_request'] for ordinal in receipt['model_request_ordinals'])
        for receipt in all_receipts)
    return {'schema': 'lexicon.ielts.verified-package.v1', 'request': request, 'package': package,
            'provenance': {'kind': 'deepseek_harness' if all_real else 'synthetic_runtime_fixture',
                'runtime_version': receipts[0]['runtime_version'], 'generation_session_id': receipts[0]['session_id'],
                'review_session_id': receipts[1]['session_id'], 'request_sha256': canonical_sha256(request),
                'package_sha256': canonical_sha256(package), 'review_sha256': view['review_receipt']['review_sha256'],
                'corpus_sha256': receipts[0]['corpus_sha256'], 'semantic_review': 'independent_answers_agree',
                'explanation_review_session_id': receipts[2]['session_id'],
                'explanation_review_sha256': canonical_sha256(explanation_review),
                'explanation_review': 'source_faithfulness_checked',
                'recent_three_year_coverage': False, 'real_provider_request_count': real_count,
                'created_at': int(time.time() * 1000)}}


def run_job(jobs, job_id, *, corpus_path, key, work_root, max_calls=12, timeout=240,
            transport_factory=None, generation_model=GENERATION_MODELS['flash'],
            review_model='deepseek-flash', review_reasoning='off',
            explanation_model=REVIEW_MODELS['pro'], explanation_reasoning='high', delivery_kind=NATIVE_DELIVERY,
            quality_revisions=0):
    """One claim only. Fixture transports stay explicitly labelled in every output."""
    # Invalid execution budgets must not consume the queued job's one claim.
    if delivery_kind not in DELIVERY_KINDS:
        raise HarnessError('candidate_delivery_invalid')
    if type(quality_revisions) is not int or quality_revisions not in (0, 1) or (quality_revisions and delivery_kind != NATIVE_DELIVERY):
        raise HarnessError('quality_revision_policy_invalid')
    budget = ModelBudget(max_calls)
    job_deadline = time.monotonic() + timeout * 3 if quality_revisions else None
    validate_stage_model('generation', generation_model, 'off')
    validate_stage_model('review', review_model, review_reasoning)
    validate_stage_model('explanation_review', explanation_model, explanation_reasoning)
    stage_settings = {'generation': (generation_model, 'off'), 'review': (review_model, review_reasoning),
                      'explanation_review': (explanation_model, explanation_reasoning)}
    lease, envelope = jobs.claim(job_id)
    request, body = envelope['request'], envelope['body']
    work = Path(work_root) / job_id
    work_created = False
    receipts = []
    rejected_round, context = None, None
    package, review, review_id = None, None, 'review-' + uuid.uuid4().hex
    explanation_review, explanation_review_id = None, 'explanation-' + uuid.uuid4().hex
    process_token = None
    try:
        try:
            work.mkdir(parents=True, exist_ok=False, mode=0o700)
        except FileExistsError:
            raise HarnessError('work_directory_exists') from None
        work_created = True
        verify_body(request, body)
        if sha256_file(corpus_path) != envelope['corpus_hash']:
            raise HarnessError('corpus_changed_after_submission')
        if contains_credential(json.dumps(envelope, ensure_ascii=False).encode(), key):
            raise HarnessError('credential_in_input')
        for round_index in range(quality_revisions + 1):
            round_work = work if round_index == 0 else work / 'revision-01'
            round_work.mkdir(parents=True, exist_ok=True, mode=0o700)
            round_receipts = []
            package, review, explanation_review = None, None, None
            review_id, explanation_review_id = 'review-' + uuid.uuid4().hex, 'explanation-' + uuid.uuid4().hex
            for mode in ('generation', 'review', 'explanation_review'):
                if jobs.cancellation_requested(job_id, lease):
                    return jobs.finish(job_id, lease, error='cancelled_before_stage')
                stage_timeout = timeout if job_deadline is None else min(timeout, job_deadline - time.monotonic())
                if stage_timeout < 1:
                    raise HarnessError('job_deadline_exhausted')
                stage_review_id = explanation_review_id if mode == 'explanation_review' else review_id
                prompt = stage_prompt(request, package=package if mode != 'generation' else None,
                                      review_id=stage_review_id if mode != 'generation' else None,
                                      blind_review=review if mode == 'explanation_review' else None, delivery_kind=delivery_kind, revision_context=context if mode == 'generation' else None)
                transport = transport_factory(mode, request, package, stage_review_id, review) if transport_factory else None
                first_request_ordinal = budget.calls + 1
                stage_model, stage_reasoning = stage_settings[mode]
                limits = stage_execution_limits(stage_reasoning)
                with ModelRoute(key, model=stage_model, token_limit=limits['token_limit'],
                                provider_deadline_seconds=limits['provider_deadline_seconds'],
                                budget=budget, transport=transport) as route:
                    prepared = prepare_stage(round_work / mode, request, corpus_path, route, mode=mode,
                                             package=package, review_id=stage_review_id if mode != 'generation' else None,
                                             blind_review=review if mode == 'explanation_review' else None,
                                             reasoning_effort=stage_reasoning, delivery_kind=delivery_kind)
                    if mode == 'generation' and context is not None:
                        prepared['quality_revision_sha256'] = canonical_sha256(context)
                        write_json_new(prepared['directory'] / 'inputs/quality-revision-context.json', context)
                    process_token = jobs.set_process_active(job_id, lease)
                    try:
                        result, receipt = run_native_stage(prepared, prompt, mode=mode, timeout=stage_timeout,
                                            model_budget=budget, first_request_ordinal=first_request_ordinal,
                                            cancelled=lambda: jobs.cancellation_requested(job_id, lease))
                    except Exception:
                        # No trustworthy reaping receipt reached us. Keep that uncertainty
                        # visible instead of guessing that an exception means no live child.
                        return jobs.interrupt_unconfirmed(job_id, lease)
                    if not isinstance(receipt, dict) or receipt.get('process_reaped') is not True:
                        return jobs.interrupt_unconfirmed(job_id, lease)
                    receipt = dict(receipt)
                    receipt['model_request_ordinals'] = list(range(first_request_ordinal, budget.calls + 1))
                    jobs.record_stage(job_id, lease, process_token, receipt, reaped=True)
                    process_token = None
                receipts.append(receipt)
                round_receipts.append(receipt)
                if receipt.get('model') != stage_model or receipt.get('reasoning_effort') != stage_reasoning:
                    raise HarnessError('runtime_model_binding_mismatch')
                validate_stage_execution_limits(receipt, stage_reasoning)
                if result is None or receipt['status'] != 'completed':
                    if any(record.get('phase') != 'completed' for record in budget.snapshot()):
                        raise HarnessError('provider_completion_unknown')
                    raise HarnessError(receipt.get('error', 'runtime_failed'))
                if contains_credential(json.dumps(result, ensure_ascii=False).encode(), key):
                    raise HarnessError('credential_in_output')
                # Confirm provider completion before creating another owned process. A
                # completed native runner alone does not settle its last HTTP request.
                records = budget.snapshot()
                if any(record.get('phase') != 'completed' for record in records):
                    raise HarnessError('provider_completion_unknown')
                _verify_model_bindings(receipts, records)
                # Freeze and verify each stage before exposing any later-stage input.
                _verify_runtime_binding(receipt, prompt, result, revision_context=context if mode == 'generation' else None)
                if receipt.get('stage') != mode or receipt.get('runtime_version') != RUNTIME_VERSION:
                    raise HarnessError('runtime_binding_mismatch')
                if receipt.get('corpus_sha256') != envelope['corpus_hash']:
                    raise HarnessError('corpus_changed_between_stages')
                if len({r.get('session_id') for r in receipts}) != len(receipts):
                    raise HarnessError('review_session_not_independent')
                rejection = None
                if mode == 'generation':
                    validate_package(request, result)
                    package = result
                    write_json_new(round_work / 'generation-candidate.json', result)
                    if round_index == 0:
                        jobs.begin_review(job_id, lease)
                elif mode == 'review':
                    review = result
                    audit = validate_review(request, package, review, review_id)
                    write_json_new(round_work / 'review-receipt.json', audit)
                    if audit['status'] != 'eligible_for_caller_publication':
                        rejection = 'review_rejected'
                    else:
                        write_json_new(round_work / 'blind-review.json', review)
                else:
                    explanation_review = result
                    audit = validate_explanation_review(request, package, review, result, explanation_review_id)
                    write_json_new(round_work / 'explanation-review-receipt.json', audit)
                    if audit['status'] != 'eligible_for_caller_publication':
                        rejection = 'explanation_review_rejected'
                if rejection is not None:
                    # Only a fully received, verified semantic rejection may trigger
                    # one new candidate. Structural/transport/auth/unknown failures exit.
                    if (round_index != 0 or quality_revisions == 0 or budget.max_calls - budget.calls < 5 or
                            budget.halted or jobs.cancellation_requested(job_id, lease)):
                        raise HarnessError(rejection)
                    rejected_round = {'schema': ROUND_SCHEMA, 'package': package, 'review': review,
                        'explanation_review': explanation_review, 'receipts': copy.deepcopy(round_receipts)}
                    context = verify_rejected_round(request, rejected_round, budget.snapshot())
                    if contains_credential(json.dumps(context, ensure_ascii=False).encode(), key):
                        raise HarnessError('credential_in_revision')
                    write_json_new(work / 'quality-revision.json', rejected_round)
                    break
            else:
                output = verified_output(request, package, review, review_id, round_receipts, budget.snapshot(),
                                         generation_model=generation_model, explanation_review=explanation_review,
                                         explanation_review_id=explanation_review_id, rejected_round=rejected_round)
                return jobs.finish(job_id, lease, output=output)
    except (ValueError, OSError) as error:
        if process_token is not None:
            # A failed receipt commit cannot safely clear the durable process
            # ownership marker, even when the transient result claimed reaping.
            return jobs.interrupt_unconfirmed(job_id, lease)
        code = getattr(error, 'code', 'host_io_error')
        if not isinstance(code, str) or not code.replace('_', '').isalnum() or len(code) > 80:
            code = 'pipeline_rejected'
        return jobs.finish(job_id, lease, error=code.lower())
    finally:
        # Never append to an existing task directory that this claim did not
        # create. In particular, preserve the previous model-requests evidence.
        if work_created:
            model_records = budget.snapshot()
            write_json_new(work / 'model-requests.json', {'schema': 'lexicon.ielts.model-usage.v1',
                'max_calls': budget.max_calls, 'reserved_calls': budget.calls, 'requests': model_records,
                'real_provider_requests': sum(r['real_provider_request'] for r in model_records),
                'provider_completion_unconfirmed': sum(r.get('phase') != 'completed' for r in model_records)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True, help='Bounded submit.v1 JSON, never an executable prompt')
    parser.add_argument('--out', type=Path, required=True, help='New private task directory')
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--key-file', type=Path, required=True)
    parser.add_argument('--max-calls', type=int, default=12)
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--quality-revisions', type=int, choices=(0, 1), default=1)
    parser.add_argument('--generation-model', choices=tuple(GENERATION_MODELS), default='flash')
    parser.add_argument('--review-model', choices=tuple(REVIEW_MODELS), default='flash', help='Independent answer review model; default: flash')
    parser.add_argument('--review-reasoning', choices=REASONING_EFFORTS, default='off', help='Independent answer review effort; default: off')
    parser.add_argument('--explanation-model', choices=tuple(REVIEW_MODELS), default='pro', help='Post-blind explanation review model; default: pro')
    parser.add_argument('--explanation-reasoning', choices=REASONING_EFFORTS, default='high', help='Post-blind explanation review effort; default: high')
    args = parser.parse_args()
    request, body, corpus_sha = normalize_submission(load_json(args.input), args.corpus)
    key = args.key_file.read_text(encoding='utf-8').strip()
    if not key or '\n' in key or '\r' in key:
        raise JobError('key_format_invalid')
    if contains_credential(json.dumps({'request': request, 'body': body}, ensure_ascii=False).encode(), key):
        raise JobError('credential_in_input')
    args.out.mkdir(parents=True, exist_ok=False, mode=0o700)
    jobs = ExerciseJobs(args.out/'jobs.sqlite')
    job = jobs.submit(request, body, corpus_sha)
    status = run_job(jobs, job['id'], corpus_path=args.corpus, key=key, work_root=args.out/'work',
                     max_calls=args.max_calls, timeout=args.timeout, quality_revisions=args.quality_revisions,
                     generation_model=GENERATION_MODELS[args.generation_model],
                     review_model=REVIEW_MODELS[args.review_model], review_reasoning=args.review_reasoning,
                     explanation_model=REVIEW_MODELS[args.explanation_model], explanation_reasoning=args.explanation_reasoning)
    write_json_new(args.out/'job-receipt.json', status)
    if status['state'] == 'ready':
        write_json_new(args.out/'exercise-package.json', jobs.package(job['id']))
    print(json.dumps(status, ensure_ascii=False, indent=2))
    return 0 if status['state'] == 'ready' else 1


if __name__ == '__main__':
    try: raise SystemExit(main())
    except (ValueError, OSError):
        print('{"status":"rejected","error":"invalid_input_or_environment"}')
        raise SystemExit(2)
