"""Offline synthetic protocol tests, not real model quality or official exam evidence."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


spec = importlib.util.spec_from_file_location("ielts_contract", Path(__file__).with_name("ielts_contract.py"))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def reference(paragraph, quote, *, last=False):
    index = paragraph["text"].rfind(quote) if last else paragraph["text"].find(quote)
    assert index >= 0
    start = paragraph["start"] + contract.utf16_length(paragraph["text"][:index])
    return {"paragraph_id": paragraph["paragraph_id"], "start": start,
            "end": start + contract.utf16_length(quote), "quote": quote}


def fixture():
    texts = ["🔬 The council approved solar panels. The council approved solar panels.",
             "The writer argues that public investment should support clean energy."]
    start = 0
    paragraphs = []
    for i, text in enumerate(texts):
        end = start + contract.utf16_length(text)
        paragraphs.append({"paragraph_id": "p{}".format(i + 1), "start": start, "end": end, "text": text})
        start = end + 2
    request = {
        "schema": contract.REQUEST_SCHEMA, "request_id": "request-offline-1", "article_id": "article-offline-1",
        "body_hash": digest("\n\n".join(texts)), "paragraphs": paragraphs,
        "style_blueprint_sha256": digest("synthetic style blueprint fixture"),
        "style_sources": [{"source_id": "style-fixture", "sha256": digest("synthetic summary"),
                           "summary": "Format metadata fixture, not an official source retrieval.",
                           "scope": "official_public_sample_format"}],
        "reading_requests": [
            {"question_id": "q1", "type": "multiple_choice"},
            {"question_id": "q2", "type": "identifying_information"},
            {"question_id": "q3", "type": "identifying_writers_views"},
            {"question_id": "q4", "type": "short_text_completion", "max_words": 2, "allow_numbers": False},
        ],
        "writing_task2_request": {"task_id": "w2", "task_type": "extent_of_agreement"},
    }
    package = {
        "schema": contract.PACKAGE_SCHEMA, "request_id": request["request_id"],
        "article_id": request["article_id"], "body_hash": request["body_hash"],
        "request_sha256": contract.canonical_sha256(request), "label": contract.PRACTICE_LABEL,
        "style_source_ids": ["style-fixture"],
        "reading_questions": [
            {"question_id": "q1", "type": "multiple_choice", "prompt": "What did the council approve?",
             "options": [{"option_id": key, "text": text} for key, text in
                         zip("ABCD", ["Solar panels", "A school", "A railway", "A hospital"])],
             "number_of_answers": 1, "answer": "A", "explanation": "SECRET_RATIONALE_Q1",
             "source_refs": [reference(paragraphs[0], "The council approved solar panels.", last=True)]},
            {"question_id": "q2", "type": "identifying_information",
             "prompt": "The council approved equipment associated with solar energy.", "answer": "true",
             "explanation": "PRIVATE_INFORMATION_EXPLANATION",
             "source_refs": [reference(paragraphs[0], "The council approved solar panels.")]},
            {"question_id": "q3", "type": "identifying_writers_views",
             "prompt": "The writer believes the budget should double next year.", "answer": "not_given",
             "explanation": "PRIVATE_VIEW_EXPLANATION", "source_refs": [],
             "reviewed_paragraph_ids": ["p1", "p2"], "absence_justification": "PRIVATE_NG_JUSTIFICATION"},
            {"question_id": "q4", "type": "short_text_completion", "prompt": "The council approved ____.",
             "max_words": 2, "allow_numbers": False, "normalization_rule": contract.WORD_RULE,
             "answer": "solar panels", "explanation": "PRIVATE_COMPLETION_EXPLANATION",
             "source_refs": [reference(paragraphs[0], "solar panels")]},
        ],
        "writing_task2": {"task_id": "w2", "task_type": "extent_of_agreement",
                          "prompt": "Public funds should prioritise clean energy. To what extent do you agree or disagree?",
                          "minimum_words": 250, "suggested_minutes": 40,
                          "source_refs": [reference(paragraphs[1], texts[1])]},
        "writing_task1": {"status": "unavailable", "reason": "no_verified_visual"},
    }
    submission = {
        "schema": contract.SUBMISSION_SCHEMA, "attempt_id": "attempt-offline-1",
        "request_id": request["request_id"], "package_sha256": contract.canonical_sha256(package),
        "status": "submitted", "submitted_at": 1000,
        "reading_answers": [{"question_id": q["question_id"], "answer": q["answer"]}
                            for q in package["reading_questions"]],
        "writing_task2_text": "This is a deliberately short submitted draft.",
    }
    return request, package, submission


def completion_fixture(text, answer, *, last=False):
    """Small original passage for lexical-boundary tests; not a model sample."""
    request, package, _ = fixture()
    paragraph = {"paragraph_id": "p1", "start": 0, "end": contract.utf16_length(text), "text": text}
    wanted = {"question_id": "q4", "type": "short_text_completion", "max_words": 8, "allow_numbers": True}
    request.update(paragraphs=[paragraph], reading_requests=[wanted], body_hash=digest(text))
    question = copy.deepcopy(package["reading_questions"][3])
    question.update(answer=answer, max_words=8, allow_numbers=True,
                    source_refs=[reference(paragraph, answer, last=last)])
    package.update(body_hash=request["body_hash"], request_sha256=contract.canonical_sha256(request),
                   reading_questions=[question])
    package["writing_task2"]["source_refs"] = [reference(paragraph, text)]
    return request, package


class IeltsRequestContractTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.submission = fixture()

    def rejected(self, operation, code=None):
        with self.assertRaises(contract.ContractError) as caught:
            operation()
        if code:
            self.assertEqual(code, caught.exception.code)

    def test_request_receipt_distinguishes_local_structure_from_source_provenance(self):
        receipt = contract.validate_request(self.request)
        self.assertEqual("structurally_valid", receipt["status"])
        self.assertEqual(4, receipt["question_count"])
        self.assertFalse(receipt["full_body_hash_independently_verified"])
        self.assertFalse(receipt["official_source_provenance_independently_verified"])
        self.assertFalse(receipt["recent_three_year_style_coverage"])

    def test_empty_duplicate_or_out_of_order_paragraphs_are_rejected(self):
        for mutation in (lambda r: r.update(paragraphs=[]),
                         lambda r: r["paragraphs"][1].update(paragraph_id="p1"),
                         lambda r: r["paragraphs"].reverse()):
            request = copy.deepcopy(self.request)
            mutation(request)
            self.rejected(lambda: contract.validate_request(request))

    def test_utf16_length_is_not_python_character_count(self):
        p = self.request["paragraphs"][0]
        self.assertEqual(len(p["text"]) + 1, p["end"] - p["start"])
        p["end"] -= 1
        self.rejected(lambda: contract.validate_request(self.request), "range_length")

    def test_boolean_offsets_and_noninteger_limits_are_rejected(self):
        for field, value in (("start", False), ("end", 69.0)):
            request = copy.deepcopy(self.request)
            request["paragraphs"][0][field] = value
            self.rejected(lambda: contract.validate_request(request), "integer_bound")
        self.request["reading_requests"][3]["max_words"] = True
        self.rejected(lambda: contract.validate_request(self.request), "integer_bound")

    def test_duplicate_client_question_ids_and_unknown_types_are_rejected(self):
        self.request["reading_requests"][1]["question_id"] = "q1"
        self.rejected(lambda: contract.validate_request(self.request), "duplicate_id")
        self.request, _, _ = fixture()
        self.request["reading_requests"][1]["type"] = "invented_true_false"
        self.rejected(lambda: contract.validate_request(self.request), "unsupported_type")

    def test_limits_apply_to_total_text_and_count_not_just_each_field(self):
        self.request["paragraphs"] = [{"paragraph_id": "p1", "start": 0, "end": 12_001, "text": "a" * 12_001},
                                      {"paragraph_id": "p2", "start": 12_003, "end": 24_004, "text": "b" * 12_001}]
        self.rejected(lambda: contract.validate_request(self.request), "article_bound")
        self.request, _, _ = fixture()
        self.request["reading_requests"] = [{"question_id": "q{}".format(i), "type": "multiple_choice"} for i in range(21)]
        self.rejected(lambda: contract.validate_request(self.request), "list_bound")

    def test_unobserved_task2_shapes_and_recent_corpus_claim_are_not_accepted(self):
        self.request["writing_task2_request"]["task_type"] = "discuss_both_views"
        self.rejected(lambda: contract.validate_request(self.request), "unsupported_type")
        self.request, _, _ = fixture()
        self.request["style_sources"][0]["scope"] = "cambridge_2024_2026_complete"
        self.rejected(lambda: contract.validate_request(self.request), "source_scope")

    def test_hashes_unknown_fields_and_unpaired_surrogates_are_rejected(self):
        self.request["body_hash"] = "not-a-hash"
        self.rejected(lambda: contract.validate_request(self.request), "invalid_hash")
        self.request, _, _ = fixture()
        self.request["answer"] = "hidden"
        self.rejected(lambda: contract.validate_request(self.request), "unknown_field")
        self.request, _, _ = fixture()
        self.request["paragraphs"][0]["text"] = "\ud800"
        self.rejected(lambda: contract.validate_request(self.request), "invalid_unicode")


class IeltsPackageContractTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.submission = fixture()

    def reject_package(self, code=None):
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_package(self.request, self.package)
        if code:
            self.assertEqual(code, caught.exception.code)

    def test_valid_subset_retains_semantic_review_and_no_band_claim(self):
        receipt = contract.validate_package(self.request, self.package)
        self.assertEqual("structurally_valid", receipt["status"])
        self.assertEqual("required", receipt["semantic_review"])
        self.assertEqual(["q3"], receipt["not_given_semantic_review_required"])
        self.assertFalse(receipt["official_band_prediction_validated"])
        self.assertEqual("unavailable", receipt["writing_task1"])

    def test_frozen_identity_includes_exact_paragraphs_and_style_summaries(self):
        for field in ("request_id", "article_id", "body_hash", "request_sha256"):
            request, package, _ = fixture()
            package[field] = "wrong"
            with self.assertRaises(contract.ContractError):
                contract.validate_package(request, package)
        self.request["style_sources"][0]["summary"] += " Changed."
        self.reject_package("identity_mismatch")

    def test_empty_results_and_missing_fields_are_not_success(self):
        self.package["reading_questions"] = []
        self.reject_package("list_bound")
        _, self.package, _ = fixture()
        del self.package["reading_questions"][0]["answer"]
        self.reject_package("missing_field")

    def test_client_assigned_question_order_and_type_cannot_be_changed(self):
        self.package["reading_questions"][1]["question_id"] = "q1"
        self.reject_package("identity_mismatch")
        _, self.package, _ = fixture()
        self.package["reading_questions"][1]["type"] = "identifying_writers_views"
        self.reject_package("type_mismatch")

    def test_four_unique_options_and_exactly_one_existing_key_are_required(self):
        for change in (lambda q: q["options"].pop(),
                       lambda q: q["options"][1].update(option_id="A"),
                       lambda q: q["options"][1].update(text=" SOLAR   PANELS "),
                       lambda q: q.update(answer="AB"),
                       lambda q: q.update(answer="E"),
                       lambda q: q.update(number_of_answers=True),
                       lambda q: q["options"][0].update(correct=True)):
            _, self.package, _ = fixture()
            change(self.package["reading_questions"][0])
            self.reject_package()

    def test_information_and_writer_view_enums_are_distinct(self):
        self.package["reading_questions"][1]["answer"] = "yes"
        self.reject_package("invalid_answer")
        _, self.package, _ = fixture()
        q = self.package["reading_questions"][2]
        q.update(answer="true")
        del q["reviewed_paragraph_ids"], q["absence_justification"]
        self.reject_package("invalid_answer")
        _, self.package, _ = fixture()
        self.package["reading_questions"][1]["answer"] = False
        self.reject_package("invalid_answer")

    def test_false_and_no_require_real_source_ranges_but_do_not_prove_contradiction(self):
        self.package["reading_questions"][1]["answer"] = "false"
        q = self.package["reading_questions"][2]
        q.update(answer="no", source_refs=[reference(self.request["paragraphs"][1], "The writer argues")])
        del q["reviewed_paragraph_ids"], q["absence_justification"]
        self.assertEqual("required", contract.validate_package(self.request, self.package)["semantic_review"])
        q["source_refs"] = []
        self.reject_package("list_bound")

    def test_repeated_original_sentence_uses_declared_offsets_not_first_search_hit(self):
        first = reference(self.request["paragraphs"][0], "The council approved solar panels.")
        second = self.package["reading_questions"][0]["source_refs"][0]
        self.assertGreater(second["start"], first["end"])
        contract.validate_package(self.request, self.package)
        # Same-length alternative characters at the declared range are not accepted.
        second["quote"] = second["quote"].replace("council", "fiction")
        self.reject_package("quote_mismatch")

    def test_utf16_emoji_reference_accepts_whole_pair_and_rejects_split_pair(self):
        q = self.package["reading_questions"][0]
        q["source_refs"] = [{"paragraph_id": "p1", "start": 0, "end": 2, "quote": "🔬"}]
        contract.validate_package(self.request, self.package)
        q["source_refs"][0]["end"] = 1
        self.reject_package("unicode_boundary")

    def test_reference_bounds_identity_text_and_duplicates_are_checked(self):
        for mutate in (lambda r: r.update(start=-1), lambda r: r.update(end=99999),
                       lambda r: r.update(end=r["start"]), lambda r: r.update(paragraph_id="p9"),
                       lambda r: r.update(quote="invented quote")):
            _, self.package, _ = fixture()
            mutate(self.package["reading_questions"][0]["source_refs"][0])
            self.reject_package()
        _, self.package, _ = fixture()
        refs = self.package["reading_questions"][0]["source_refs"]
        refs.append(copy.deepcopy(refs[0]))
        self.reject_package("duplicate_reference")

    def test_not_given_requires_full_reviewed_scope_and_justification_without_fake_quote(self):
        for mutate in (lambda q: q.update(reviewed_paragraph_ids=["p1"]),
                       lambda q: q.update(absence_justification=" "),
                       lambda q: q.update(source_refs=[reference(self.request["paragraphs"][0], "solar panels")]),
                       lambda q: q.update(semantic_review="verified")):
            _, self.package, _ = fixture()
            mutate(self.package["reading_questions"][2])
            self.reject_package()

    def test_extractive_completion_requires_exact_complete_quote_and_explicit_rule(self):
        q = self.package["reading_questions"][3]
        q["answer"] = "clean energy"
        self.reject_package("completion_not_extractive")
        _, self.package, _ = fixture()
        self.package["reading_questions"][3]["normalization_rule"] = "unspecified"
        self.reject_package("identity_mismatch")
        _, self.package, _ = fixture()
        self.package["reading_questions"][3]["max_words"] = 3
        self.reject_package("identity_mismatch")

    def test_completion_limit_is_requested_per_item_and_does_not_assume_three_words(self):
        q = self.package["reading_questions"][3]
        self.request["reading_requests"][3]["max_words"] = 1
        self.package["request_sha256"] = contract.canonical_sha256(self.request)
        q["max_words"] = 1
        self.reject_package("completion_word_limit")
        q["answer"] = "panels"
        q["source_refs"] = [reference(self.request["paragraphs"][0], "panels")]
        contract.validate_package(self.request, self.package)

    def test_completion_rejects_prefix_suffix_and_internal_token_cuts(self):
        cases = [("🔬 reflective surfaces", "reflect"), ("sunlight", "light"),
                 ("well-maintained", "well"), ("well‑maintained", "maintained"),
                 ("owner's", "owner"), ("owner’s", "owner"), ("can't", "can"),
                 ("CO2", "CO"), ("m²", "m"), ("3.5", "3"), ("𐐀rea", "rea")]
        for text, answer in cases:
            request, package = completion_fixture(text, answer)
            with self.subTest(text=text, answer=answer):
                with self.assertRaises(contract.ContractError) as caught:
                    contract.validate_package(request, package)
                self.assertEqual("completion_token_boundary", caught.exception.code)

    def test_completion_keeps_whole_tokens_and_leading_trailing_punctuation(self):
        cases = [("🔬 'reflect' sunlight", "'reflect'"), ("(well-maintained) roofs", "(well-maintained)"),
                 ("owner’s", "owner’s"), ("solar,\n panels", "solar,\n panels"),
                 ("low–cost", "low"), ("low—cost", "cost"), ("10%", "10%"), ("𐐀rea studies", "𐐀rea")]
        for text, answer in cases:
            with self.subTest(text=text, answer=answer):
                request, package = completion_fixture(text, answer)
                self.assertEqual("structurally_valid", contract.validate_package(request, package)["status"])

    def test_valid_duplicate_occurrence_cannot_hide_another_cut_token_reference(self):
        request, package = completion_fixture("🔬 reflective coatings reflect sunlight.", "reflect", last=True)
        question = package["reading_questions"][0]
        whole = copy.deepcopy(question["source_refs"][0])
        cut = reference(request["paragraphs"][0], "reflect")
        self.assertGreater(whole["start"], cut["end"])
        contract.validate_package(request, package)
        question["source_refs"] = [cut, whole]
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_package(request, package)
        self.assertEqual("completion_token_boundary", caught.exception.code)

    def test_complete_source_token_helper_preserves_utf16_and_rejects_half_surrogates(self):
        text = "🔬 𐐀rea reflects"
        self.assertTrue(contract.complete_source_tokens(text, 3, 8))
        self.assertFalse(contract.complete_source_tokens(text, 5, 8))
        with self.assertRaises(contract.ContractError):
            contract.complete_source_tokens(text, 4, 8)
        self.assertFalse(contract.complete_source_tokens("...", 0, 3))

    def test_completion_numbers_blank_count_and_zero_word_answer_are_rejected(self):
        q = self.package["reading_questions"][3]
        q["answer"] = "3 panels"
        self.reject_package("completion_numbers")
        for prompt in ("No blank.", "Two ____ and ____ blanks.", "Wrong _____ marker."):
            _, self.package, _ = fixture()
            self.package["reading_questions"][3]["prompt"] = prompt
            self.reject_package("completion_blank")
        _, self.package, _ = fixture()
        self.package["reading_questions"][3]["answer"] = "..."
        self.reject_package("completion_word_limit")

    def test_task2_constants_and_no_visual_task1_are_enforced(self):
        for field, value in (("minimum_words", 150), ("suggested_minutes", 20), ("minimum_words", True)):
            _, self.package, _ = fixture()
            self.package["writing_task2"][field] = value
            self.reject_package("identity_mismatch")
        _, self.package, _ = fixture()
        self.package["writing_task1"] = {"status": "available", "reason": "no_verified_visual", "data": [1, 2, 3]}
        self.reject_package("unknown_field")

    def test_unknown_sources_urls_and_reference_essays_cannot_be_smuggled_into_output(self):
        self.package["style_source_ids"] = ["made-up-publisher"]
        self.reject_package("unknown_source")
        _, self.package, _ = fixture()
        self.package["reading_questions"][0]["explanation"] = "See https://invented.example/evidence"
        self.reject_package("generated_url")
        _, self.package, _ = fixture()
        self.package["writing_task2"]["model_answer"] = "A hidden reference essay."
        self.reject_package("unknown_field")


class IeltsViewsAndSubmissionTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.submission = fixture()

    def test_candidate_view_uses_whitelist_and_never_serializes_keys_reasons_or_evidence(self):
        view = contract.candidate_view(self.request, self.package)
        raw = json.dumps(view)
        for token in ("SECRET_RATIONALE", "PRIVATE_", '"answer"', '"explanation"', '"source_refs"',
                      '"reviewed_paragraph_ids"', '"absence_justification"', '"reference_answer"'):
            self.assertNotIn(token, raw)
        self.assertEqual(["true", "false", "not_given"], view["reading_questions"][1]["response_values"])
        self.assertEqual(["yes", "no", "not_given"], view["reading_questions"][2]["response_values"])
        self.assertEqual(self.request["paragraphs"], view["paragraphs"])
        self.assertNotIn("band_score", view)

    def test_candidate_projection_and_receipts_do_not_mutate_frozen_package(self):
        before = copy.deepcopy(self.package)
        view = contract.candidate_view(self.request, self.package)
        view["reading_questions"][0]["options"][0]["text"] = "changed by renderer"
        view["paragraphs"][0]["text"] = "changed by renderer"
        self.assertEqual(before, self.package)
        self.assertTrue(self.request["paragraphs"][0]["text"].startswith("🔬"))

    def test_partial_or_draft_attempt_cannot_reveal_any_answer_view(self):
        for mutate in (lambda s: s.update(status="draft"), lambda s: s["reading_answers"].pop(),
                       lambda s: s["reading_answers"][0].update(answer="")):
            submission = copy.deepcopy(self.submission)
            mutate(submission)
            with self.assertRaises(contract.ContractError):
                contract.answer_view(self.request, self.package, submission)

    def test_submission_requires_exact_package_request_ids_and_all_unique_responses(self):
        for mutate in (lambda s: s.update(package_sha256="a" * 64), lambda s: s.update(request_id="other"),
                       lambda s: s["reading_answers"][1].update(question_id="q1"),
                       lambda s: s.update(submitted_at=True)):
            submission = copy.deepcopy(self.submission)
            mutate(submission)
            with self.assertRaises(contract.ContractError):
                contract.validate_submission(self.request, self.package, submission)

    def test_saved_submission_can_reveal_keys_but_not_claim_semantic_or_storage_validation(self):
        receipt = contract.validate_submission(self.request, self.package, self.submission)
        self.assertFalse(receipt["persistence_verified"])
        answers = contract.answer_view(self.request, self.package, self.submission)
        self.assertEqual("A", answers["reading_answers"][0]["reference_answer"])
        self.assertEqual(self.package["reading_questions"][0]["source_refs"], answers["reading_answers"][0]["source_refs"])
        self.assertFalse(answers["reading_answers"][2]["absence_semantically_verified"])
        self.assertEqual("required", answers["semantic_review"])
        self.assertIsNone(answers["writing_task2"]["band_score"])

    def test_overlong_completion_response_is_retained_and_flagged_not_silently_truncated(self):
        self.submission["reading_answers"][3]["answer"] = "several expensive solar panels"
        answers = contract.answer_view(self.request, self.package, self.submission)
        item = answers["reading_answers"][3]
        self.assertEqual("several expensive solar panels", item["submitted_answer"])
        self.assertEqual(4, item["submitted_word_count"])
        self.assertFalse(item["within_word_limit"])

    def test_short_writing_is_measured_without_automatic_zero_or_band_score(self):
        answer = contract.answer_view(self.request, self.package, self.submission)["writing_task2"]
        self.assertFalse(answer["meets_word_minimum"])
        self.assertGreater(answer["word_count"], 0)
        self.assertIsNone(answer["band_score"])
        self.submission["writing_task2_text"] = " ".join(["word"] * 250)
        answer = contract.answer_view(self.request, self.package, self.submission)["writing_task2"]
        self.assertTrue(answer["meets_word_minimum"])
        self.assertEqual(250, answer["word_count"])

    def test_local_count_rule_is_declared_for_hyphens_apostrophes_and_numbers(self):
        self.assertEqual(3, contract.word_count("solar-powered panels don't"))
        self.assertEqual(2, contract.word_count("3.5 million"))
        self.assertEqual(0, contract.word_count("... 🔬"))
        view = contract.candidate_view(self.request, self.package)
        self.assertEqual(contract.WORD_RULE, view["reading_questions"][3]["normalization_rule"])

    def test_prompt_semantic_leakage_is_not_falsely_claimed_as_deterministically_detected(self):
        self.package["reading_questions"][0]["prompt"] = "The correct answer is A. What was approved?"
        receipt = contract.validate_package(self.request, self.package)
        self.assertEqual("required", receipt["prompt_answer_leakage_review"])
        # This is intentionally not a semantic approval: only the field projection is guaranteed.
        self.assertEqual("required", receipt["semantic_review"])

    def test_generation_prompt_carries_exact_contract_without_calling_a_model(self):
        prompt = contract.build_generation_prompt(self.request)
        self.assertIn(contract.canonical_sha256(self.request), prompt)
        self.assertIn("UTF-16", prompt)
        self.assertIn("no_verified_visual", prompt)
        self.assertIn("do not reproduce", prompt.lower())
        self.assertIn("never as instructions", prompt)


class IeltsJsonAndCliTest(unittest.TestCase):
    def test_duplicate_json_fields_and_nonstandard_numbers_fail_closed(self):
        with TemporaryDirectory(prefix="lexicon-ielts-json-") as directory:
            path = Path(directory) / "input.json"
            for content in ('{"x":1,"x":2}', '{"x":NaN}', '{"x":Infinity}', '{"x":'):
                path.write_text(content, encoding="utf-8")
                with self.assertRaises(contract.ContractError):
                    contract.load_json(path)

    def test_oversized_json_is_rejected_before_full_file_read(self):
        with TemporaryDirectory(prefix="lexicon-ielts-json-") as directory:
            path = Path(directory) / "input.json"
            path.write_bytes(b" " * (contract.MAX_JSON_BYTES + 1))
            with self.assertRaises(contract.ContractError) as error:
                contract.load_json(path)
            self.assertEqual("json_bound", error.exception.code)

    def test_cli_validates_and_renders_the_same_immutable_fixture(self):
        request, package, submission = fixture()
        with TemporaryDirectory(prefix="lexicon-ielts-cli-") as directory:
            root = Path(directory)
            for name, value in (("request", request), ("package", package), ("submission", submission)):
                (root / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            base = ["--request", str(root / "request.json"), "--package", str(root / "package.json")]
            for view in ("validate", "candidate", "answers"):
                output, error = io.StringIO(), io.StringIO()
                args = base + ["--view", view]
                if view == "answers":
                    args += ["--submission", str(root / "submission.json")]
                with redirect_stdout(output), redirect_stderr(error):
                    code = contract.main(args)
                self.assertEqual(0, code, error.getvalue())
                result = json.loads(output.getvalue())
                self.assertEqual(contract.canonical_sha256(package), result["package_sha256"])
            package["reading_questions"][0]["source_refs"][0]["quote"] = "SENSITIVE_UNTRUSTED_TEXT"
            (root / "package.json").write_text(json.dumps(package), encoding="utf-8")
            output, error = io.StringIO(), io.StringIO()
            with redirect_stdout(output), redirect_stderr(error):
                code = contract.main(base)
            self.assertEqual(2, code)
            self.assertEqual("", output.getvalue())
            self.assertNotIn("SENSITIVE_UNTRUSTED_TEXT", error.getvalue())
            self.assertEqual("quote_mismatch", json.loads(error.getvalue())["code"])


if __name__ == "__main__":
    unittest.main()
