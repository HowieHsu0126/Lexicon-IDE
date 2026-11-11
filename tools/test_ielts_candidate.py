"""Deterministic offline compiler tests; no model, network or quality claims."""
import copy
import hashlib
import unittest

try:
    from tools import ielts_contract as contract, ielts_review as review
    from tools import ielts_explanation_review as explanation_review
    from tools.ielts_candidate import (compile_candidate, WRITING_AGREEMENT_INSTRUCTION, MAX_WRITING_PROMPT_UNITS,
                                       WRITING_DRAFT_CONFLICT, WRITING_DRAFT_TASK_TYPE, WRITING_DRAFT_TARGET_PARAGRAPH)
    from tools.test_ielts_contract import fixture, reference, completion_fixture
    from tools.test_ielts_review import review_fixture
except ImportError:
    import ielts_contract as contract
    import ielts_review as review
    import ielts_explanation_review as explanation_review
    from ielts_candidate import (compile_candidate, WRITING_AGREEMENT_INSTRUCTION, MAX_WRITING_PROMPT_UNITS,
                                 WRITING_DRAFT_CONFLICT, WRITING_DRAFT_TASK_TYPE, WRITING_DRAFT_TARGET_PARAGRAPH)
    from test_ielts_contract import fixture, reference, completion_fixture
    from test_ielts_review import review_fixture


def locator(ref):
    return {key: ref[key] for key in ("paragraph_id", "quote")}


def locator_fixture():
    request, package, _ = fixture()
    original = copy.deepcopy(package)
    p1, p2 = request["paragraphs"]
    package["reading_questions"][3]["source_refs"] = [
        locator(reference(p1, "🔬 The council approved solar panels."))]
    package["writing_task2"]["source_refs"] = [locator(reference(p2, p2["text"]))]
    return request, package, original


def writing_draft_fixture():
    request, package, _ = fixture()
    del package["writing_task2"]["prompt"]
    package["writing_task2"]["prompt_draft"] = {
        "background": "Cities can fund different kinds of energy projects. Residents face different costs.",
        "target_statement": "Cities should prioritise equal access to clean energy while keeping projects affordable.",
    }
    return request, package


def explanation_fixture():
    """Synthetic accepted structure, not a model or content-quality result."""
    request, package, blind = review_fixture()
    result = {"schema": explanation_review.EXPLANATION_REVIEW_SCHEMA, "review_id": "explanation-fixture-1",
              "request_sha256": contract.canonical_sha256(request),
              "package_sha256": contract.canonical_sha256(package),
              "blind_review_sha256": contract.canonical_sha256(blind),
              "explanation_reviews": []}
    for question in package["reading_questions"]:
        pid = question["source_refs"][0]["paragraph_id"] if question["source_refs"] else "p2"
        paragraph = next(p for p in request["paragraphs"] if p["paragraph_id"] == pid)
        refs = [reference(paragraph, paragraph["text"])]
        result["explanation_reviews"].append({
            "question_id": question["question_id"],
            "explanation_sha256": hashlib.sha256(question["explanation"].encode("utf-8")).hexdigest(),
            "source_refs": copy.deepcopy(refs),
            "unit_reviews": [{"unit_id": unit["unit_id"], "scope": "mixed", "relation": "supported",
                              "interpretations": ["Synthetic interpretation used only for protocol tests."],
                              "reason": "Synthetic protocol observation; no semantic assessment was performed.",
                              "source_ref_indices": [0]}
                             for unit in explanation_review.explanation_units(question["explanation"])]})
    return request, package, blind, result


class CandidateCompilerTest(unittest.TestCase):
    def rejects(self, request, candidate, code=None, mode="generation"):
        before = copy.deepcopy(candidate)
        with self.assertRaises(contract.ContractError) as caught:
            compile_candidate(request, candidate, mode)
        if code:
            self.assertEqual(code, caught.exception.code)
        self.assertEqual(before, candidate)
        return caught.exception

    def test_valid_full_candidate_is_preserved_and_not_mutated(self):
        request, package, _ = fixture()
        snapshot = copy.deepcopy(package)
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual(snapshot, compiled)
        self.assertEqual(snapshot, package)
        self.assertIsNot(compiled, package)
        self.assertIsNot(compiled["reading_questions"], package["reading_questions"])

    def test_writing_draft_compiles_exact_host_instruction_without_new_final_fields(self):
        request, package = writing_draft_fixture()
        before = copy.deepcopy(package)
        draft = before["writing_task2"]["prompt_draft"]
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual("To what extent do you agree or disagree with this statement?", WRITING_AGREEMENT_INSTRUCTION)
        self.assertEqual(draft["background"] + "\n\n" + draft["target_statement"] + "\n\n" + WRITING_AGREEMENT_INSTRUCTION,
                         compiled["writing_task2"]["prompt"])
        self.assertEqual({"task_id", "task_type", "prompt", "minimum_words", "suggested_minutes", "source_refs"},
                         set(compiled["writing_task2"]))
        self.assertEqual(before, package)
        for field in ("task_id", "task_type", "minimum_words", "suggested_minutes", "source_refs"):
            self.assertEqual(before["writing_task2"][field], compiled["writing_task2"][field])
        self.assertEqual(before["reading_questions"], compiled["reading_questions"])
        self.assertEqual("structurally_valid", contract.validate_package(request, compiled)["status"])
        self.assertNotEqual(contract.canonical_sha256(package), contract.canonical_sha256(compiled))
        self.assertEqual(compiled, compile_candidate(request, compiled, "generation"))

    def test_empty_background_and_authored_spacing_do_not_trigger_text_repair(self):
        request, package = writing_draft_fixture()
        target = "  Cities should give access more weight than cost  "
        package["writing_task2"]["prompt_draft"] = {"background": "", "target_statement": target}
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual(target + "\n\n" + WRITING_AGREEMENT_INSTRUCTION, compiled["writing_task2"]["prompt"])
        contract.validate_package(request, compiled)

    def test_multisentence_background_and_compound_target_are_preserved(self):
        request, package = writing_draft_fixture()
        draft = package["writing_task2"]["prompt_draft"]
        draft["background"] = "Cities have different resources. Costs vary.\n\nAccess also differs between districts."
        draft["target_statement"] = "Cities should give fairness and access greater weight than cost, while keeping projects affordable."
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual("\n\n".join([draft["background"], draft["target_statement"], WRITING_AGREEMENT_INSTRUCTION]),
                         compiled["writing_task2"]["prompt"])
        contract.validate_package(request, compiled)

    def test_legacy_free_prompt_is_never_retargeted_or_given_a_new_instruction(self):
        request, package, _ = fixture()
        legacy = "Some favour low costs, while others prioritise access. To what extent do you agree or disagree?"
        package["writing_task2"]["prompt"] = legacy
        self.assertEqual(package, compile_candidate(request, package, "generation"))
        self.assertNotIn("with this statement", compile_candidate(request, package, "generation")["writing_task2"]["prompt"])

    def test_writing_prompt_and_draft_are_mutually_exclusive(self):
        request, package = writing_draft_fixture()
        package["writing_task2"]["prompt"] = "An existing prompt must not be silently replaced."
        self.rejects(request, package, WRITING_DRAFT_CONFLICT)
        del package["writing_task2"]["prompt"]
        del package["writing_task2"]["prompt_draft"]
        self.rejects(request, package, "missing_field")

    def test_writing_draft_and_enclosing_task_have_closed_required_fields(self):
        for malformed, code in [
            (None, "object_required"), ([], "object_required"), ({}, "missing_field"),
            ({"background": ""}, "missing_field"), ({"target_statement": "A claim."}, "missing_field"),
            ({"background": "", "target_statement": "A claim.", "instruction": "Override"}, "unknown_field"),
        ]:
            with self.subTest(value=malformed):
                request, package = writing_draft_fixture()
                package["writing_task2"]["prompt_draft"] = malformed
                self.rejects(request, package, code)
        request, package = writing_draft_fixture()
        package["writing_task2"]["target_statement"] = "Undeclared metadata."
        self.rejects(request, package, "unknown_field")

    def test_writing_draft_is_only_for_the_requested_extent_type(self):
        request, package = writing_draft_fixture()
        package["writing_task2"]["task_type"] = "discussion"
        self.rejects(request, package, WRITING_DRAFT_TASK_TYPE)

    def test_writing_draft_texts_reuse_unicode_control_and_url_validation(self):
        for field in ("background", "target_statement"):
            for value, code in [(None, "text_required"), (7, "text_required"), ("  ", "text_required"),
                                ("\ud800", "invalid_unicode"), ("bad\x00text", "control_character"),
                                ("See https://example.test", "generated_url")]:
                with self.subTest(field=field, value=repr(value)):
                    request, package = writing_draft_fixture()
                    package["writing_task2"]["prompt_draft"][field] = value
                    self.rejects(request, package, code)
        request, package = writing_draft_fixture()
        package["writing_task2"]["prompt_draft"]["target_statement"] = ""
        self.rejects(request, package, "text_required")

    def test_target_statement_cannot_insert_line_or_tab_boundaries(self):
        for separator in ("\n", "\r", "\r\n", "\t", "\x85", "\u2028", "\u2029"):
            with self.subTest(separator=repr(separator)):
                request, package = writing_draft_fixture()
                package["writing_task2"]["prompt_draft"]["target_statement"] = "One claim." + separator + "Another block."
                self.rejects(request, package, WRITING_DRAFT_TARGET_PARAGRAPH)

    def test_writing_draft_bounds_apply_to_fields_and_complete_utf16_prompt(self):
        for field in ("background", "target_statement"):
            request, package = writing_draft_fixture()
            package["writing_task2"]["prompt_draft"][field] = "x" * (MAX_WRITING_PROMPT_UNITS + 1)
            self.rejects(request, package, "text_bound")
        request, package = writing_draft_fixture()
        # Both fields fit independently; their composition must still fit.
        package["writing_task2"]["prompt_draft"] = {"background": "b" * 900, "target_statement": "t" * 900}
        self.rejects(request, package, "text_bound")
        units = MAX_WRITING_PROMPT_UNITS - contract.utf16_length("\n\n" + WRITING_AGREEMENT_INSTRUCTION)
        target = "🔬" + "t" * (units - 2)
        package["writing_task2"]["prompt_draft"] = {"background": "", "target_statement": target}
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual(MAX_WRITING_PROMPT_UNITS, contract.utf16_length(compiled["writing_task2"]["prompt"]))
        contract.validate_package(request, compiled)
        package["writing_task2"]["prompt_draft"]["target_statement"] += "t"
        self.rejects(request, package, "text_bound")

    def test_draft_compilation_and_source_locator_compilation_share_one_final_artifact(self):
        request, package = writing_draft_fixture()
        original_refs = copy.deepcopy(package["writing_task2"]["source_refs"])
        package["writing_task2"]["source_refs"] = [locator(ref) for ref in original_refs]
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual(original_refs, compiled["writing_task2"]["source_refs"])
        self.assertNotIn("prompt_draft", compiled["writing_task2"])
        contract.validate_package(request, compiled)

    def test_final_package_contract_does_not_accept_draft_or_generator_target_fields(self):
        request, package = writing_draft_fixture()
        with self.assertRaises(contract.ContractError):
            contract.validate_package(request, package)
        compiled = compile_candidate(request, package, "generation")
        for field in ("prompt_draft", "target_statement"):
            with self.subTest(field=field):
                mutated = copy.deepcopy(compiled)
                mutated["writing_task2"][field] = "Undeclared generator claim"
                with self.assertRaises(contract.ContractError):
                    contract.validate_package(request, mutated)

    def test_review_modes_do_not_compile_or_trust_generator_draft_metadata(self):
        request, package, report = review_fixture()
        draft = {"background": "", "target_statement": "A generator's asserted target."}
        report["writing_task2_review"]["prompt_draft"] = copy.deepcopy(draft)
        before = copy.deepcopy(report)
        compiled = compile_candidate(request, report, "review")
        self.assertEqual(before, compiled)
        with self.assertRaises(contract.ContractError):
            review.validate_review(request, package, compiled, report["review_id"])
        request, package, blind, report = explanation_fixture()
        report["prompt_draft"] = copy.deepcopy(draft)
        compiled = compile_candidate(request, report, "explanation_review")
        self.assertEqual(report, compiled)
        with self.assertRaises(contract.ContractError):
            explanation_review.validate_explanation_review(request, package, blind, compiled, report["review_id"])

    def test_unique_locator_and_answer_compilation_preserve_context_after_emoji(self):
        request, package, original = locator_fixture()
        raw = copy.deepcopy(package)
        compiled = compile_candidate(request, package, "generation")
        refs = compiled["reading_questions"][3]["source_refs"]
        self.assertEqual(2, len(refs))
        self.assertEqual(reference(request["paragraphs"][0], "🔬 The council approved solar panels."), refs[0])
        self.assertEqual(original["reading_questions"][3]["source_refs"][0], refs[1])
        self.assertEqual(original["writing_task2"], compiled["writing_task2"])
        self.assertEqual(raw, package)
        self.assertEqual("structurally_valid", contract.validate_package(request, compiled)["status"])
        self.assertNotEqual(contract.canonical_sha256(package), contract.canonical_sha256(compiled))
        self.assertEqual(compiled, compile_candidate(request, compiled, "generation"))

    def test_duplicate_locator_does_not_select_first(self):
        request, package, _ = fixture()
        package["reading_questions"][0]["source_refs"] = [locator(package["reading_questions"][0]["source_refs"][0])]
        error = self.rejects(request, package, "candidate_quote_ambiguous")
        self.assertIn("lexicon_ielts_read", str(error))
        self.assertNotIn("solar", str(error))

    def test_overlapping_locator_matches_are_ambiguous(self):
        request, package = completion_fixture("An aaa marker was recorded.", "marker")
        package["writing_task2"]["source_refs"] = [{"paragraph_id": "p1", "quote": "aa"}]
        self.rejects(request, package, "candidate_quote_ambiguous")

    def test_locator_matching_is_case_and_whitespace_sensitive(self):
        for quote in ("the writer", "The  writer", "The writer.*"):
            request, package, _ = fixture()
            package["writing_task2"]["source_refs"] = [{"paragraph_id": "p2", "quote": quote}]
            self.rejects(request, package, "candidate_quote_not_found")

    def test_unknown_paragraph_and_empty_or_mixed_shape_reject(self):
        for ref in ({"paragraph_id": "unknown", "quote": "The writer"},
                    {"paragraph_id": "p2", "quote": ""}, {"paragraph_id": "p2", "quote": "  "},
                    {"paragraph_id": "p2", "quote": "The writer", "start": 0},
                    {"paragraph_id": "p2", "quote": "The writer", "extra": 1}):
            request, package, _ = fixture()
            package["writing_task2"]["source_refs"] = [ref]
            self.rejects(request, package)

    def test_provided_invalid_full_range_is_not_repaired(self):
        request, package, _ = fixture()
        package["reading_questions"][0]["source_refs"][0]["start"] += 1
        self.rejects(request, package, "quote_mismatch")

    def test_duplicate_reference_ranges_reject_after_compilation(self):
        request, package, _ = fixture()
        ref = package["writing_task2"]["source_refs"][0]
        package["writing_task2"]["source_refs"].append(locator(ref))
        self.rejects(request, package, "duplicate_reference")

    def test_global_context_union_ambiguity_rejects(self):
        request, package, _ = fixture()
        p1 = request["paragraphs"][0]
        package["reading_questions"][3]["source_refs"] = [reference(p1, p1["text"])]
        self.rejects(request, package, "candidate_answer_ambiguous")

    def test_multiple_paragraph_contexts_share_one_uniqueness_check(self):
        request, package, _ = fixture()
        p1, p2 = request["paragraphs"]
        package["reading_questions"][3].update(answer="The", source_refs=[
            reference(p1, "🔬 The council approved solar panels."), reference(p2, p2["text"])])
        self.rejects(request, package, "candidate_answer_ambiguous")

    def test_explicit_exact_range_can_disambiguate_repeated_answer(self):
        request, package, _ = fixture()
        p1 = request["paragraphs"][0]
        refs = [reference(p1, p1["text"]), reference(p1, "solar panels", last=True)]
        package["reading_questions"][3]["source_refs"] = refs
        self.assertEqual(refs, compile_candidate(request, package, "generation")["reading_questions"][3]["source_refs"])

    def test_overlapping_contexts_count_same_answer_range_once(self):
        request, package = completion_fixture("The council approved solar panels yesterday.", "solar panels")
        p = request["paragraphs"][0]
        refs = [reference(p, p["text"]), reference(p, "approved solar panels yesterday")]
        package["reading_questions"][0]["source_refs"] = copy.deepcopy(refs)
        actual = compile_candidate(request, package, "generation")["reading_questions"][0]["source_refs"]
        self.assertEqual(refs, actual[:2])
        self.assertEqual(3, len(actual))

    def test_adjacent_context_union_can_contain_whole_answer(self):
        request, package = completion_fixture("They chose solar panels today.", "solar panels")
        p = request["paragraphs"][0]
        package["reading_questions"][0]["source_refs"] = [reference(p, "They chose solar "), reference(p, "panels today.")]
        self.assertEqual("solar panels", compile_candidate(request, package, "generation")["reading_questions"][0]["source_refs"][-1]["quote"])

    def test_gapped_context_is_not_expanded_to_find_answer(self):
        request, package = completion_fixture("They chose solar panels today.", "solar panels")
        p = request["paragraphs"][0]
        package["reading_questions"][0]["source_refs"] = [reference(p, "They chose solar"), reference(p, "panels today.")]
        self.rejects(request, package, "candidate_answer_not_found")

    def test_no_search_outside_context_or_case_repair(self):
        for answer, context in (("solar panels", "The council approved"), ("Solar panels", "🔬 The council approved solar panels.")):
            request, package, _ = fixture()
            package["reading_questions"][3].update(answer=answer, source_refs=[reference(request["paragraphs"][0], context)])
            self.rejects(request, package, "candidate_answer_not_found")

    def test_partial_source_tokens_are_rejected_for_derived_and_existing_refs(self):
        for text, answer in (("A reflective surface.", "reflect"), ("A long-term policy.", "term"),
                             ("The writer's view.", "writer"), ("The rate is 2.75.", "75")):
            for context in (True, False):
                request, package = completion_fixture(text, answer)
                if context:
                    package["reading_questions"][0]["source_refs"] = [reference(request["paragraphs"][0], text)]
                self.rejects(request, package, "candidate_answer_token_boundary")

    def test_partial_substring_does_not_hide_a_unique_complete_token(self):
        request, package = completion_fixture("A reflective coating can reflect sunlight.", "reflect", last=True)
        paragraph = request["paragraphs"][0]
        package["reading_questions"][0]["source_refs"] = [reference(paragraph, paragraph["text"])]
        compiled = compile_candidate(request, package, "generation")
        self.assertEqual(reference(paragraph, "reflect", last=True), compiled["reading_questions"][0]["source_refs"][-1])
        contract.validate_package(request, compiled)

    def test_existing_exact_and_derived_refs_keep_declared_word_number_rules(self):
        for context in (True, False):
            request, package = completion_fixture("The rate reached 25 percent yesterday.", "25 percent")
            request["reading_requests"][0]["allow_numbers"] = False
            if context:
                package["reading_questions"][0]["source_refs"] = [reference(request["paragraphs"][0], request["paragraphs"][0]["text"])]
            self.rejects(request, package, "completion_numbers")
        request, package, _ = fixture()
        package["reading_questions"][3]["answer"] = "approved solar panels"
        self.rejects(request, package, "completion_word_limit")

    def test_capacity_keeps_all_original_refs_instead_of_truncating(self):
        request, package = completion_fixture("They approved solar panels yesterday morning.", "solar panels")
        p = request["paragraphs"][0]
        package["reading_questions"][0]["source_refs"] = [reference(p, quote) for quote in
            (p["text"], "They", "approved", "yesterday", "morning")]
        self.rejects(request, package, "candidate_reference_capacity")

    def test_explanation_review_compiles_only_references_without_answers_or_types(self):
        request, package, blind, report = explanation_fixture()
        expected = copy.deepcopy(report)
        for item in report["explanation_reviews"]:
            item["source_refs"] = [locator(ref) for ref in item["source_refs"]]
        original = copy.deepcopy(report)
        compiled = compile_candidate(request, report, "explanation_review")
        self.assertEqual(expected, compiled)
        self.assertEqual(original, report)
        self.assertEqual(compiled, compile_candidate(request, compiled, "explanation_review"))
        self.assertTrue(all("type" not in item and "answer" not in item for item in compiled["explanation_reviews"]))

    def test_explanation_review_preserves_rejections_and_requires_each_identity(self):
        request, _, _, report = explanation_fixture()
        report["explanation_reviews"][0]["unit_reviews"][0].update(
            relation="unsupported", reason="Synthetic finding: the explanation changes the source scope.")
        self.assertEqual(report, compile_candidate(request, report, "explanation_review"))
        for change in (lambda rows: rows.pop(), lambda rows: rows[0].update(question_id="other"),
                       lambda rows: rows.reverse()):
            mutated = copy.deepcopy(report)
            change(mutated["explanation_reviews"])
            self.rejects(request, mutated, mode="explanation_review")

    def test_explanation_reference_compilation_preserves_unit_interpretations_and_indices(self):
        request, _, _, report = explanation_fixture()
        unit = report["explanation_reviews"][0]["unit_reviews"][0]
        unit.update(scope="ambiguous", relation="ambiguous",
                    interpretations=["The passage does not report a measurement.", "No measurement took place."],
                    reason="Synthetic ambiguity fixture; no semantic assessment was performed.")
        before = copy.deepcopy(report)
        for item in report["explanation_reviews"]:
            item["source_refs"] = [locator(ref) for ref in item["source_refs"]]
        compiled = compile_candidate(request, report, "explanation_review")
        self.assertEqual(before, compiled)
        self.assertEqual(unit, compiled["explanation_reviews"][0]["unit_reviews"][0])

    def test_explanation_review_does_not_derive_completion_answers(self):
        request, _, _, report = explanation_fixture()
        context = reference(request["paragraphs"][0], request["paragraphs"][0]["text"])
        report["explanation_reviews"][3]["source_refs"] = [locator(context)]
        compiled = compile_candidate(request, report, "explanation_review")
        self.assertEqual([context], compiled["explanation_reviews"][3]["source_refs"])

    def test_review_compiles_observed_ng_context_and_writing_without_generator_input(self):
        request, package, report = review_fixture()
        p1, p2 = request["paragraphs"]
        report["reading_reviews"][3]["source_refs"] = [locator(reference(p1, "🔬 The council approved solar panels."))]
        report["reading_reviews"][2]["not_given_review"]["paragraph_reviews"][1]["context_refs"] = [locator(reference(p2, p2["text"]))]
        report["writing_task2_review"]["source_refs"] = [locator(reference(p2, p2["text"]))]
        before = copy.deepcopy(report)
        compiled = compile_candidate(request, report, "review")
        self.assertEqual(before, report)
        self.assertEqual(report["reading_reviews"][0]["option_reviews"], compiled["reading_reviews"][0]["option_reviews"])
        self.assertEqual(report["reading_reviews"][0]["source_refs"], compiled["reading_reviews"][0]["source_refs"])
        self.assertEqual(report["writing_task2_review"]["instruction_alignment"],
                         compiled["writing_task2_review"]["instruction_alignment"])
        self.assertEqual("eligible_for_caller_publication", review.validate_review(request, package, compiled, report["review_id"])["status"])

    def test_null_observation_stays_null(self):
        request, package, report = review_fixture()
        report["reading_reviews"][3].update(observed_answer=None, source_refs=[])
        compiled = compile_candidate(request, report, "review")
        self.assertIsNone(compiled["reading_reviews"][3]["observed_answer"])
        self.assertEqual([], compiled["reading_reviews"][3]["source_refs"])
        self.assertEqual("rejected", review.validate_review(request, package, compiled, report["review_id"])["status"])

    def test_alternative_context_retained_and_semantic_rejection_not_repaired(self):
        request, package, report = review_fixture()
        context = reference(request["paragraphs"][1], request["paragraphs"][1]["text"])
        item = report["reading_reviews"][3]
        item["alternative_answers"] = [{"text": "clean energy", "source_ref": locator(context)}]
        compiled = compile_candidate(request, report, "review")
        result = compiled["reading_reviews"][3]
        self.assertEqual(item["source_refs"], result["source_refs"][:1])
        self.assertEqual(context, result["source_refs"][1])
        self.assertEqual("clean energy", result["source_refs"][2]["quote"])
        self.assertEqual(result["source_refs"][2], result["alternative_answers"][0]["source_ref"])
        receipt = review.validate_review(request, package, compiled, report["review_id"])
        self.assertIn("multiple_completion_answers", [b["code"] for b in receipt["blockers"]])

    def test_alternative_context_is_deduplicated_without_reordering(self):
        request, _, report = review_fixture()
        context = reference(request["paragraphs"][1], request["paragraphs"][1]["text"])
        item = report["reading_reviews"][3]
        item["source_refs"].append(context)
        item["alternative_answers"] = [{"text": "clean energy", "source_ref": context}]
        refs = compile_candidate(request, report, "review")["reading_reviews"][3]["source_refs"]
        self.assertEqual(item["source_refs"], refs[:2])
        self.assertEqual(3, len(refs))

    def test_alternative_ambiguous_and_partial_answers_are_rejected(self):
        request, _, report = review_fixture()
        p1 = request["paragraphs"][0]
        for answer, code in (("solar panels", "candidate_answer_ambiguous"), ("prove", "candidate_answer_token_boundary")):
            report["reading_reviews"][3]["alternative_answers"] = [{"text": answer, "source_ref": reference(p1, p1["text"])}]
            self.rejects(request, report, code, "review")

    def test_final_contract_still_rejects_uncompiled_locators(self):
        request, package, _ = locator_fixture()
        with self.assertRaises(contract.ContractError):
            contract.validate_package(request, package)


if __name__ == "__main__":
    unittest.main()
