"""Offline contract fixtures; no model is asked to assess meaning or quality here."""
import copy
import hashlib
import json
import unittest

try:
    from tools import ielts_contract as contract, ielts_review as answer_review
    from tools import ielts_explanation_review as audit
    from tools.test_ielts_contract import reference
    from tools.test_ielts_review import review_fixture
    from tools.test_ielts_json_schema import KEYWORDS, schema_nodes, valid, with_locators
except ImportError:
    import ielts_contract as contract
    import ielts_review as answer_review
    import ielts_explanation_review as audit
    from test_ielts_contract import reference
    from test_ielts_review import review_fixture
    from test_ielts_json_schema import KEYWORDS, schema_nodes, valid, with_locators


REVIEW_ID = "explanation-offline-1"


def result_fixture(request, package, blind):
    context = reference(request["paragraphs"][0], request["paragraphs"][0]["text"])
    return {
        "schema": audit.EXPLANATION_REVIEW_SCHEMA, "review_id": REVIEW_ID,
        "request_sha256": contract.canonical_sha256(request),
        "package_sha256": contract.canonical_sha256(package),
        "blind_review_sha256": contract.canonical_sha256(blind),
        "explanation_reviews": [
            {"question_id": q["question_id"], "explanation_sha256": audit.explanation_sha256(q["explanation"]),
             "source_refs": [copy.deepcopy(context)],
             "unit_reviews": [{"unit_id": unit["unit_id"], "scope": "source_fact", "relation": "supported",
                               "interpretations": ["Synthetic source assertion."],
                               "reason": "Synthetic observation for protocol testing, not a semantic assessment.",
                               "source_ref_indices": [0]} for unit in audit.explanation_units(q["explanation"])]}
            for q in package["reading_questions"]],
    }


def negation_fixture(explanation):
    """A short authored example; reported semantic judgments below are fixtures, not model results."""
    request, package, blind = review_fixture()
    text = ("Researchers recorded roof surface temperatures at consistent daily times for eight weeks. "
            "They did not measure temperatures inside every dwelling. Hospital admissions were not measured.")
    paragraph = {"paragraph_id": "p1", "start": 0, "end": contract.utf16_length(text), "text": text}
    request.update(paragraphs=[paragraph], reading_requests=[{"question_id": "q1", "type": "multiple_choice"}],
                   body_hash=hashlib.sha256(text.encode()).hexdigest())
    question = copy.deepcopy(package["reading_questions"][0])
    question.update(prompt="What did the researchers record over eight weeks?", answer="D", explanation=explanation,
                    options=[{"option_id": label, "text": value} for label, value in zip("ABCD", [
                        "Indoor temperatures and hospital admissions", "Savings from two roof treatments",
                        "Tenant participation", "Roof surface temperatures at consistent daily times"])],
                    source_refs=[reference(paragraph, text.split(". ", 1)[0] + ".")])
    package.update(reading_questions=[question], body_hash=request["body_hash"],
                   request_sha256=contract.canonical_sha256(request))
    package["writing_task2"]["source_refs"] = [reference(paragraph, text)]
    item = copy.deepcopy(blind["reading_reviews"][0])
    item.update(observed_answer="D", conclusion="D states the explicitly recorded variable.",
                source_refs=copy.deepcopy(question["source_refs"]))
    for option in item["option_reviews"]:
        option.update(status="supported" if option["option_id"] == "D" else "not_supported",
                      source_ref_indices=[0] if option["option_id"] == "D" else [],
                      reason="D states the recorded variable; the other combined claims do not.")
    blind["reading_reviews"] = [item]
    blind["writing_task2_review"]["source_refs"] = [reference(paragraph, text)]
    blind.update(request_sha256=contract.canonical_sha256(request), package_sha256=contract.canonical_sha256(package))
    return request, package, blind


class ExplanationReviewTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.blind = review_fixture()
        self.result = result_fixture(self.request, self.package, self.blind)

    def validate(self, result=None):
        return audit.validate_explanation_review(self.request, self.package, self.blind,
                                                  self.result if result is None else result, REVIEW_ID)

    def reject(self, result, code=None):
        with self.assertRaises(contract.ContractError) as caught:
            self.validate(result)
        if code:
            self.assertEqual(code, caught.exception.code)

    def test_complete_pass_has_all_bindings_but_no_semantic_or_runtime_guarantee(self):
        receipt = self.validate()
        self.assertEqual("eligible_for_caller_publication", receipt["status"])
        self.assertEqual([], receipt["blocked_items"])
        self.assertEqual(4, receipt["question_count"])
        self.assertEqual(contract.canonical_sha256(self.request), receipt["request_sha256"])
        self.assertEqual(contract.canonical_sha256(self.package), receipt["package_sha256"])
        self.assertEqual(contract.canonical_sha256(self.blind), receipt["blind_review_sha256"])
        self.assertEqual(contract.canonical_sha256(self.result), receipt["explanation_review_sha256"])
        self.assertEqual(self.blind["review_id"], receipt["blind_review_id"])
        self.assertFalse(receipt["semantic_guarantee"])
        self.assertFalse(receipt["runtime_provenance_verified"])
        self.assertTrue(receipt["caller_runtime_verification_required"])
        self.assertFalse(receipt["official_band_prediction_validated"])

    def test_digest_or_accepted_receipt_cannot_substitute_for_the_full_blind_result(self):
        receipt = answer_review.validate_review(self.request, self.package, self.blind, self.blind["review_id"])
        substitutions = [None, contract.canonical_sha256(self.blind), receipt,
                         {"review_id": self.blind["review_id"], "status": "eligible_for_caller_publication",
                          "review_sha256": contract.canonical_sha256(self.blind)}]
        for value in substitutions:
            for operation in (
                lambda: audit.build_explanation_review_prompt(self.request, self.package, value, REVIEW_ID),
                lambda: audit.explanation_review_schema(self.request, self.package, value, REVIEW_ID),
                lambda: audit.validate_explanation_review(self.request, self.package, value, self.result, REVIEW_ID),
            ):
                with self.subTest(value_type=type(value).__name__), self.assertRaises(contract.ContractError):
                    operation()

    def test_blind_disagreement_or_failed_check_blocks_before_explanations_are_exposed(self):
        for kind in ("answer", "fail", "insufficient"):
            blind = copy.deepcopy(self.blind)
            if kind == "answer":
                blind["reading_reviews"][0]["observed_answer"] = "B"
            else:
                blind["reading_reviews"][0]["checks"]["unique_answer"]["status"] = kind
            for operation in (
                lambda: audit.build_explanation_review_prompt(self.request, self.package, blind, REVIEW_ID),
                lambda: audit.explanation_review_schema(self.request, self.package, blind, REVIEW_ID),
                lambda: audit.validate_explanation_review(self.request, self.package, blind, self.result, REVIEW_ID),
            ):
                with self.subTest(kind=kind), self.assertRaises(contract.ContractError) as caught:
                    operation()
                self.assertEqual("blind_review_not_eligible", caught.exception.code)

    def test_missing_or_malformed_blind_review_is_not_an_implicit_pass(self):
        for field in ("review_id", "reading_reviews", "writing_task2_review"):
            blind = copy.deepcopy(self.blind); del blind[field]
            with self.subTest(field=field), self.assertRaises(contract.ContractError):
                audit.build_explanation_review_prompt(self.request, self.package, blind, REVIEW_ID)
        blind = copy.deepcopy(self.blind); blind["review_id"] = None
        with self.assertRaises(contract.ContractError):
            audit.explanation_review_schema(self.request, self.package, blind, REVIEW_ID)

    def test_post_blind_prompt_reveals_actual_explanations_and_full_context_without_changing_blind_prompt(self):
        original_prompt = answer_review.build_review_prompt(self.request, self.package, self.blind["review_id"])
        prompt = audit.build_explanation_review_prompt(self.request, self.package, self.blind, REVIEW_ID)
        data = json.loads(prompt.split("FROZEN POST-BLIND MATERIAL:\n", 1)[1])
        self.assertEqual(self.request["paragraphs"], data["paragraphs"])
        self.assertEqual(self.package["reading_questions"], data["reading_questions"])
        self.assertEqual([{"question_id": r["question_id"], "observed_answer": r["observed_answer"]}
                          for r in self.blind["reading_reviews"]], data["frozen_blind_answers"])
        self.assertIn("SECRET_RATIONALE_Q1", prompt)
        self.assertNotIn("SECRET_RATIONALE_Q1", original_prompt)
        self.assertNotIn("PRIVATE_REVIEW_Q1", prompt)
        self.assertEqual({q["question_id"]: audit.explanation_units(q["explanation"])
                          for q in self.package["reading_questions"]}, data["explanation_units_by_question"])

    def test_prompt_digest_is_raw_utf8_after_construction(self):
        prompt = audit.build_explanation_review_prompt(self.request, self.package, self.blind, REVIEW_ID)
        expected = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        self.assertEqual(expected, self.validate()["review_prompt_sha256"])
        self.assertNotIn(expected, prompt)

    def test_explanation_hash_preserves_exact_utf8_whitespace_and_unicode(self):
        for text in ("解释", " A\r\nB ", "é", "e\u0301", "🔬 explanation"):
            self.assertEqual(hashlib.sha256(text.encode()).hexdigest(), audit.explanation_sha256(text))
        self.assertNotEqual(audit.explanation_sha256("é"), audit.explanation_sha256("e\u0301"))
        self.assertNotEqual(audit.explanation_sha256("A"), audit.explanation_sha256("A "))

    def test_changed_explanation_cannot_reuse_its_previous_per_item_digest(self):
        self.package["reading_questions"][0]["explanation"] += " "
        self.blind["package_sha256"] = contract.canonical_sha256(self.package)
        self.result.update(package_sha256=contract.canonical_sha256(self.package),
                           blind_review_sha256=contract.canonical_sha256(self.blind))
        self.reject(self.result, "identity_mismatch")

    def test_changed_blind_observation_invalidates_a_previous_audit_even_with_the_same_answers(self):
        self.blind["reading_reviews"][0]["conclusion"] += " Another bounded observation."
        self.reject(self.result, "identity_mismatch")

    def test_wrong_top_level_identity_or_schema_is_rejected(self):
        for field in ("schema", "review_id", "request_sha256", "package_sha256", "blind_review_sha256"):
            result = copy.deepcopy(self.result); result[field] = "wrong"
            with self.subTest(field=field):
                self.reject(result, "identity_mismatch")

    def test_missing_extra_or_null_result_fields_are_rejected(self):
        with self.assertRaises(contract.ContractError):
            audit.validate_explanation_review(self.request, self.package, self.blind, None, REVIEW_ID)
        for field in self.result:
            result = copy.deepcopy(self.result); del result[field]
            with self.subTest(missing=field):
                self.reject(result)
        for field in ("overall_pass", "runtime_provenance_verified", "answer", "replacement_explanation"):
            result = copy.deepcopy(self.result); result[field] = True
            with self.subTest(extra=field):
                self.reject(result, "unknown_field")

    def test_missing_duplicate_reordered_or_unknown_question_is_rejected(self):
        mutations = [lambda r: r["explanation_reviews"].pop(),
                     lambda r: r["explanation_reviews"].append(copy.deepcopy(r["explanation_reviews"][0])),
                     lambda r: r["explanation_reviews"][1].update(question_id="q1"),
                     lambda r: r["explanation_reviews"].reverse(),
                     lambda r: r["explanation_reviews"][0].update(question_id="unrelated")]
        for index, mutate in enumerate(mutations):
            result = copy.deepcopy(self.result); mutate(result)
            with self.subTest(index=index):
                self.reject(result)

    def test_all_item_fields_are_required_and_no_additional_field_is_allowed(self):
        for field in self.result["explanation_reviews"][0]:
            result = copy.deepcopy(self.result); del result["explanation_reviews"][0][field]
            with self.subTest(missing=field):
                self.reject(result)
        result = copy.deepcopy(self.result); result["explanation_reviews"][0]["answer"] = "A"
        self.reject(result, "unknown_field")

    def test_null_wrong_type_and_unknown_statuses_are_rejected(self):
        for status in (None, True, 1, [], {}, "PASS", "accepted", ""):
            result = copy.deepcopy(self.result); result["explanation_reviews"][0]["unit_reviews"][0]["relation"] = status
            with self.subTest(status=repr(status)):
                self.reject(result, "explanation_relation")

    def test_nonempty_bounded_unicode_reasons_are_required(self):
        for reason in (None, True, "", " \t\n", "a" * 321, "🔬" * 161, "bad\x00reason", "bad\ud800", "https://example.invalid"):
            result = copy.deepcopy(self.result); result["explanation_reviews"][0]["unit_reviews"][0]["reason"] = reason
            with self.subTest(reason_type=type(reason).__name__):
                self.reject(result)
        self.result["explanation_reviews"][0]["unit_reviews"][0]["reason"] = "🔬" * 160
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])

    def test_failed_and_insufficient_items_are_valid_complete_rejections_without_forced_evidence(self):
        self.result["explanation_reviews"][0].update(source_refs=[])
        self.result["explanation_reviews"][0]["unit_reviews"][0].update(relation="unsupported", source_ref_indices=[], reason="The explanation changes the source proposition.")
        self.result["explanation_reviews"][1].update(source_refs=[])
        self.result["explanation_reviews"][1]["unit_reviews"][0].update(relation="ambiguous", source_ref_indices=[],
            interpretations=["The article does not report a measurement.", "The study did not perform the measurement."],
            reason="The actual assertion has two plausible scopes.")
        receipt = self.validate()
        self.assertEqual("rejected", receipt["status"])
        self.assertEqual(["q1", "q2"], [x["item_id"] for x in receipt["blocked_items"]])
        self.assertEqual(["explanation_fail", "explanation_insufficient"], [x["code"] for x in receipt["blocked_items"]])
        self.assertEqual(4, len(receipt["explanation_results"]))

    def test_pass_requires_at_least_one_original_reference(self):
        self.result["explanation_reviews"][0]["source_refs"] = []
        self.result["explanation_reviews"][0]["unit_reviews"][0]["source_ref_indices"] = []
        self.reject(self.result, "explanation_evidence_required")

    def test_reference_id_range_quote_boolean_offsets_and_duplicates_are_checked(self):
        mutations = [lambda ref: ref.update(paragraph_id="unknown"),
                     lambda ref: ref.update(start=True), lambda ref: ref.update(end=False),
                     lambda ref: ref.update(start=-1), lambda ref: ref.update(end=999999),
                     lambda ref: ref.update(end=ref["start"]), lambda ref: ref.update(quote="private altered quote")]
        for index, mutate in enumerate(mutations):
            result = copy.deepcopy(self.result); mutate(result["explanation_reviews"][0]["source_refs"][0])
            with self.subTest(index=index):
                self.reject(result)
        result = copy.deepcopy(self.result)
        result["explanation_reviews"][0]["source_refs"] *= 2
        self.reject(result, "duplicate_reference")

    def test_more_than_five_references_is_rejected(self):
        self.result["explanation_reviews"][0]["source_refs"] *= 6
        self.reject(self.result, "list_bound")

    def test_full_reference_contract_rejects_uncompiled_locators(self):
        self.reject(with_locators(self.result), "missing_field")

    def test_utf16_offsets_after_emoji_are_not_python_character_offsets(self):
        ref = reference(self.request["paragraphs"][0], "The council approved solar panels.")
        self.assertEqual(3, ref["start"])
        self.result["explanation_reviews"][0]["source_refs"] = [ref]
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])
        ref["start"] -= 1; ref["end"] -= 1
        self.reject(self.result, "quote_mismatch")

    def test_partial_source_tokens_or_punctuation_are_not_passing_evidence(self):
        paragraph = self.request["paragraphs"][0]
        for text in ("sol", "pan", ".", "🔬"):
            result = copy.deepcopy(self.result)
            result["explanation_reviews"][0]["source_refs"] = [reference(paragraph, text)]
            with self.subTest(text=text):
                self.reject(result, "explanation_token_boundary")
        self.result["explanation_reviews"][0]["source_refs"] = [reference(paragraph, "solar panels.")]
        self.assertEqual("eligible_for_caller_publication", self.validate()["status"])

    def test_failure_references_still_need_valid_exact_complete_source_tokens(self):
        self.result["explanation_reviews"][0].update(source_refs=[reference(self.request["paragraphs"][0], "sol")])
        self.reject(self.result, "explanation_token_boundary")

    def test_blind_answer_agreement_cannot_override_a_failed_quantifier_explanation_audit(self):
        request, package, blind = negation_fixture("研究人员完全没有测量任何住宅的室内温度。")
        self.assertEqual("eligible_for_caller_publication", answer_review.validate_review(request, package, blind, blind["review_id"])["status"])
        result = result_fixture(request, package, blind)
        result["explanation_reviews"][0]["unit_reviews"][0].update(relation="unsupported",
            reason="The explanation replaces not every dwelling with none; these claims are not equivalent.")
        result["explanation_reviews"][0]["source_refs"] = [reference(request["paragraphs"][0], "They did not measure temperatures inside every dwelling.")]
        receipt = audit.validate_explanation_review(request, package, blind, result, REVIEW_ID)
        self.assertEqual("rejected", receipt["status"])
        self.assertEqual("explanation_fail", receipt["blocked_items"][0]["code"])
        self.assertEqual("D", package["reading_questions"][0]["answer"])

    def test_corrected_explanation_can_pass_with_the_original_quantified_context(self):
        request, package, blind = negation_fixture(
            "D identifies the recorded surface temperatures. A includes hospital admissions, which were not measured; "
            "the indoor-temperature clause only says that not every dwelling was measured.")
        result = result_fixture(request, package, blind)
        result["explanation_reviews"][0]["unit_reviews"][0]["reason"] = "The explanation preserves not every and excludes the compound option using the unmeasured hospital-admission component."
        receipt = audit.validate_explanation_review(request, package, blind, result, REVIEW_ID)
        self.assertEqual("eligible_for_caller_publication", receipt["status"])
        self.assertFalse(receipt["semantic_guarantee"])

    def test_contract_errors_do_not_echo_private_explanation_or_altered_quote(self):
        result = copy.deepcopy(self.result)
        result["explanation_reviews"][0]["source_refs"][0]["quote"] = "DO_NOT_ECHO_PRIVATE_TEXT"
        with self.assertRaises(contract.ContractError) as caught:
            self.validate(result)
        self.assertNotIn("DO_NOT_ECHO_PRIVATE_TEXT", str(caught.exception))
        self.assertNotIn("SECRET_RATIONALE_Q1", str(caught.exception))

    def test_inputs_remain_unchanged_after_prompt_schema_and_validation(self):
        before = copy.deepcopy((self.request, self.package, self.blind, self.result))
        audit.build_explanation_review_prompt(self.request, self.package, self.blind, REVIEW_ID)
        schema = audit.explanation_review_schema(self.request, self.package, self.blind, REVIEW_ID)
        self.validate()
        schema["properties"].clear()
        self.assertEqual(before, (self.request, self.package, self.blind, self.result))
        self.assertIn("schema", audit.explanation_review_schema(self.request, self.package, self.blind, REVIEW_ID)["properties"])


class ExplanationSchemaTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.blind = review_fixture()
        self.result = result_fixture(self.request, self.package, self.blind)
        self.schema = audit.explanation_review_schema(self.request, self.package, self.blind, REVIEW_ID)

    def test_schema_uses_only_the_installed_harness_subset_and_strict_objects(self):
        self.assertTrue(valid(self.result, self.schema))
        for node in schema_nodes(self.schema):
            self.assertLessEqual(set(node), KEYWORDS)
            if node.get("type") == "object":
                self.assertFalse(node["additionalProperties"])
                self.assertEqual(set(node["properties"]), set(node["required"]))
        self.assertLess(len(json.dumps(self.schema).encode()), 65_536)

    def test_schema_rejects_extra_fields_null_status_and_wrong_question_hash_pairs(self):
        variants = []
        result = copy.deepcopy(self.result); result["overall_pass"] = True; variants.append(result)
        result = copy.deepcopy(self.result); result["explanation_reviews"][0]["unit_reviews"][0]["relation"] = None; variants.append(result)
        result = copy.deepcopy(self.result); result["explanation_reviews"][0]["question_id"] = "unknown"; variants.append(result)
        result = copy.deepcopy(self.result); result["explanation_reviews"][0]["explanation_sha256"] = "0" * 64; variants.append(result)
        for result in variants:
            self.assertFalse(valid(result, self.schema))

    def test_schema_accepts_input_locators_but_the_final_validator_requires_compiled_ranges(self):
        locator_result = with_locators(self.result)
        self.assertTrue(valid(locator_result, self.schema))
        with self.assertRaises(contract.ContractError):
            audit.validate_explanation_review(self.request, self.package, self.blind, locator_result, REVIEW_ID)

    def test_array_count_order_and_pass_evidence_remain_deterministic_host_checks(self):
        for mutate in (lambda r: r["explanation_reviews"].pop(),
                       lambda r: r["explanation_reviews"].reverse(),
                       lambda r: r["explanation_reviews"][0].update(source_refs=[])):
            result = copy.deepcopy(self.result); mutate(result)
            self.assertTrue(valid(result, self.schema))
            with self.assertRaises(contract.ContractError):
                audit.validate_explanation_review(self.request, self.package, self.blind, result, REVIEW_ID)

    def test_failed_observation_is_schema_valid_without_claiming_publication(self):
        self.result["explanation_reviews"][0]["unit_reviews"][0].update(relation="ambiguous", source_ref_indices=[],
            interpretations=["The article omits the method.", "The researchers never used the method."])
        self.assertTrue(valid(self.result, self.schema))
        receipt = audit.validate_explanation_review(self.request, self.package, self.blind, self.result, REVIEW_ID)
        self.assertEqual("rejected", receipt["status"])


class ExplanationUnitTest(unittest.TestCase):
    def test_partition_preserves_every_character_and_exact_utf16_ranges(self):
        examples = ["No final punctuation", " A.\r\n B。  ", "Dr. Ng measured 1.25 m. Next.",
                    "🔬 e\u0301。🔬 é！\n A? B; C；", "\n\r A\r\n\nB\t\n", "句。" * 700,
                    'He said, "not every." Then paused.', "Same. Same. Same."]
        for text in examples:
            with self.subTest(text=text[:30]):
                units = audit.explanation_units(text)
                self.assertGreaterEqual(len(units), 1)
                self.assertLessEqual(len(units), 8)
                self.assertEqual(text, "".join(unit["text"] for unit in units))
                self.assertEqual(0, units[0]["start"])
                self.assertEqual(contract.utf16_length(text), units[-1]["end"])
                for i, unit in enumerate(units):
                    self.assertEqual("u{}".format(i + 1), unit["unit_id"])
                    self.assertEqual(unit["text"], contract.utf16_slice(text, unit["start"], unit["end"]))
                    if i:
                        self.assertEqual(units[i - 1]["end"], unit["start"])

    def setUp(self):
        self.request, self.package, self.blind = negation_fixture("正确事实。无依据的推断。歧义表述。")
        self.result = result_fixture(self.request, self.package, self.blind)

    def validate(self, result=None):
        return audit.validate_explanation_review(self.request, self.package, self.blind,
            self.result if result is None else result, REVIEW_ID)

    def test_each_relation_derives_status_without_a_model_aggregate(self):
        for relation, expected in [("supported", "pass"), ("contradicted", "fail"),
                                   ("unsupported", "fail"), ("ambiguous", "insufficient")]:
            result = copy.deepcopy(self.result)
            unit = result["explanation_reviews"][0]["unit_reviews"][-1]
            unit["relation"] = relation
            if relation == "ambiguous":
                unit["interpretations"] = ["The source did not say it happened.", "It did not happen."]
            observation = self.validate(result)["explanation_results"][0]
            self.assertEqual(expected, observation["status"])
            self.assertEqual(3, len(observation["unit_results"]))

    def test_a_later_failure_blocks_even_after_an_ambiguity_or_supported_opening(self):
        units = self.result["explanation_reviews"][0]["unit_reviews"]
        units[1].update(relation="ambiguous", interpretations=["Reading one.", "Reading two."])
        units[2].update(relation="unsupported", reason="A later claim lacks source support.")
        receipt = self.validate()
        self.assertEqual("explanation_fail", receipt["blocked_items"][0]["code"])
        self.assertEqual(units[2]["reason"], receipt["blocked_items"][0]["reason"])

    def test_missing_duplicate_unknown_reordered_and_shortened_coverage_is_rejected(self):
        for mutate in [lambda rows: rows.pop(), lambda rows: rows.reverse(),
                       lambda rows: rows[1].update(unit_id="u1"),
                       lambda rows: rows[1].update(unit_id="invented"),
                       lambda rows: rows[0].update(text="partial clause"),
                       lambda rows: rows[0].update(start=1)]:
            result = copy.deepcopy(self.result)
            mutate(result["explanation_reviews"][0]["unit_reviews"])
            with self.assertRaises(contract.ContractError):
                self.validate(result)

    def test_unknown_or_missing_unit_fields_and_legacy_aggregate_cannot_bypass_coverage(self):
        for key in self.result["explanation_reviews"][0]["unit_reviews"][0]:
            result = copy.deepcopy(self.result)
            del result["explanation_reviews"][0]["unit_reviews"][0][key]
            with self.subTest(key=key), self.assertRaises(contract.ContractError):
                self.validate(result)
        for field in ("status", "reason"):
            result = copy.deepcopy(self.result)
            result["explanation_reviews"][0][field] = "pass"
            with self.assertRaises(contract.ContractError):
                self.validate(result)
        result = copy.deepcopy(self.result); result["schema"] = "lexicon.ielts.explanation-review.v1"
        with self.assertRaises(contract.ContractError):
            self.validate(result)

    def test_ambiguity_requires_two_distinct_bounded_readings_and_cannot_be_supported(self):
        for readings in [[], ["one"], ["same", " SAME "], ["same reading", " same  reading "],
                         ["a", "b", "c"], ["a", "x" * 241]]:
            result = copy.deepcopy(self.result)
            result["explanation_reviews"][0]["unit_reviews"][0].update(relation="ambiguous", interpretations=readings)
            with self.assertRaises(contract.ContractError):
                self.validate(result)
        self.result["explanation_reviews"][0]["unit_reviews"][0]["scope"] = "ambiguous"
        with self.assertRaises(contract.ContractError):
            self.validate()

    def test_scope_relations_and_evidence_indices_reject_invalid_types_and_values(self):
        for key, values in [("scope", [None, True, "unknown"]), ("relation", [None, True, "pass"]),
                            ("source_ref_indices", [[True], [-1], [1], [0, 0], [], [0.0]])]:
            for value in values:
                result = copy.deepcopy(self.result)
                result["explanation_reviews"][0]["unit_reviews"][0][key] = value
                with self.subTest(key=key, value=value), self.assertRaises(contract.ContractError):
                    self.validate(result)

    def test_total_text_budget_cannot_multiply_the_old_per_item_limit(self):
        units = self.result["explanation_reviews"][0]["unit_reviews"]
        for unit in units:
            unit.update(interpretations=["i" * 240], reason="r" * 320)
        with self.assertRaises(contract.ContractError) as caught:
            self.validate()
        self.assertEqual("explanation_review_budget", caught.exception.code)

    def test_maximum_question_and_unit_count_uses_a_shared_bounded_schema(self):
        self.request["reading_requests"] = [dict(self.request["reading_requests"][0], question_id="q{}".format(i)) for i in range(20)]
        question = self.package["reading_questions"][0]
        self.package["reading_questions"] = [dict(copy.deepcopy(question), question_id="q{}".format(i),
            prompt="Synthetic distinct structure item {}?".format(i), explanation="句。" * 800) for i in range(20)]
        self.package["request_sha256"] = contract.canonical_sha256(self.request)
        blind = self.blind["reading_reviews"][0]
        self.blind["reading_reviews"] = [dict(copy.deepcopy(blind), question_id="q{}".format(i)) for i in range(20)]
        self.blind.update(request_sha256=contract.canonical_sha256(self.request), package_sha256=contract.canonical_sha256(self.package))
        result = result_fixture(self.request, self.package, self.blind)
        schema = audit.explanation_review_schema(self.request, self.package, self.blind, REVIEW_ID)
        self.assertTrue(valid(result, schema))
        self.assertEqual("eligible_for_caller_publication", self.validate(result)["status"])
        # Schema sent to the runtime must remain below its 256 KiB metadata limit.
        self.assertLess(len(json.dumps(schema, ensure_ascii=False).encode()), 131072)
        self.assertLess(len(json.dumps(result, ensure_ascii=False).encode()), contract.MAX_JSON_BYTES)


if __name__ == "__main__":
    unittest.main()
