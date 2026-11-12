import copy
import unittest
from ielts_candidate import compile_candidate, validate_focused_sequence
from ielts_contract import ContractError
from test_ielts_visual import visual_fixture


class FocusedSequenceTests(unittest.TestCase):
    def fixture(self):
        request, package, _ = visual_fixture()
        q = copy.deepcopy(package['reading_questions'][1])
        request['reading_requests'] = [{'question_id': f'q{i+1}', 'type': 'identifying_information'} for i in range(3)]
        package['reading_questions'] = [dict(copy.deepcopy(q), question_id=f'q{i+1}') for i in range(3)]
        refs = []
        for paragraph in request['paragraphs']:
            refs.append({'paragraph_id': paragraph['paragraph_id'], 'start': paragraph['start'], 'end': paragraph['end'], 'quote': paragraph['text']})
        return request, package, refs

    def test_forward_and_shared_context_are_allowed(self):
        request, package, refs = self.fixture()
        for q, ref in zip(package['reading_questions'], [refs[0], refs[0], refs[-1]]):
            q.update(answer='true', source_refs=[ref])
        validate_focused_sequence(request, package)

    def test_backward_evidence_is_rejected_without_reordering_or_changing_answers(self):
        request, package, refs = self.fixture()
        for q, ref in zip(package['reading_questions'], [refs[-1], refs[0], refs[-1]]):
            q.update(answer='true', source_refs=[ref])
        original = copy.deepcopy(package)
        with self.assertRaises(ContractError) as caught:
            validate_focused_sequence(request, package)
        self.assertEqual('candidate_focused_evidence_order', caught.exception.code)
        self.assertEqual(original, package)

    def test_not_given_has_no_invented_anchor_but_does_not_reset_progress(self):
        request, package, refs = self.fixture()
        package['reading_questions'][0].update(answer='true', source_refs=[refs[-1]])
        package['reading_questions'][1].update(answer='not_given', source_refs=[])
        package['reading_questions'][2].update(answer='false', source_refs=[refs[0]])
        with self.assertRaises(ContractError):
            validate_focused_sequence(request, package)
        package['reading_questions'][2]['source_refs'] = [refs[-1]]
        validate_focused_sequence(request, package)

    def test_mixed_and_single_item_requests_keep_their_existing_scope(self):
        request, package, refs = self.fixture()
        request['reading_requests'][0]['type'] = 'multiple_choice'
        validate_focused_sequence(request, package)
        request['reading_requests'] = request['reading_requests'][1:2]
        validate_focused_sequence(request, package)


if __name__ == '__main__':
    unittest.main()
