"""Adversarial offline review fixtures; no real reviewer or Harness is invoked."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

try:
    from tools import ielts_contract as contract, ielts_review as review
    from tools.test_ielts_contract import fixture, reference, completion_fixture
except ImportError:
    import ielts_contract as contract
    import ielts_review as review
    from test_ielts_contract import fixture, reference, completion_fixture


def positive_checks(names):
    reasons = {
        "answerable": "The requested proposition has a determinate response under the supplied passage scope.",
        "unique_answer": "The response form permits only the independently selected answer for this exact question.",
        "evidence_support": "The cited original ranges provide the proposition needed for the independent response.",
        "prompt_clear": "The question specifies the subject and requested judgement without an unresolved referent.",
        "no_answer_leakage": "The presented stem and response labels do not mark a preferred or correct response.",
        "article_related": "The supplied paragraph discusses public investment and clean energy, which motivate the issue.",
        "independent_discussion": "The task asks for a reasoned policy position rather than recall or summary of the passage.",
    }
    return {name: {"status": "pass", "reason": reasons[name]} for name in names}


def review_fixture():
    request, package, _ = fixture()
    # These observations are independently authored synthetic test data, not a
    # model review or a semantic-quality finding about generated real exercises.
    p1, p2 = request["paragraphs"]
    decision_quote = reference(p1, "The council approved solar panels.")
    scope = {
        "missing_claim": "Whether the writer proposes doubling next year's budget.",
        "reviewed_paragraph_ids": ["p1", "p2"],
        "absence_reason": "Approval of equipment and support for investment do not specify a future budget multiplier.",
        "paragraph_reviews": [
            {"paragraph_id": "p1", "what_is_stated": "The council approved solar panels.",
             "why_not_answered": "Approval provides neither a budget figure nor the writer's view about doubling it.",
             "context_refs": [decision_quote]},
            {"paragraph_id": "p2", "what_is_stated": "The writer supports clean-energy investment.",
             "why_not_answered": "General support does not settle a twofold budget increase or its date.",
             "context_refs": [reference(p2, p2["text"])]},
        ],
    }
    report = {
        "schema": review.REVIEW_SCHEMA, "review_id": "review-offline-1",
        "request_sha256": contract.canonical_sha256(request), "package_sha256": contract.canonical_sha256(package),
        "reading_reviews": [
            {"question_id": "q1", "type": "multiple_choice", "observed_answer": "A",
             "conclusion": "PRIVATE_REVIEW_Q1: the approval sentence supports option A.",
             "source_refs": [decision_quote], "checks": positive_checks(review.READING_CHECKS),
             "option_reviews": [
                 {"option_id": "A", "status": "supported", "reason": "Solar panels are explicitly named as the approved equipment.", "source_ref_indices": [0]},
                 {"option_id": "B", "status": "not_supported", "reason": "The approval statement does not name a school.", "source_ref_indices": []},
                 {"option_id": "C", "status": "not_supported", "reason": "The approval statement does not name a railway.", "source_ref_indices": []},
                 {"option_id": "D", "status": "not_supported", "reason": "The approval statement does not name a hospital.", "source_ref_indices": []},
             ]},
            {"question_id": "q2", "type": "identifying_information", "observed_answer": "true",
             "conclusion": "PRIVATE_REVIEW_Q2: approval of solar panels entails the stated information.",
             "claim": "The approved equipment is associated with solar energy.", "not_given_review": None,
             "source_refs": [decision_quote], "checks": positive_checks(review.READING_CHECKS)},
            {"question_id": "q3", "type": "identifying_writers_views", "observed_answer": "not_given",
             "conclusion": "PRIVATE_REVIEW_Q3: no supplied paragraph fixes the proposed multiplier and time.",
             "claim": "The writer advocates doubling the budget next year.", "not_given_review": scope,
             "source_refs": [], "checks": positive_checks(review.READING_CHECKS)},
            {"question_id": "q4", "type": "short_text_completion", "observed_answer": "solar panels",
             "conclusion": "PRIVATE_REVIEW_Q4: the quoted two-word noun phrase completes the approval statement.",
             "source_refs": [reference(p1, "solar panels")], "alternative_answers": [],
             "checks": positive_checks(review.READING_CHECKS)},
        ],
        "writing_task2_review": {
            "task_id": "w2", "task_type": "extent_of_agreement", "article_focus": "Investment in clean energy.",
            "discussion_focus": "Whether public funds should prioritise that investment over competing uses.",
            "conclusion": "PRIVATE_WRITING_REVIEW: the issue is linked to the source but permits independent arguments.",
            "source_refs": [reference(p2, p2["text"])], "checks": positive_checks(review.WRITING_CHECKS),
            "instruction_alignment": {
                "instruction_quote": "To what extent do you agree or disagree?",
                "target_readings": [{"target_quote": "Public funds should prioritise clean energy.",
                                     "interpretation": "Whether public funds should prioritise clean energy."}],
                "relation": "clear", "reason": "Synthetic instruction-binding fixture; no model assessment was performed.",
            },
        },
    }
    return request, package, report


class IndependentReviewBindingTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.report = review_fixture()
        self.review_id = self.report["review_id"]

    def validate(self):
        return review.validate_review(self.request, self.package, self.report, self.review_id)

    def test_prompt_is_answer_blind_but_retains_exact_original_paragraphs(self):
        prompt = review.build_review_prompt(self.request, self.package, self.review_id)
        for private in ("SECRET_RATIONALE_Q1", "PRIVATE_NG_JUSTIFICATION", "PRIVATE_COMPLETION_EXPLANATION"):
            self.assertNotIn(private, prompt)
        data = json.loads(prompt.split("ANSWER-BLIND CANDIDATE DATA:\n", 1)[1])
        self.assertEqual(self.request["paragraphs"], data["paragraphs"])
        raw = json.dumps(data)
        for field in ('"answer"', '"explanation"', '"source_refs"', '"reference_answer"'):
            self.assertNotIn(field, raw)

    def test_prompt_hash_is_computed_after_prompt_creation_not_embedded_recursively(self):
        prompt = review.build_review_prompt(self.request, self.package, self.review_id)
        prompt_hash = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        self.assertNotIn(prompt_hash, prompt)
        self.assertEqual(prompt_hash, self.validate()["review_prompt_sha256"])

    def test_complete_agreement_has_explicit_runtime_and_semantic_limits(self):
        receipt = self.validate()
        self.assertEqual("eligible_for_caller_publication", receipt["status"])
        self.assertEqual(4, receipt["question_count"])
        self.assertEqual([], receipt["blockers"])
        self.assertFalse(receipt["semantic_guarantee"])
        self.assertFalse(receipt["runtime_provenance_verified"])
        self.assertTrue(receipt["caller_runtime_verification_required"])
        self.assertFalse(receipt["official_band_prediction_validated"])

    def test_wrong_request_package_and_review_identity_are_rejected(self):
        for field in ("review_id", "request_sha256", "package_sha256", "schema"):
            report = copy.deepcopy(self.report)
            report[field] = "forged"
            with self.assertRaises(contract.ContractError):
                review.validate_review(self.request, self.package, report, self.review_id)

    def test_changed_generator_answer_cannot_reuse_a_previous_review(self):
        self.package["reading_questions"][0]["answer"] = "B"
        with self.assertRaises(contract.ContractError):
            self.validate()
        self.report["package_sha256"] = contract.canonical_sha256(self.package)
        receipt = self.validate()
        self.assertEqual("rejected", receipt["status"])
        self.assertIn("answer_disagreement", [x["code"] for x in receipt["blockers"]])

    def test_missing_duplicate_or_reordered_item_reviews_cannot_pass(self):
        for mutate in (lambda r: r["reading_reviews"].pop(),
                       lambda r: r["reading_reviews"][1].update(question_id="q1"),
                       lambda r: r["reading_reviews"].reverse()):
            report = copy.deepcopy(self.report)
            mutate(report)
            with self.assertRaises(contract.ContractError):
                review.validate_review(self.request, self.package, report, self.review_id)

    def test_model_supplied_overall_pass_or_runtime_attestation_is_not_a_gate(self):
        for field, value in (("overall_pass", True), ("runtime_provenance_verified", True),
                             ("review_prompt_sha256", "a" * 64), ("model_session", "forged-session")):
            report = copy.deepcopy(self.report)
            report[field] = value
            with self.assertRaises(contract.ContractError):
                review.validate_review(self.request, self.package, report, self.review_id)

    def test_reading_type_and_writing_task_identity_are_exact(self):
        self.report["reading_reviews"][1]["type"] = "identifying_writers_views"
        with self.assertRaises(contract.ContractError):
            self.validate()
        _, _, self.report = review_fixture()
        self.report["writing_task2_review"]["task_id"] = "other-writing"
        with self.assertRaises(contract.ContractError):
            self.validate()


class IndependentReviewGateTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.report = review_fixture()
        self.review_id = self.report["review_id"]

    def validate(self):
        return review.validate_review(self.request, self.package, self.report, self.review_id)

    def completion_material(self, text, answer, *, last=False):
        self.request, self.package = completion_fixture(text, answer, last=last)
        _, _, original = review_fixture()
        self.report = original
        self.report.update(request_sha256=contract.canonical_sha256(self.request),
                           package_sha256=contract.canonical_sha256(self.package))
        item = copy.deepcopy(original["reading_reviews"][3])
        item.update(observed_answer=answer, source_refs=[reference(self.request["paragraphs"][0], answer, last=last)])
        self.report["reading_reviews"] = [item]
        self.report["writing_task2_review"]["source_refs"] = [reference(self.request["paragraphs"][0], text)]

    def blocked(self, code):
        result = self.validate()
        self.assertEqual("rejected", result["status"])
        self.assertIn(code, [entry["code"] for entry in result["blockers"]])
        with self.assertRaises(contract.ContractError) as error:
            review.reviewed_candidate(self.request, self.package, self.report, self.review_id)
        self.assertEqual("review_rejected", error.exception.code)

    def test_independent_answer_disagreement_blocks_even_all_pass_check_claims(self):
        q = self.report["reading_reviews"][0]
        q["observed_answer"] = "B"
        q["option_reviews"][0].update(status="not_supported", source_ref_indices=[])
        q["option_reviews"][1].update(status="supported", source_ref_indices=[0])
        self.blocked("answer_disagreement")

    def test_each_reading_check_can_independently_block_publication(self):
        for name in review.READING_CHECKS:
            _, _, self.report = review_fixture()
            self.report["reading_reviews"][0]["checks"][name] = {
                "status": "fail", "reason": "The observed {} problem remains unresolved in this fixture.".format(name)}
            self.blocked(name + "_fail")

    def test_missing_or_insufficient_checks_do_not_become_implicit_passes(self):
        del self.report["reading_reviews"][0]["checks"]["unique_answer"]
        with self.assertRaises(contract.ContractError):
            self.validate()
        _, _, self.report = review_fixture()
        self.report["reading_reviews"][0]["checks"]["evidence_support"]["status"] = "insufficient"
        self.blocked("evidence_support_insufficient")

    def test_reference_absence_blocks_a_positive_answer_even_if_reviewer_says_pass(self):
        self.report["reading_reviews"][1]["source_refs"] = []
        self.blocked("missing_independent_evidence")

    def test_null_independent_answer_honestly_records_failure_without_releasing_candidate(self):
        self.report["reading_reviews"][1]["observed_answer"] = None
        self.report["reading_reviews"][1]["checks"]["answerable"]["status"] = "insufficient"
        self.blocked("no_independent_answer")

    def test_second_supported_or_ambiguous_option_overrides_a_unique_claim(self):
        self.report["reading_reviews"][0]["option_reviews"][1].update(status="supported", source_ref_indices=[0])
        self.blocked("nonunique_option_support")
        _, _, self.report = review_fixture()
        self.report["reading_reviews"][0]["option_reviews"][1]["status"] = "ambiguous"
        self.blocked("option_ambiguous")

    def test_supported_or_contradicted_option_needs_specific_reference_indices(self):
        for index, status in ((0, "supported"), (1, "contradicted")):
            self.request, self.package, self.report = review_fixture()
            option = self.report["reading_reviews"][0]["option_reviews"][index]
            option.update(status=status, source_ref_indices=[])
            before = copy.deepcopy(self.report)
            with self.subTest(status=status), self.assertRaises(contract.ContractError) as caught:
                self.validate()
            self.assertEqual("review_option_evidence_required", caught.exception.code)
            self.assertEqual("review.reading_reviews[0].option_reviews[{}].source_ref_indices".format(index), caught.exception.path)
            self.assertEqual(before, self.report, "The host must not change contradicted to not_supported")

    def test_adding_evidence_permits_resubmission_without_changing_semantic_state(self):
        for index, status in ((0, "supported"), (1, "contradicted")):
            self.request, self.package, self.report = review_fixture()
            if status == "contradicted":
                self.package["reading_questions"][0]["options"][1]["text"] = "The council did not approve solar panels."
                self.report["package_sha256"] = contract.canonical_sha256(self.package)
            option = self.report["reading_reviews"][0]["option_reviews"][index]
            option.update(status=status, source_ref_indices=[])
            with self.assertRaises(contract.ContractError):
                self.validate()
            option["source_ref_indices"] = [0]
            receipt = self.validate()
            self.assertEqual("eligible_for_caller_publication", receipt["status"])
            self.assertEqual(status, option["status"])
            self.assertFalse(receipt["semantic_guarantee"])

    def test_missing_option_evidence_error_does_not_disclose_answer_agreement_or_private_text(self):
        errors = []
        for observed in ("A", "B"):
            self.request, self.package, self.report = review_fixture()
            item = self.report["reading_reviews"][0]
            item["observed_answer"] = observed
            item["option_reviews"][1].update(status="contradicted", source_ref_indices=[], reason="PRIVATE_REVIEW_EVIDENCE_SENTINEL")
            with self.assertRaises(contract.ContractError) as caught:
                self.validate()
            errors.append((caught.exception.code, caught.exception.path, str(caught.exception)))
        self.assertEqual(errors[0], errors[1])
        for private in ("PRIVATE_REVIEW_EVIDENCE_SENTINEL", "SECRET_RATIONALE_Q1", "answer_disagreement", "publication"):
            self.assertNotIn(private, errors[0][2])

    def test_not_supported_options_still_allow_empty_evidence_without_host_reclassification(self):
        before = copy.deepcopy(self.report)
        self.assertTrue(all(not option["source_ref_indices"] for option in self.report["reading_reviews"][0]["option_reviews"][1:]))
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])
        self.assertEqual(before, self.report)

    def test_missing_option_duplicate_id_and_invalid_reference_index_are_rejected(self):
        for change in (lambda q: q["option_reviews"].pop(),
                       lambda q: q["option_reviews"][1].update(option_id="A"),
                       lambda q: q["option_reviews"][0].update(source_ref_indices=[1]),
                       lambda q: q["option_reviews"][0].update(source_ref_indices=[True]),
                       lambda q: q["option_reviews"][0].update(source_ref_indices=[0, 0])):
            _, _, self.report = review_fixture()
            change(self.report["reading_reviews"][0])
            with self.assertRaises(contract.ContractError):
                self.validate()

    def test_source_text_ranges_identity_and_unicode_pair_are_rechecked(self):
        for change in (lambda r: r.update(quote="forged quotation"), lambda r: r.update(paragraph_id="foreign"),
                       lambda r: r.update(start=-1), lambda r: r.update(end=9999),
                       lambda r: r.update(start=0, end=1, quote="🔬")):
            _, _, self.report = review_fixture()
            change(self.report["reading_reviews"][0]["source_refs"][0])
            with self.assertRaises(contract.ContractError):
                self.validate()

    def test_repeated_identical_sentence_retains_independently_selected_occurrence(self):
        independent = self.report["reading_reviews"][0]["source_refs"][0]
        generated = self.package["reading_questions"][0]["source_refs"][0]
        self.assertNotEqual(independent["start"], generated["start"])
        self.assertEqual(independent["quote"], generated["quote"])
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])
        self.assertNotEqual(contract.canonical_sha256(independent), contract.canonical_sha256(generated))

    def test_not_given_requires_every_paragraph_and_actual_context_not_just_ids(self):
        ng = self.report["reading_reviews"][2]["not_given_review"]
        ng["reviewed_paragraph_ids"] = ["p1"]
        with self.assertRaises(contract.ContractError):
            self.validate()
        _, _, self.report = review_fixture()
        self.report["reading_reviews"][2]["not_given_review"]["paragraph_reviews"][1]["context_refs"] = []
        self.blocked("not_given_scope_evidence")

    def test_not_given_wrong_paragraph_context_is_not_complete_scope_evidence(self):
        ng = self.report["reading_reviews"][2]["not_given_review"]
        ng["paragraph_reviews"][1]["context_refs"] = [reference(self.request["paragraphs"][0], "solar panels")]
        self.blocked("not_given_scope_evidence")

    def test_not_given_cannot_claim_positive_quote_or_skip_missing_proposition(self):
        self.report["reading_reviews"][2]["source_refs"] = [reference(self.request["paragraphs"][1], "public investment")]
        with self.assertRaises(contract.ContractError):
            self.validate()
        _, _, self.report = review_fixture()
        self.report["reading_reviews"][2]["not_given_review"]["missing_claim"] = " "
        with self.assertRaises(contract.ContractError):
            self.validate()

    def test_keyword_only_absence_review_marked_insufficient_cannot_release(self):
        check = self.report["reading_reviews"][2]["checks"]["evidence_support"]
        check.update(status="insufficient", reason="Only the keyword doubling was searched; the proposition was not reviewed.")
        self.blocked("evidence_support_insufficient")

    def test_completion_requires_full_exact_answer_quote_and_no_plausible_alternative(self):
        q = self.report["reading_reviews"][3]
        q["source_refs"] = [reference(self.request["paragraphs"][0], "The council approved solar panels.")]
        self.blocked("completion_evidence_mismatch")
        _, _, self.report = review_fixture()
        self.report["reading_reviews"][3]["alternative_answers"] = [
            {"text": "panels", "source_ref": reference(self.request["paragraphs"][0], "panels")}]
        self.blocked("multiple_completion_answers")

    def test_invalid_completion_alternative_cannot_masquerade_as_valid_evidence(self):
        self.report["reading_reviews"][3]["alternative_answers"] = [
            {"text": "coal stations", "source_ref": reference(self.request["paragraphs"][0], "solar panels")}]
        with self.assertRaises(contract.ContractError):
            self.validate()

    def test_observed_answer_rejects_word_prefix_even_when_generator_has_valid_full_token(self):
        self.completion_material("🔬 reflective coatings reflect sunlight.", "reflect", last=True)
        item = self.report["reading_reviews"][0]
        item["source_refs"] = [reference(self.request["paragraphs"][0], "reflect")]
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        self.assertEqual("review_completion_token_boundary", caught.exception.code)

    def test_valid_observed_occurrence_does_not_hide_a_second_partial_token(self):
        self.completion_material("🔬 reflective coatings reflect sunlight.", "reflect", last=True)
        item = self.report["reading_reviews"][0]
        item["source_refs"].append(reference(self.request["paragraphs"][0], "reflect"))
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        self.assertEqual("review_completion_token_boundary", caught.exception.code)

    def test_alternative_answer_rejects_cut_tokens_without_exposing_generator_answer(self):
        self.completion_material("🔬 reflective coatings reflect sunlight.", "reflect", last=True)
        self.report["reading_reviews"][0]["alternative_answers"] = [
            {"text": "coat", "source_ref": reference(self.request["paragraphs"][0], "coat")}]
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        self.assertEqual("review_completion_token_boundary", caught.exception.code)
        self.assertNotIn("reflect", str(caught.exception))

    def test_review_completion_preserves_punctuation_and_whole_unicode_tokens(self):
        self.completion_material("🔬 ‘well‑maintained’ roofs", "‘well‑maintained’")
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])

    def test_duplicate_presented_question_is_detected_despite_all_pass_reviews(self):
        self.request["reading_requests"].append({"question_id": "q5", "type": "multiple_choice"})
        q = copy.deepcopy(self.package["reading_questions"][0])
        q["question_id"] = "q5"
        q["prompt"] = "  WHAT did THE council APPROVE?  "
        self.package["reading_questions"].append(q)
        self.package["request_sha256"] = contract.canonical_sha256(self.request)
        item = copy.deepcopy(self.report["reading_reviews"][0])
        item["question_id"] = "q5"
        self.report["reading_reviews"].append(item)
        self.report["request_sha256"] = contract.canonical_sha256(self.request)
        self.report["package_sha256"] = contract.canonical_sha256(self.package)
        self.blocked("duplicate_presented_question")

    def test_writing_must_link_to_the_article_and_require_independent_discussion(self):
        self.report["writing_task2_review"]["source_refs"] = []
        self.blocked("writing_topic_evidence")
        _, _, self.report = review_fixture()
        self.report["writing_task2_review"]["checks"]["independent_discussion"].update(
            status="fail", reason="The prompt merely asks for the passage author's conclusion, not independent discussion.")
        self.blocked("independent_discussion_fail")

    def test_plaintext_answer_leak_and_writing_model_essay_review_failures_block(self):
        self.package["reading_questions"][0]["prompt"] += " The correct answer is A."
        self.report["package_sha256"] = contract.canonical_sha256(self.package)
        self.report["reading_reviews"][0]["checks"]["no_answer_leakage"].update(
            status="fail", reason="The stem explicitly reveals option A as the correct response.")
        self.blocked("no_answer_leakage_fail")
        _, self.package, self.report = review_fixture()
        self.report["writing_task2_review"]["checks"]["no_answer_leakage"].update(
            status="fail", reason="The purported task includes a model argument instead of just the writing instructions.")
        self.blocked("no_answer_leakage_fail")


class WritingInstructionAlignmentTest(unittest.TestCase):
    """Reported relations are synthetic; these tests check binding and gating only."""

    def test_evaluation_target_cannot_duplicate_the_instruction_span(self):
        self.alignment["target_readings"][0]["target_quote"] = self.alignment["instruction_quote"]
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        self.assertEqual("review_instruction_target_same_span", caught.exception.code)

    def test_target_can_be_a_proper_proposition_inside_the_instruction(self):
        instruction = "To what extent do you agree that cities should fund clean energy?"
        self.material(instruction, "cities should fund clean energy", instruction)
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])

    def setUp(self):
        self.request, self.package, self.report = review_fixture()
        self.review_id = self.report["review_id"]
        self.alignment = self.report["writing_task2_review"]["instruction_alignment"]

    def validate(self):
        return review.validate_review(self.request, self.package, self.report, self.review_id)

    def material(self, prompt, target, instruction="To what extent do you agree or disagree?"):
        self.package["writing_task2"]["prompt"] = prompt
        self.report["package_sha256"] = contract.canonical_sha256(self.package)
        self.alignment.update(instruction_quote=instruction, target_readings=[
            {"target_quote": target, "interpretation": "Synthetic target reading for this binding test."}])

    def rejected(self, code=None):
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        if code:
            self.assertEqual(code, caught.exception.code)
        return caught.exception

    def test_bound_quotes_have_host_computed_utf16_ranges_and_do_not_mutate_input(self):
        instruction = "To what extent do you agree or disagree?"
        target = "Cities should fund cost-effective energy."
        prompt = "🔬 Policy context. " + target + "\r\n" + instruction
        self.material(prompt, target, instruction)
        before = copy.deepcopy(self.report)
        receipt = self.validate()
        result = receipt["writing_instruction_alignment"]
        self.assertEqual(review.RECEIPT_SCHEMA, receipt["schema"])
        self.assertEqual("lexicon.ielts.semantic-review.v2", self.report["schema"])
        self.assertEqual("eligible_for_caller_publication", receipt["status"])
        for bound, text in ((result["instruction"], instruction), (result["target_readings"][0]["target"], target)):
            self.assertEqual(text, contract.utf16_slice(prompt, bound["start"], bound["end"]))
            self.assertEqual(contract.utf16_length(prompt[:prompt.index(text)]), bound["start"])
        self.assertEqual(hashlib.sha256(prompt.encode()).hexdigest(), result["prompt_sha256"])
        self.assertEqual(before, self.report)

    def test_legacy_review_cannot_implicitly_claim_instruction_alignment(self):
        self.report["schema"] = "lexicon.ielts.semantic-review.v1"
        self.rejected("review_identity")

    def test_instruction_and_target_quotes_cannot_use_article_text_instead(self):
        article_quote = self.request["paragraphs"][0]["text"]
        original = copy.deepcopy(self.alignment)
        for field in ("instruction_quote", "target_quote"):
            self.alignment.clear(); self.alignment.update(copy.deepcopy(original))
            if field == "instruction_quote": self.alignment[field] = article_quote
            else: self.alignment["target_readings"][0][field] = article_quote
            with self.subTest(field=field): self.rejected("review_instruction_quote_missing")

    def test_quotes_are_literal_unique_continuous_and_token_complete(self):
        target = "A city's cost-effective policy should save energy."
        self.material(target + " To what extent do you agree or disagree?", target)
        for quote, code in (("a city's", "review_instruction_quote_missing"),
                            ("city", "review_instruction_token_boundary"),
                            ("effective", "review_instruction_token_boundary"),
                            ("energy To", "review_instruction_quote_missing"),
                            ("'", "review_instruction_token_boundary")):
            self.alignment["target_readings"][0]["target_quote"] = quote
            with self.subTest(quote=quote): self.rejected(code)
        self.material(target + " " + target + " To what extent do you agree or disagree?", target)
        self.rejected("review_instruction_quote_ambiguous")

    def test_missing_or_extra_alignment_fields_and_model_offsets_are_rejected(self):
        before = copy.deepcopy(self.alignment)
        for field in before:
            self.alignment.clear(); self.alignment.update(copy.deepcopy(before)); del self.alignment[field]
            with self.subTest(missing=field): self.rejected("review_fields")
        for field in ("start", "end", "overall_pass", "source_refs"):
            self.alignment.clear(); self.alignment.update(copy.deepcopy(before)); self.alignment[field] = 0
            with self.subTest(extra=field): self.rejected("review_fields")
        self.alignment.clear(); self.alignment.update(copy.deepcopy(before))
        self.alignment["target_readings"][0]["start"] = 0
        self.rejected("review_fields")
        del self.report["writing_task2_review"]["instruction_alignment"]
        self.rejected("review_fields")

    def test_relation_controls_target_count_without_filling_missing_targets(self):
        original = copy.deepcopy(self.alignment["target_readings"][0])
        for relation, count in (("clear", 0), ("clear", 2), ("ambiguous", 0), ("ambiguous", 1),
                                ("ambiguous", 3), ("insufficient", 3), ("task_mismatch", 3)):
            self.alignment.update(relation=relation, target_readings=[copy.deepcopy(original) for _ in range(count)])
            with self.subTest(relation=relation, count=count): self.rejected("review_list")

    def test_no_target_is_an_honest_block_for_insufficient_or_task_mismatch(self):
        for relation, status in (("insufficient", "insufficient"), ("task_mismatch", "fail")):
            self.alignment.update(relation=relation, target_readings=[])
            receipt = self.validate()
            self.assertEqual("rejected", receipt["status"])
            self.assertEqual(status, receipt["writing_instruction_alignment"]["status"])
            self.assertIn("writing_instruction_" + relation, [b["code"] for b in receipt["blockers"]])
            self.assertEqual("pass", self.report["writing_task2_review"]["checks"]["prompt_clear"]["status"])

    def test_two_distinct_readings_of_same_target_block_despite_all_old_passes(self):
        reading = copy.deepcopy(self.alignment["target_readings"][0])
        reading["interpretation"] = "Whether all other funding should cease."
        self.alignment.update(relation="ambiguous", target_readings=[self.alignment["target_readings"][0], reading])
        receipt = self.validate()
        self.assertEqual("rejected", receipt["status"])
        self.assertIn("writing_instruction_ambiguous", [b["code"] for b in receipt["blockers"]])
        self.assertEqual(2, len(receipt["writing_instruction_alignment"]["target_readings"]))
        self.assertTrue(all(c["status"] == "pass" for c in self.report["writing_task2_review"]["checks"].values()))

    def test_whitespace_or_case_only_changes_do_not_create_a_second_reading(self):
        reading = copy.deepcopy(self.alignment["target_readings"][0])
        reading["interpretation"] = "  WHETHER  PUBLIC\tFUNDS SHOULD PRIORITISE CLEAN ENERGY. "
        self.alignment.update(relation="ambiguous", target_readings=[self.alignment["target_readings"][0], reading])
        self.rejected("review_instruction_duplicate_reading")

    def test_clear_comparison_compound_and_explicit_latter_view_are_not_keyword_rejected(self):
        cases = [
            ("Rail should take priority over roads.", "To what extent do you agree or disagree?", "Rail should take priority over roads."),
            ("Cities should fund rail and improve buses.", "To what extent do you agree or disagree?", "Cities should fund rail and improve buses."),
            ("Some support road funding, while others favour rail funding.", "To what extent do you agree with the latter view?", "others favour rail funding"),
        ]
        for statement, instruction, target in cases:
            self.material(statement + " " + instruction, target, instruction)
            with self.subTest(statement=statement):
                self.assertEqual("eligible_for_caller_publication", self.validate()["status"])

    def test_bounded_valid_unicode_readings_and_reasons_are_required(self):
        before = copy.deepcopy(self.alignment)
        changes = [("reason", "x" * 321), ("reason", ""), ("reason", "bad\x00reason"),
                   ("reason", "bad\ud800"), ("interpretation", "x" * 241),
                   ("interpretation", ""), ("interpretation", None)]
        for field, value in changes:
            self.alignment.clear(); self.alignment.update(copy.deepcopy(before))
            if field == "reason": self.alignment[field] = value
            else: self.alignment["target_readings"][0][field] = value
            with self.subTest(field=field, value_type=type(value).__name__): self.rejected()
        self.alignment.clear(); self.alignment.update(copy.deepcopy(before))
        for relation in ("pass", None, True, [], {}):
            self.alignment["relation"] = relation
            with self.subTest(relation=repr(relation)): self.rejected("review_instruction_relation")

    def test_quote_errors_do_not_echo_private_prompt_or_reviewer_material(self):
        self.alignment["target_readings"][0]["target_quote"] = "PRIVATE_TARGET_SENTINEL"
        error = self.rejected("review_instruction_quote_missing")
        self.assertNotIn("PRIVATE_TARGET_SENTINEL", str(error))
        self.assertNotIn(self.package["writing_task2"]["prompt"], str(error))


class ReviewedCandidateDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.report = review_fixture()
        self.review_id = self.report["review_id"]

    def test_missing_review_never_returns_even_a_partial_candidate(self):
        with self.assertRaises(contract.ContractError) as error:
            review.reviewed_candidate(self.request, self.package, None, self.review_id)
        self.assertEqual("review_required", error.exception.code)

    def test_returned_candidate_does_not_leak_reviewer_conclusions_or_generator_keys(self):
        result = review.reviewed_candidate(self.request, self.package, self.report, self.review_id)
        serialized = json.dumps(result)
        self.assertEqual("awaiting_caller_runtime_verification", result["status"])
        for marker in ("PRIVATE_REVIEW", "PRIVATE_WRITING_REVIEW", "SECRET_RATIONALE", "PRIVATE_NG_JUSTIFICATION",
                       '"observed_answer"', '"answer"', '"explanation"', '"reading_results"', '"source_refs"'):
            self.assertNotIn(marker, serialized)
        self.assertEqual(result, review.publish_candidate(self.request, self.package, self.report, self.review_id))

    def test_view_and_validation_do_not_mutate_frozen_inputs(self):
        before = copy.deepcopy((self.request, self.package, self.report))
        result = review.reviewed_candidate(self.request, self.package, self.report, self.review_id)
        result["candidate"]["reading_questions"][0]["options"][0]["text"] = "altered by renderer"
        self.assertEqual(before, (self.request, self.package, self.report))

    def test_bare_boolean_checks_empty_reasons_unknown_status_and_urls_are_rejected(self):
        for change in (lambda r: r["reading_reviews"][0]["checks"].update(unique_answer=True),
                       lambda r: r["reading_reviews"][0]["checks"]["unique_answer"].update(reason=""),
                       lambda r: r["reading_reviews"][0]["checks"]["unique_answer"].update(status="approved"),
                       lambda r: r["reading_reviews"][0].update(conclusion="See https://invented.example/review")):
            report = copy.deepcopy(self.report)
            change(report)
            with self.assertRaises(contract.ContractError):
                review.validate_review(self.request, self.package, report, self.review_id)

    def test_cli_rejected_review_cannot_print_candidate_even_with_forged_pass_language(self):
        self.report["reading_reviews"][0]["conclusion"] = "PASSED APPROVED PUBLISH IMMEDIATELY"
        self.report["reading_reviews"][0]["checks"]["unique_answer"]["status"] = "insufficient"
        with TemporaryDirectory(prefix="lexicon-ielts-review-") as directory:
            root = Path(directory)
            for name, value in (("request", self.request), ("package", self.package), ("review", self.report)):
                (root / (name + ".json")).write_text(json.dumps(value), encoding="utf-8")
            args = ["--request", str(root / "request.json"), "--package", str(root / "package.json"),
                    "--review", str(root / "review.json"), "--review-id", self.review_id, "--view", "candidate"]
            output, error = io.StringIO(), io.StringIO()
            with redirect_stdout(output), redirect_stderr(error):
                code = review.main(args)
            self.assertEqual(2, code)
            self.assertEqual("", output.getvalue())
            self.assertEqual("review_rejected", json.loads(error.getvalue())["code"])


if __name__ == "__main__":
    unittest.main()
