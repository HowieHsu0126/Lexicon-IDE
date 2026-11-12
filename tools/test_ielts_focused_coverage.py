"""Product contrast-practice policy, independent of official exam answer ratios."""
import copy
import json
import unittest

from ielts_candidate import (compile_candidate, validate_focused_coverage, focused_information_practice,
                             FOCUSED_INFORMATION_GUIDANCE)
from ielts_contract import ContractError, canonical_sha256, validate_package, candidate_view
from ielts_json_schema import generation_schema, review_schema
from ielts_pipeline import stage_prompt
from ielts_review import validate_review
from harness_ielts import LEGACY_DELIVERY
from test_ielts_contract import reference
from test_ielts_visual import visual_fixture


def focused_fixture():
    request, package, blind = visual_fixture()
    p1, p2, p3 = request['paragraphs']
    request['reading_requests'] = [{'question_id': 'q'+str(i+1), 'type': 'identifying_information'} for i in range(4)]
    claims = [
        ('The council approved equipment for solar energy.', 'true', [reference(p1, 'The council approved solar panels.')]),
        ('The writer recommends avoiding public investment in clean energy.', 'false', [reference(p2, p2['text'])]),
        ('The council plans to double its clean-energy budget next year.', 'not_given', []),
        ('Weekly visits at the Riverside library increased between the two reported years.', 'true', [reference(p3, p3['text'].split('. ', 1)[0]+'.')]),
    ]
    questions, reviews = [], []
    for i, (prompt, answer, refs) in enumerate(claims):
        ng = answer == 'not_given'
        q = copy.deepcopy(package['reading_questions'][2 if ng else 1])
        r = copy.deepcopy(blind['reading_reviews'][2 if ng else 1])
        q.update(question_id='q'+str(i+1), type='identifying_information', prompt=prompt, answer=answer,
                 source_refs=refs, explanation='Synthetic interpretation for host-contract checks, not a model-quality claim.')
        r.update(question_id=q['question_id'], type=q['type'], observed_answer=answer,
                 claim=prompt, conclusion='Synthetic independent result for protocol testing.', source_refs=copy.deepcopy(refs))
        if ng:
            q['absence_justification'] = 'Equipment approval and general investment support do not specify a council budget multiplier or year; library statistics are unrelated.'
            r['not_given_review']['missing_claim'] = prompt
        questions.append(q); reviews.append(r)
    package.update(reading_questions=questions, request_sha256=canonical_sha256(request))
    package['writing_task1']['task']['request_sha256'] = canonical_sha256(request)
    blind.update(reading_reviews=reviews, request_sha256=canonical_sha256(request), package_sha256=canonical_sha256(package))
    return request, package, blind


class FocusedCoverageTests(unittest.TestCase):
    def test_complete_contrast_set_retains_exact_input_and_compiles_idempotently(self):
        request, package, blind = focused_fixture()
        original = copy.deepcopy(package)
        result = compile_candidate(request, package, 'generation')
        self.assertEqual(original, package)
        self.assertEqual(original, result)
        self.assertEqual(result, compile_candidate(request, result, 'generation'))
        validate_package(request, result)
        self.assertEqual('eligible_for_caller_publication', validate_review(request, result, blind, blind['review_id'])['status'])

    def test_each_missing_response_fails_without_relabelling_any_question(self):
        for missing in ('true', 'false', 'not_given'):
            request, package, _ = focused_fixture()
            for item in package['reading_questions']:
                if item['answer'] == missing: item['answer'] = 'false' if missing == 'true' else 'true'
            before = copy.deepcopy(package)
            with self.subTest(missing=missing), self.assertRaises(ContractError) as caught:
                validate_focused_coverage(request, package)
            self.assertEqual('candidate_focused_response_coverage', caught.exception.code)
            self.assertEqual(before, package)

    def test_repeated_labels_allowed_without_fixed_order_or_equal_ratio(self):
        request, package, _ = focused_fixture()
        for values in [('true', 'false', 'not_given', 'not_given'), ('false', 'not_given', 'true', 'false'), ('not_given', 'true', 'true', 'false')]:
            for item, value in zip(package['reading_questions'], values): item['answer'] = value
            validate_focused_coverage(request, package)

    def test_mixed_short_and_writer_view_requests_do_not_inherit_the_recipe(self):
        for kind in ('mixed', 'short', 'writers'):
            request, package, _ = focused_fixture()
            if kind == 'mixed': request['reading_requests'][0]['type'] = 'multiple_choice'
            elif kind == 'short': request['reading_requests'] = request['reading_requests'][:3]
            else:
                for q in request['reading_requests']: q['type'] = 'identifying_writers_views'
            self.assertFalse(focused_information_practice(request))
            for item in package['reading_questions']: item['answer'] = 'true'
            validate_focused_coverage(request, package)
            self.assertNotIn('FOCUSED CONTRAST PRACTICE', stage_prompt(request))

    def test_bad_labels_are_not_counted_as_coverage(self):
        for bad in (None, [], True, 'TRUE', 'yes'):
            request, package, _ = focused_fixture(); package['reading_questions'][0]['answer'] = bad
            with self.subTest(bad=bad), self.assertRaises(ContractError) as caught:
                validate_focused_coverage(request, package)
            self.assertEqual('candidate_information_answer', caught.exception.code)

    def test_compiler_enforces_coverage_but_archive_contract_does_not_retrofit_it(self):
        request, package, _ = focused_fixture()
        package['reading_questions'][2] = dict(copy.deepcopy(package['reading_questions'][1]), question_id='q3',
                                             prompt='The writer opposes investment in clean energy.')
        validate_package(request, package)
        self.assertEqual(4, len(candidate_view(request, package)['reading_questions']))
        with self.assertRaisesRegex(ContractError, 'candidate_focused_response_coverage'):
            compile_candidate(request, package, 'generation')

    def test_covering_all_labels_cannot_remove_the_absence_audit_or_invent_support(self):
        for mutation in ('missing_scope', 'forged_support'):
            request, package, _ = focused_fixture()
            if mutation == 'missing_scope': package['reading_questions'][2]['reviewed_paragraph_ids'].pop()
            else: package['reading_questions'][2]['source_refs'] = package['reading_questions'][0]['source_refs']
            # Label presence deliberately does not certify the question.
            validate_focused_coverage(request, package)
            with self.subTest(mutation=mutation), self.assertRaises(ContractError): validate_package(request, package)

    def test_blind_review_can_contradict_the_generation_without_fulfilling_any_quota(self):
        request, package, blind = focused_fixture()
        item = blind['reading_reviews'][2]
        item.update(observed_answer=None, not_given_review=None,
                    checks={name: {'status':'insufficient','reason':'Fixture models an ambiguous claim that cannot be uniquely assessed.'} for name in item['checks']})
        compiled = compile_candidate(request, blind, 'review')
        self.assertIsNone(compiled['reading_reviews'][2]['observed_answer'])
        self.assertEqual('rejected', validate_review(request, package, compiled, blind['review_id'])['status'])

    def test_generation_only_guidance_is_excluded_from_blind_interfaces_and_legacy_prompt(self):
        request, package, blind = focused_fixture()
        self.assertIn(FOCUSED_INFORMATION_GUIDANCE, stage_prompt(request))
        self.assertIn(FOCUSED_INFORMATION_GUIDANCE, generation_schema(request)['description'])
        self.assertNotIn(FOCUSED_INFORMATION_GUIDANCE, stage_prompt(request, delivery_kind=LEGACY_DELIVERY))
        for text in (stage_prompt(request, package=package, review_id=blind['review_id']),
                     json.dumps(review_schema(request, package, blind['review_id'])), json.dumps(candidate_view(request, package))):
            self.assertNotIn('FOCUSED CONTRAST PRACTICE', text)
            self.assertNotIn('at least one true', text)
            self.assertNotIn(package['reading_questions'][2]['absence_justification'], text)


if __name__ == '__main__': unittest.main()
