"""Source-bound Task 1 numeric visuals. Pure validation; no generation or approval.

Decimal strings preserve cross-language hashes. Each cell binds its exact numeric
lexeme and its containing context. Labels, units, comparability and Task 1
suitability still require an independent semantic review of the complete passage.
"""
from decimal import Decimal, InvalidOperation
import re

import ielts_contract as contract

VISUAL_SCHEMA = 'lexicon.ielts.visual.v1'
TASK_SCHEMA = 'lexicon.ielts.visual-task.v1'
KINDS = ('grouped_bar', 'table')
MAX_CELLS = 24
VALUE = re.compile(r'(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,4})?\Z', re.ASCII)
SOURCE_NUMBER = re.compile(r'(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\.[0-9]+)?\Z', re.ASCII)
INSTRUCTION = 'Summarise the information by selecting and reporting the main features, and make comparisons where relevant.'


def fail(code, path):
    contract._fail(code, path, 'Source visual does not satisfy the declared contract')


def number(value, path='visual.value'):
    if not isinstance(value, str) or not VALUE.fullmatch(value):
        fail('visual_decimal', path)
    try:
        result = Decimal(value)
    except InvalidOperation:
        fail('visual_decimal', path)
    if result > Decimal('999999999999.9999'):
        fail('visual_decimal', path)
    # Exactly one serialized spelling per value; no exponent, redundant zero or -0.
    canonical = format(result, 'f')
    if '.' in canonical:
        canonical = canonical.rstrip('0').rstrip('.')
    if value != canonical:
        fail('visual_noncanonical_decimal', path)
    return result


def _plain(value, path, maximum):
    contract._text(value, path, maximum)
    if value != value.strip() or any(c in value for c in '\r\n\t\u0085\u2028\u2029'):
        fail('visual_label', path)
    return value


def _unique(values, path):
    normalized = [' '.join(value.split()).lower() for value in values]
    if len(normalized) != len(set(normalized)):
        fail('visual_duplicate_label', path)


def _reference(value, request, path):
    contract._references([value], request, path)
    return value


def validate_visual(request, value):
    """Request must already pass the article request validator; no recursive validation."""
    path = 'visual'
    chart = contract._object(value, path, {'schema', 'kind', 'title', 'unit', 'categories', 'series', 'context_refs'})
    contract._equal(chart['schema'], VISUAL_SCHEMA, path + '.schema')
    if chart['kind'] not in KINDS:
        fail('visual_kind', path + '.kind')
    _plain(chart['title'], path + '.title', 240)
    _plain(chart['unit'], path + '.unit', 80)
    categories = contract._list(chart['categories'], path + '.categories', 2, 8)
    for label in categories:
        _plain(label, path + '.categories', 80)
    _unique(categories, path + '.categories')
    series = contract._list(chart['series'], path + '.series', 1, 3)
    if not 4 <= len(categories) * len(series) <= MAX_CELLS:
        fail('visual_cell_count', path)
    contexts = contract._references(chart['context_refs'], request, path + '.context_refs', maximum=32)
    refs, numbers, labels = set(), [], []
    for si, item in enumerate(series):
        where = '{}.series[{}]'.format(path, si)
        contract._object(item, where, {'label', 'cells'})
        labels.append(_plain(item['label'], where + '.label', 80))
        cells = contract._list(item['cells'], where + '.cells', len(categories), len(categories))
        for ci, cell in enumerate(cells):
            here = '{}.cells[{}]'.format(where, ci)
            contract._object(cell, here, {'value', 'source_ref'})
            numeric = number(cell['value'], here + '.value')
            ref = _reference(cell['source_ref'], request, here + '.source_ref')
            raw = ref['quote']
            if len(raw) > 24 or not SOURCE_NUMBER.fullmatch(raw) or Decimal(raw.replace(',', '')) != numeric:
                fail('visual_source_value', here)
            if not any(c['paragraph_id'] == ref['paragraph_id'] and c['start'] <= ref['start'] and ref['end'] <= c['end'] and
                       (c['start'] < ref['start'] or ref['end'] < c['end']) for c in contexts):
                fail('visual_context_missing', here)
            part = next(p for p in request['paragraphs'] if p['paragraph_id'] == ref['paragraph_id'])
            start, end = ref['start'] - part['start'], ref['end'] - part['start']
            before = contract.utf16_slice(part['text'], 0, start)
            after = contract.utf16_slice(part['text'], end, contract.utf16_length(part['text']))
            # Reject a substring of a larger number, a sign, scientific notation or attached unit.
            if (before and (before[-1].isalnum() or before[-1] in '.,+−-')) or (after and (after[0].isalnum() or
                    (after[0] in '.,' and len(after) > 1 and after[1].isdigit()))):
                fail('visual_numeric_boundary', here)
            identity = (ref['paragraph_id'], ref['start'], ref['end'])
            if identity in refs:
                fail('visual_reused_number', here)
            refs.add(identity); numbers.append(numeric)
    _unique(labels, path + '.series')
    if len(set(numbers)) < 2:
        fail('visual_no_comparison', path)
    return {'visual_sha256': contract.canonical_sha256(chart), 'cells': len(numbers),
            'source_numbers_verified': True, 'semantic_review_required': True,
            'chart_maximum': format(max(numbers), 'f')}


def task_prompt(visual):
    return visual['title'] + '\n\n' + INSTRUCTION


def validate_task(request, task):
    contract._object(task, 'task1', {'schema', 'task_id', 'request_sha256', 'status', 'visual', 'prompt',
                                    'minimum_words', 'suggested_minutes'})
    contract._equal(task['schema'], TASK_SCHEMA, 'task1.schema')
    contract._identifier(task['task_id'], 'task1.task_id')
    if task['task_id'] in [q['question_id'] for q in request['reading_requests']] + [request['writing_task2_request']['task_id']]:
        fail('visual_task_id_conflict', 'task1.task_id')
    contract._equal(task['request_sha256'], contract.canonical_sha256(request), 'task1.request_sha256')
    contract._equal(task['status'], 'available', 'task1.status')
    contract._equal(task['minimum_words'], 150, 'task1.minimum_words')
    contract._equal(task['suggested_minutes'], 20, 'task1.suggested_minutes')
    result = validate_visual(request, task['visual'])
    contract._equal(task['prompt'], task_prompt(task['visual']), 'task1.prompt')
    return dict(result, task_sha256=contract.canonical_sha256(task))


def compile_task(request, task_id, visual):
    task = {'schema': TASK_SCHEMA, 'task_id': task_id, 'request_sha256': contract.canonical_sha256(request),
            'status': 'available', 'visual': visual, 'prompt': task_prompt(visual),
            'minimum_words': 150, 'suggested_minutes': 20}
    validate_task(request, task)
    return task


def validate_request_spec(request):
    spec = contract._object(request['writing_task1_request'], 'request.writing_task1_request', {'task_id', 'policy'})
    contract._identifier(spec['task_id'], 'request.writing_task1_request.task_id')
    contract._equal(spec['policy'], 'source_numeric_visual_v1', 'request.writing_task1_request.policy')
    if spec['task_id'] in [q['question_id'] for q in request['reading_requests']] + [request['writing_task2_request']['task_id']]:
        fail('visual_task_id_conflict', 'request.writing_task1_request.task_id')


def validate_package_task(request, task):
    if 'writing_task1_request' not in request:
        contract._equal(task, {'status': 'unavailable', 'reason': 'no_verified_visual'}, 'package.writing_task1')
        return
    if not isinstance(task, dict):
        fail('visual_task_shape', 'package.writing_task1')
    if task.get('status') == 'unavailable':
        contract._equal(task, {'status': 'unavailable', 'reason': 'insufficient_comparable_data'}, 'package.writing_task1')
    else:
        contract._object(task, 'package.writing_task1', {'status', 'task'})
        contract._equal(task['status'], 'available', 'package.writing_task1.status')
        contract._equal(task['task']['task_id'], request['writing_task1_request']['task_id'], 'package.writing_task1.task_id')
        validate_task(request, task['task'])


def validate_visual_review(request, task, review):
    """Every cell and common semantic dimension must be inspected; errors block the package."""
    path = 'review.writing_task1_review'
    contract._object(review, path, {'task_id', 'availability', 'checks', 'cell_reviews'})
    contract._equal(review['task_id'], request['writing_task1_request']['task_id'], path + '.task_id')
    contract._equal(review['availability'], task['status'], path + '.availability')
    names = ['data_sufficiency', 'task_suitability']
    if task['status'] == 'available':
        names += ['title_and_units', 'category_series_mapping', 'scope_and_comparability']
    checks = contract._object(review['checks'], path + '.checks', set(names))
    blockers = []
    def check(value, identity):
        contract._object(value, identity, {'status', 'reason'})
        if value['status'] not in ('pass', 'fail', 'insufficient'):
            fail('visual_review_status', identity)
        contract._text(value['reason'], identity + '.reason', 320)
        if value['status'] != 'pass':
            blockers.append({'item_id': review['task_id'], 'code': 'visual_' + value['status'], 'conclusion': value['reason']})
    for name in names:
        check(checks[name], path + '.checks.' + name)
    expected = [] if task['status'] != 'available' else [(si, ci) for si, s in enumerate(task['task']['visual']['series']) for ci, _ in enumerate(s['cells'])]
    cells = contract._list(review['cell_reviews'], path + '.cell_reviews', len(expected), len(expected))
    for index, (value, (si, ci)) in enumerate(zip(cells, expected)):
        here = '{}.cell_reviews[{}]'.format(path, index)
        contract._object(value, here, {'series_index', 'category_index', 'status', 'reason'})
        contract._integer(value['series_index'], here, si, si); contract._integer(value['category_index'], here, ci, ci)
        check({k:value[k] for k in ('status', 'reason')}, here)
    return blockers


def generation_guidance():
    return ('Task 1: extract a grouped_bar or table only when the original passage reports at least four '
            'comparable numeric observations, with at least two categories and one to three series, at most 24 cells. '
            'Each decimal value must directly match its own cited source number (commas may be removed). '
            'No inferred totals, subtraction, unit conversion, missing-value estimates, invented trends or synthetic data. '
            'Bind the full contextual sentences in context_refs. Each cell source_ref may cite the exact numeric lexeme or a context with one unique occurrence of its value; the host extracts the number. '
            'Labels must faithfully identify the source categories and the same unit across every cell; avoid unrelated dates '
            'or counts assembled solely to fill a chart. Set unavailable/insufficient_comparable_data when these conditions '
            'or a meaningful Task 1 comparison cannot be met. When available submit {status:"available",visual:{...}}; '
            'the host compiles the task prompt, identity, 150-word and 20-minute settings. No model essay or claimed trend summary. ')


def review_guidance():
    return ('Review Task 1 from the complete passage: unavailable is acceptable only if no suitable comparable '
            'numeric data supports the requested visual; explain the insufficiency in data_sufficiency. For an available '
            'visual inspect EVERY cell in series-major order. Check its category, series, numeric context and unit, '
            'not just literal number presence. Check whether dates, sample sizes or unrelated observations have been '
            'misrepresented as a common measure. Title, units, mapping, scope and comparison must match the passage. '
            'Assess whether the visual supports an overview and selected comparisons without outside facts. '
            'Each check/cell needs a brief specific reason (max 320 UTF-16 units); any fail or insufficient blocks delivery. ')
