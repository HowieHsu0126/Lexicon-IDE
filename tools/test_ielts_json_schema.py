"""Offline schema and contract fixtures; no model/API or quality-rate evidence.

Uses installed jsonschema when available. The explicit fallback checks only the
small keyword subset emitted here; it is not a full JSON Schema implementation.
The original contract/review validators still check the quantitative/extraction
rules that the installed Harness schema dialect cannot express.
"""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest

try:
    from tools import ielts_contract as contract, ielts_review as review
    from tools import ielts_json_schema as schemas, ielts_corpus as corpus
    from tools.ielts_candidate import compile_candidate
    from tools.test_ielts_contract import fixture, reference
    from tools.test_ielts_review import review_fixture
except ImportError:
    import ielts_contract as contract
    import ielts_review as review
    import ielts_json_schema as schemas
    import ielts_corpus as corpus
    from ielts_candidate import compile_candidate
    from test_ielts_contract import fixture, reference
    from test_ielts_review import review_fixture

try:
    import jsonschema
except ImportError:
    jsonschema = None

KEYWORDS = {"type", "oneOf", "properties", "required", "additionalProperties", "items", "enum", "const", "description"}


def _same(left, right):
    return type(left) is type(right) and left == right


def subset_valid(value, schema):
    """Only this module's emitted subset, never a general schema validator."""
    assert set(schema) <= KEYWORDS, "Unsupported emitted schema keyword"
    if "oneOf" in schema and sum(subset_valid(value, child) for child in schema["oneOf"]) != 1:
        return False
    if "type" in schema:
        kinds = {"object": dict, "array": list, "string": str, "integer": int, "boolean": bool, "null": type(None)}
        if type(value) is not kinds[schema["type"]]:
            return False
    if "const" in schema and not _same(value, schema["const"]):
        return False
    if "enum" in schema and not any(_same(value, option) for option in schema["enum"]):
        return False
    if isinstance(value, dict) and "properties" in schema:
        properties = schema["properties"]
        if not set(schema.get("required", [])) <= set(value):
            return False
        if schema.get("additionalProperties") is False and not set(value) <= set(properties):
            return False
        if any(not subset_valid(item, properties[key]) for key, item in value.items() if key in properties):
            return False
    if isinstance(value, list) and "items" in schema:
        if any(not subset_valid(item, schema["items"]) for item in value):
            return False
    return True


def valid(value, schema):
    if jsonschema is not None:
        jsonschema.Draft202012Validator.check_schema(schema)
        return jsonschema.Draft202012Validator(schema).is_valid(value)
    return subset_valid(value, schema)


def schema_nodes(schema):
    yield schema
    for child in schema.get("properties", {}).values():
        yield from schema_nodes(child)
    for child in schema.get("oneOf", []):
        yield from schema_nodes(child)
    if "items" in schema:
        yield from schema_nodes(schema["items"])


def object_paths(value, path=()):
    if isinstance(value, dict):
        yield path
        for key, child in value.items():
            yield from object_paths(child, path + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from object_paths(child, path + (index,))


def at(value, path):
    for part in path:
        value = value[part]
    return value


def with_locators(value):
    """Make input-only locator fixtures without changing final-artifact fixtures."""
    changed = copy.deepcopy(value)
    for path in object_paths(changed):
        obj = at(changed, path)
        if set(obj) == {"paragraph_id", "start", "end", "quote"}:
            del obj["start"]
            del obj["end"]
    return changed


def draft_input(package):
    """Project this known authored fixture only; never infer a target from text."""
    changed = copy.deepcopy(package)
    writing = changed["writing_task2"]
    assert writing.pop("prompt") == "Public funds should prioritise clean energy. To what extent do you agree or disagree?"
    writing["prompt_draft"] = {"background": "", "target_statement": "Public funds should prioritise clean energy."}
    return changed


def capacity_fixture(*, mixed_rules: bool, long_ids: bool):
    """All 20 questions and 80 paragraphs, including every completion rule pair."""
    request, package, _ = fixture()
    originals = copy.deepcopy(package["reading_questions"])
    original_requests = copy.deepcopy(request["reading_requests"])
    def identifier(prefix, index):
        value = "{}{}".format(prefix, index)
        return value.ljust(128, "x") if long_ids else value
    text = "The council approved solar panels."
    step = contract.utf16_length(text) + 2
    request["paragraphs"] = [{
        "paragraph_id": identifier("p", index), "start": 9_000_000 + index * step,
        "end": 9_000_000 + index * step + step - 2, "text": text,
    } for index in range(80)]
    request["request_id"] = identifier("request", 0)
    request["article_id"] = identifier("article", 0)
    request["writing_task2_request"]["task_id"] = identifier("writing", 0)
    source = request["style_sources"][0]
    request["style_sources"] = [dict(source, source_id=identifier("source", i)) for i in range(12)]
    request["reading_requests"], package["reading_questions"] = [], []
    first = request["paragraphs"][0]
    for index in range(20):
        # Three non-completion kinds, all 8 word limits × 2 number policies,
        # and a repeated judgement type (its identity appears in both branches).
        template = (index if index < 3 else 3 if index < 19 else 1) if mixed_rules else 1
        wanted, question = copy.deepcopy(original_requests[template]), copy.deepcopy(originals[template])
        wanted["question_id"] = question["question_id"] = identifier("q", index)
        if template == 3:
            wanted.update(max_words=(index - 3) % 8 + 1, allow_numbers=(index - 3) // 8 == 0)
            question.update(max_words=wanted["max_words"], allow_numbers=wanted["allow_numbers"],
                            answer="panels", source_refs=[reference(first, "panels")])
        elif question["answer"] == "not_given":
            question["reviewed_paragraph_ids"] = [p["paragraph_id"] for p in request["paragraphs"]]
        else:
            question["source_refs"] = [reference(first, text)]
        if not mixed_rules and index == 1:
            question.update(prompt="The council rejected solar panels.", answer="false")
        elif not mixed_rules and index == 2:
            question.update(prompt="The approved solar panels cost five thousand pounds.", answer="not_given",
                            source_refs=[], reviewed_paragraph_ids=[p["paragraph_id"] for p in request["paragraphs"]],
                            absence_justification="Every fixture paragraph states approval only, without any price.")
        request["reading_requests"].append(wanted)
        package["reading_questions"].append(question)
    package.update(request_id=request["request_id"], article_id=request["article_id"],
                   request_sha256=contract.canonical_sha256(request),
                   style_source_ids=[s["source_id"] for s in request["style_sources"]])
    package["writing_task2"].update(task_id=request["writing_task2_request"]["task_id"],
                                   source_refs=[reference(first, text)])
    return request, package, identifier("review", 0)


def ready_bytes(request, schema):
    """Exact bridge envelope with the pinned corpus's complete document inventory."""
    value = {"schema": "lexicon.ielts.plugin-ready.v1",
             "document_ids": [document["document_id"] for document in corpus._documents()],
             "article_document_ids": ["article:" + p["paragraph_id"] for p in request["paragraphs"]],
             "modules": list(corpus.MODULES), "question_types": list(corpus.QUESTION_TYPES),
             "candidate_compiler_ready": True, "candidate_schema": schema}
    return len(json.dumps({"ok": True, "value": value}, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


class IeltsSchemaShapeTest(unittest.TestCase):
    def setUp(self):
        self.request, self.package, self.report = review_fixture()
        self.candidate = draft_input(self.package)
        self.review_id = self.report["review_id"]
        self.generation = schemas.generation_schema(self.request)
        self.independent = schemas.review_schema(self.request, self.package, self.review_id)

    def test_valid_generation_and_all_four_question_types(self):
        self.assertTrue(valid(self.candidate, self.generation))
        self.assertEqual("structurally_valid", contract.validate_package(self.request, self.package)["status"])
        self.assertEqual(list(contract.READING_TYPES), [q["type"] for q in self.package["reading_questions"]])

    def test_new_generation_requires_draft_while_saved_prompt_remains_valid(self):
        before = copy.deepcopy(self.package)
        self.assertFalse(valid(self.package, self.generation))
        self.assertTrue(valid(self.candidate, self.generation))
        self.assertEqual("structurally_valid", contract.validate_package(self.request, self.package)["status"])
        with self.assertRaises(contract.ContractError):
            contract.validate_package(self.request, self.candidate)
        self.assertEqual(before, self.package)

    def test_generation_writing_draft_is_closed_required_and_type_checked(self):
        mutations = [
            lambda w: w.pop("prompt_draft"),
            lambda w: w.update(prompt="A competing free prompt"),
            lambda w: w.update(prompt_draft="A free prompt is not a structured draft"),
            lambda w: w["prompt_draft"].pop("background"),
            lambda w: w["prompt_draft"].pop("target_statement"),
            lambda w: w["prompt_draft"].update(instruction="An instruction is host-owned"),
            lambda w: w["prompt_draft"].update(background=None),
            lambda w: w["prompt_draft"].update(target_statement=["A target"]),
        ]
        for index, mutate in enumerate(mutations):
            changed = copy.deepcopy(self.candidate)
            mutate(changed["writing_task2"])
            with self.subTest(mutation=index):
                self.assertFalse(valid(changed, self.generation))

    def test_writing_draft_compiles_to_prompt_only_and_review_sees_final_text(self):
        candidate = copy.deepcopy(self.candidate)
        source_background = candidate["writing_task2"]["source_refs"][0]["quote"]
        candidate["writing_task2"]["prompt_draft"]["background"] = source_background
        before = copy.deepcopy(candidate)
        self.assertTrue(valid(candidate, self.generation))
        compiled = compile_candidate(self.request, candidate, "generation")
        expected = (source_background + "\n\n" +
                    "Public funds should prioritise clean energy.\n\n"
                    "To what extent do you agree or disagree with this statement?")
        self.assertEqual(expected, compiled["writing_task2"]["prompt"])
        self.assertNotIn("prompt_draft", compiled["writing_task2"])
        self.assertEqual(before, candidate)
        self.assertEqual("structurally_valid", contract.validate_package(self.request, compiled)["status"])
        visible = contract.candidate_view(self.request, compiled)
        self.assertEqual(expected, visible["writing_task2"]["prompt"])
        self.assertNotIn("prompt_draft", json.dumps(visible))
        self.assertNotIn("target_statement", json.dumps(visible))
        independent = schemas.review_schema(self.request, compiled, self.review_id)
        self.assertNotIn("prompt_draft", json.dumps(independent))
        with self.assertRaises(contract.ContractError):
            schemas.review_schema(self.request, candidate, self.review_id)
        changed = copy.deepcopy(self.report)
        changed["package_sha256"] = contract.canonical_sha256(compiled)
        self.assertTrue(valid(changed, independent))
        changed["writing_task2_review"]["prompt_draft"] = candidate["writing_task2"]["prompt_draft"]
        self.assertFalse(valid(changed, independent))

    def test_writing_draft_empty_background_has_no_leading_empty_paragraph(self):
        compiled = compile_candidate(self.request, self.candidate, "generation")
        self.assertEqual("Public funds should prioritise clean energy.\n\n"
                         "To what extent do you agree or disagree with this statement?",
                         compiled["writing_task2"]["prompt"])
        self.assertTrue(valid(self.candidate, self.generation))
        writing = self.generation["properties"]["writing_task2"]["properties"]
        self.assertNotIn("prompt", writing)
        self.assertIn("0–1800", writing["prompt_draft"]["properties"]["background"]["description"])

    def test_writing_draft_quantitative_and_paragraph_rules_are_host_owned(self):
        mutations = [
            {"target_statement": ""},
            {"background": " \t"},
            {"target_statement": "A first claim.\nA second paragraph."},
            {"target_statement": "A first claim.\u0085A second paragraph."},
            {"target_statement": "🔬" * 901},
            {"background": "B" * 1000, "target_statement": "T" * 800},
        ]
        for index, values in enumerate(mutations):
            candidate = copy.deepcopy(self.candidate)
            candidate["writing_task2"]["prompt_draft"].update(values)
            with self.subTest(mutation=index):
                self.assertTrue(valid(candidate, self.generation),
                                "The pinned schema subset cannot express length, blankness or paragraph restrictions")
                with self.assertRaises(contract.ContractError):
                    compile_candidate(self.request, candidate, "generation")

    def test_valid_independent_review_and_nested_fields(self):
        self.assertTrue(valid(self.report, self.independent))
        self.assertEqual("eligible_for_caller_publication", review.validate_review(self.request, self.package, self.report, self.review_id)["status"])

    def test_only_runtime_supported_keywords_and_all_objects_are_closed(self):
        for schema in (self.generation, self.independent):
            for node in schema_nodes(schema):
                self.assertLessEqual(set(node), KEYWORDS)
                if "const" in node:
                    self.assertIn(type(node["const"]), (str, int, bool, type(None)))
                if "enum" in node:
                    self.assertTrue(all(type(item) in (str, int, bool, type(None)) for item in node["enum"]))
                if node.get("type") == "object":
                    self.assertEqual(set(node["properties"]), set(node["required"]))
                    self.assertIs(False, node["additionalProperties"])

    def test_generation_every_object_rejects_missing_and_unknown_fields(self):
        for path in object_paths(self.candidate):
            for mode in ("missing", "unknown"):
                changed = copy.deepcopy(self.candidate)
                obj = at(changed, path)
                if mode == "missing":
                    obj.pop(next(iter(obj)))
                else:
                    obj["undeclared_fixture_field"] = "do not accept"
                with self.subTest(path=path, mode=mode):
                    self.assertFalse(valid(changed, self.generation))

    def test_review_every_object_rejects_missing_and_unknown_fields(self):
        for path in object_paths(self.report):
            for mode in ("missing", "unknown"):
                changed = copy.deepcopy(self.report)
                obj = at(changed, path)
                if mode == "missing":
                    obj.pop(next(iter(obj)))
                else:
                    obj["undeclared_fixture_field"] = "do not accept"
                with self.subTest(path=path, mode=mode):
                    self.assertFalse(valid(changed, self.independent))

    def test_frozen_generation_identities_and_constants(self):
        for field in ("schema", "request_id", "article_id", "body_hash", "request_sha256", "label"):
            changed = copy.deepcopy(self.package); changed[field] = "wrong"
            self.assertFalse(valid(draft_input(changed), self.generation), field)
        for field in ("task_id", "task_type", "minimum_words", "suggested_minutes"):
            changed = copy.deepcopy(self.package); changed["writing_task2"][field] = "wrong"
            self.assertFalse(valid(draft_input(changed), self.generation), field)
        changed = copy.deepcopy(self.package); changed["style_source_ids"] = ["unfrozen-source"]
        self.assertFalse(valid(draft_input(changed), self.generation))

    def test_review_identity_and_no_model_runtime_attestation(self):
        for field in ("schema", "review_id", "request_sha256", "package_sha256"):
            changed = copy.deepcopy(self.report); changed[field] = "wrong"
            self.assertFalse(valid(changed, self.independent), field)
        for field in ("runtime_attestation", "overall_pass", "reading_questions", "writing_task2"):
            changed = copy.deepcopy(self.report); changed[field] = True
            self.assertFalse(valid(changed, self.independent), field)

    def test_question_id_and_type_are_bound_together(self):
        for field, replacement in (("question_id", "q4"), ("type", "identifying_information")):
            for schema, original, array in ((self.generation, self.candidate, "reading_questions"),
                                            (self.independent, self.report, "reading_reviews")):
                changed = copy.deepcopy(original); changed[array][0][field] = replacement
                self.assertFalse(valid(changed, schema))

    def test_judgement_vocabularies_are_distinct(self):
        for index, wrong in ((0, "E"), (1, "yes"), (2, "true")):
            changed = copy.deepcopy(self.package); changed["reading_questions"][index]["answer"] = wrong
            self.assertFalse(valid(draft_input(changed), self.generation))
            changed = copy.deepcopy(self.report); changed["reading_reviews"][index]["observed_answer"] = wrong
            self.assertFalse(valid(changed, self.independent))

    def test_option_identity_and_single_answer_contract(self):
        changed = copy.deepcopy(self.package); changed["reading_questions"][0]["number_of_answers"] = 2
        self.assertFalse(valid(draft_input(changed), self.generation))
        changed = copy.deepcopy(self.package); changed["reading_questions"][0]["options"][0]["option_id"] = "E"
        self.assertFalse(valid(draft_input(changed), self.generation))
        changed = copy.deepcopy(self.report); changed["reading_reviews"][0]["option_reviews"][0]["status"] = "correct"
        self.assertFalse(valid(changed, self.independent))

    def test_completion_frozen_limits_and_rule_are_not_model_choices(self):
        for field, replacement in (("max_words", 8), ("allow_numbers", True), ("normalization_rule", "official-ielts")):
            changed = copy.deepcopy(self.package); changed["reading_questions"][3][field] = replacement
            self.assertFalse(valid(draft_input(changed), self.generation))

    def test_generation_not_given_requires_exact_scope_and_no_supporting_quote(self):
        for field in ("reviewed_paragraph_ids", "absence_justification"):
            changed = copy.deepcopy(self.package); del changed["reading_questions"][2][field]
            self.assertFalse(valid(draft_input(changed), self.generation))
        changed = copy.deepcopy(self.package); changed["reading_questions"][2]["source_refs"] = [reference(self.request["paragraphs"][0], "solar panels")]
        self.assertTrue(valid(draft_input(changed), self.generation), "Harness cannot declare empty-array const/length; the host enforces it")
        with self.assertRaises(contract.ContractError): contract.validate_package(self.request, changed)
        changed = copy.deepcopy(self.package); changed["reading_questions"][2]["reviewed_paragraph_ids"] = ["p2", "p1"]
        self.assertTrue(valid(draft_input(changed), self.generation), "Harness cannot declare array const/order; the host enforces it")
        with self.assertRaises(contract.ContractError): contract.validate_package(self.request, changed)

    def test_generation_non_ng_forbids_absence_fields(self):
        changed = copy.deepcopy(self.package); changed["reading_questions"][1]["absence_justification"] = "No evidence"
        self.assertFalse(valid(draft_input(changed), self.generation))

    def test_both_judgement_types_can_independently_choose_not_given(self):
        changed = copy.deepcopy(self.package)
        changed["reading_questions"][1].update(answer="not_given", source_refs=[], reviewed_paragraph_ids=["p1", "p2"], absence_justification="The supplied passage does not settle the claim.")
        self.assertTrue(valid(draft_input(changed), self.generation))
        contract.validate_package(self.request, changed)
        report = copy.deepcopy(self.report)
        report["reading_reviews"][1].update(observed_answer="not_given", source_refs=[], not_given_review=copy.deepcopy(report["reading_reviews"][2]["not_given_review"]))
        self.assertTrue(valid(report, self.independent))
        self.assertEqual("rejected", review.validate_review(self.request, self.package, report, self.review_id)["status"])

    def test_review_non_ng_requires_explicit_null_scope(self):
        changed = copy.deepcopy(self.report); changed["reading_reviews"][1]["not_given_review"] = {}
        self.assertFalse(valid(changed, self.independent))
        changed = copy.deepcopy(self.report); changed["reading_reviews"][2]["not_given_review"] = None
        self.assertFalse(valid(changed, self.independent))

    def test_review_honest_null_and_failed_checks_are_not_forced_to_pass(self):
        for index in range(4):
            changed = copy.deepcopy(self.report); item = changed["reading_reviews"][index]
            item.update(observed_answer=None, source_refs=[])
            if "not_given_review" in item:
                item["not_given_review"] = None
            if "option_reviews" in item:
                for option in item["option_reviews"]:
                    option.update(status="insufficient", source_ref_indices=[])
            item["checks"]["answerable"]["status"] = "insufficient"
            self.assertTrue(valid(changed, self.independent))
            self.assertEqual("rejected", review.validate_review(self.request, self.package, changed, self.review_id)["status"])

    def test_review_alternatives_are_exact_text_and_single_reference_objects(self):
        changed = copy.deepcopy(self.report)
        changed["reading_reviews"][3]["alternative_answers"] = [{"text": "council", "source_ref": reference(self.request["paragraphs"][0], "council")}]
        self.assertTrue(valid(changed, self.independent))
        self.assertEqual("rejected", review.validate_review(self.request, self.package, changed, self.review_id)["status"])
        changed["reading_reviews"][3]["alternative_answers"][0]["answer"] = "council"
        self.assertFalse(valid(changed, self.independent))

    def test_reference_field_types_are_checked_by_schema(self):
        for field, wrong in (("paragraph_id", 1), ("start", True), ("end", "42"), ("quote", ["text"])):
            changed = copy.deepcopy(self.package); changed["reading_questions"][0]["source_refs"][0][field] = wrong
            self.assertFalse(valid(draft_input(changed), self.generation))
        changed = copy.deepcopy(self.report); changed["reading_reviews"][0]["option_reviews"][0]["source_ref_indices"] = ["0"]
        self.assertFalse(valid(changed, self.independent))

    def test_option_evidence_requirement_is_declared_and_enforced_by_host(self):
        descriptions = [node.get("description", "") for node in schema_nodes(self.independent)]
        self.assertTrue(any("Supported and contradicted each require at least one exact source reference index" in text
                            and "correctable structure error" in text for text in descriptions))
        for index, status in ((0, "supported"), (1, "contradicted")):
            changed = copy.deepcopy(self.report)
            changed["reading_reviews"][0]["option_reviews"][index].update(status=status, source_ref_indices=[])
            self.assertTrue(valid(changed, self.independent), "The installed Harness subset cannot express conditional array minima")
            with self.subTest(status=status), self.assertRaises(contract.ContractError) as caught:
                review.validate_review(self.request, self.package, changed, self.review_id)
            self.assertEqual("review_option_evidence_required", caught.exception.code)

    def test_generation_locators_are_valid_tool_inputs_but_not_final_artifacts(self):
        candidate = with_locators(self.candidate)
        before = copy.deepcopy(self.package)
        self.assertTrue(valid(candidate, self.generation))
        self.assertEqual({"paragraph_id", "quote"}, set(candidate["reading_questions"][0]["source_refs"][0]))
        self.assertEqual({"paragraph_id", "quote"}, set(candidate["writing_task2"]["source_refs"][0]))
        with self.assertRaises(contract.ContractError):
            contract.validate_package(self.request, candidate)
        self.assertEqual(before, self.package)
        self.assertEqual("structurally_valid", contract.validate_package(self.request, self.package)["status"])

    def test_review_locators_cover_source_context_writing_and_alternative_references(self):
        candidate = with_locators(self.report)
        candidate["reading_reviews"][3]["alternative_answers"] = [{
            "text": "council", "source_ref": {"paragraph_id": "p1", "quote": "council"},
        }]
        paths = (
            ("reading_reviews", 0, "source_refs", 0),
            ("reading_reviews", 2, "not_given_review", "paragraph_reviews", 0, "context_refs", 0),
            ("reading_reviews", 3, "alternative_answers", 0, "source_ref"),
            ("writing_task2_review", "source_refs", 0),
        )
        for path in paths:
            self.assertEqual({"paragraph_id", "quote"}, set(at(candidate, path)))
        self.assertTrue(valid(candidate, self.independent))
        with self.assertRaises(contract.ContractError):
            review.validate_review(self.request, self.package, candidate, self.review_id)
        self.assertEqual("eligible_for_caller_publication", review.validate_review(
            self.request, self.package, self.report, self.review_id)["status"])

    def test_each_locator_rejects_missing_unknown_and_partial_offset_fields(self):
        report = copy.deepcopy(self.report)
        report["reading_reviews"][3]["alternative_answers"] = [{
            "text": "council", "source_ref": reference(self.request["paragraphs"][0], "council"),
        }]
        for original, schema in ((self.candidate, self.generation), (report, self.independent)):
            candidate = with_locators(original)
            paths = [path for path in object_paths(candidate)
                     if set(at(candidate, path)) == {"paragraph_id", "quote"}]
            for path in paths:
                for mode in ("missing_paragraph", "missing_quote", "unknown", "start_only", "end_only"):
                    changed = copy.deepcopy(candidate)
                    ref = at(changed, path)
                    if mode == "missing_paragraph": del ref["paragraph_id"]
                    elif mode == "missing_quote": del ref["quote"]
                    elif mode == "start_only": ref["start"] = 0
                    elif mode == "end_only": ref["end"] = 1
                    else: ref["search"] = "approximate"
                    with self.subTest(path=path, mode=mode):
                        self.assertFalse(valid(changed, schema))

    def test_locator_types_are_checked_in_both_modes(self):
        for original, schema, array in ((self.candidate, self.generation, "reading_questions"),
                                        (self.report, self.independent, "reading_reviews")):
            for field, wrong in (("paragraph_id", 1),
                                  ("quote", None), ("quote", ["text"]), ("quote", True)):
                changed = with_locators(original)
                changed[array][0]["source_refs"][0][field] = wrong
                with self.subTest(array=array, field=field, wrong=wrong):
                    self.assertFalse(valid(changed, schema))

    def test_unknown_paragraphs_are_rejected_by_compiler_and_final_contract(self):
        for mode, original, schema, array in (
                ("generation", self.package, self.generation, "reading_questions"),
                ("review", self.report, self.independent, "reading_reviews")):
            for locator in (False, True):
                changed = copy.deepcopy(original)
                ref = changed[array][0]["source_refs"][0]
                ref["paragraph_id"] = "unseen"
                if locator:
                    del ref["start"]; del ref["end"]
                with self.subTest(mode=mode, locator=locator):
                    self.assertTrue(valid(draft_input(changed) if mode == "generation" else changed, schema), "Input schema checks the ID type; host checks frozen membership")
                    with self.assertRaises(contract.ContractError) as caught:
                        compile_candidate(self.request, changed, mode)
                    self.assertEqual("unknown_paragraph", caught.exception.code)
                    if not locator:
                        with self.assertRaises(contract.ContractError) as final_error:
                            if mode == "generation": contract.validate_package(self.request, changed)
                            else: review.validate_review(self.request, self.package, changed, self.review_id)
                        self.assertEqual("unknown_paragraph" if mode == "generation" else "review_source",
                                         final_error.exception.code)

    def test_full_and_locator_references_can_mix_without_ambiguous_union(self):
        full = reference(self.request["paragraphs"][0], "solar panels")
        locator = {"paragraph_id": "p2", "quote": self.request["paragraphs"][1]["text"]}
        for original, schema, field in ((self.candidate, self.generation, "writing_task2"),
                                        (self.report, self.independent, "writing_task2_review")):
            changed = copy.deepcopy(original)
            changed[field]["source_refs"] = [full, locator]
            self.assertTrue(valid(changed, schema))
            branches = schema["properties"][field]["properties"]["source_refs"]["items"]["oneOf"]
            self.assertEqual(2, len(branches))
            self.assertEqual(1, sum(valid(full, branch) for branch in branches))
            self.assertEqual(1, sum(valid(locator, branch) for branch in branches))
            invalid = dict(full, start=True)
            self.assertFalse(valid(invalid, schema["properties"][field]["properties"]["source_refs"]["items"]),
                             "An invalid full reference cannot fall back to the closed locator form")

    def test_locator_match_rules_remain_host_checks_not_source_text_in_schema(self):
        # The pinned runtime cannot express nonempty/unique literal-match rules.
        # This only verifies input shape, never successful locator compilation.
        for quote in ("", " \t", "Text that does not exist in the frozen paragraph"):
            changed = with_locators(self.candidate)
            changed["reading_questions"][0]["source_refs"][0]["quote"] = quote
            self.assertTrue(valid(changed, self.generation))
        for schema in (self.generation, self.independent):
            descriptions = " ".join(node.get("description", "") for node in schema_nodes(schema))
            for rule in ("case-sensitive", "not normalized", "repeated quote is rejected", "lexicon_ielts_read"):
                self.assertIn(rule, descriptions)

    def test_array_counts_order_and_duplicate_questions_remain_host_checks(self):
        for mode in ("omit", "repeat", "reverse"):
            changed = copy.deepcopy(self.package)
            if mode == "omit": changed["reading_questions"].pop()
            elif mode == "repeat": changed["reading_questions"][1] = copy.deepcopy(changed["reading_questions"][0])
            else: changed["reading_questions"].reverse()
            self.assertTrue(valid(draft_input(changed), self.generation), "Pinned Harness lacks tuple/length validation")
            with self.assertRaises(contract.ContractError): contract.validate_package(self.request, changed)
        description = self.generation["properties"]["reading_questions"]["description"]
        self.assertIn("Exactly 4", description); self.assertIn("q1, q2, q3, q4", description)

    def test_review_counts_and_option_reference_indices_remain_host_checks(self):
        changed = copy.deepcopy(self.report); changed["reading_reviews"].reverse()
        self.assertTrue(valid(changed, self.independent))
        with self.assertRaises(contract.ContractError): review.validate_review(self.request, self.package, changed, self.review_id)
        changed = copy.deepcopy(self.report); changed["reading_reviews"][0]["option_reviews"][0]["source_ref_indices"] = [4]
        self.assertTrue(valid(changed, self.independent))
        with self.assertRaises(contract.ContractError): review.validate_review(self.request, self.package, changed, self.review_id)

    def test_writing_instruction_alignment_shape_is_closed_and_required(self):
        changes = [
            lambda r: r['writing_task2_review'].pop('instruction_alignment'),
            lambda r: r['writing_task2_review']['instruction_alignment'].update(relation='pass'),
            lambda r: r['writing_task2_review']['instruction_alignment'].update(start=0),
            lambda r: r['writing_task2_review']['instruction_alignment']['target_readings'][0].update(end=10),
            lambda r: r['writing_task2_review']['instruction_alignment']['target_readings'][0].pop('interpretation'),
        ]
        for change in changes:
            changed = copy.deepcopy(self.report); change(changed)
            self.assertFalse(valid(changed, self.independent))

    def test_writing_instruction_quote_and_count_checks_remain_host_owned(self):
        changes = [
            lambda r: r['writing_task2_review']['instruction_alignment'].update(target_readings=[]),
            lambda r: r['writing_task2_review']['instruction_alignment'].update(instruction_quote='Quote only present in an unrelated article.'),
            lambda r: r['writing_task2_review']['instruction_alignment']['target_readings'][0].update(target_quote='nergy'),
        ]
        for change in changes:
            changed = copy.deepcopy(self.report); change(changed)
            self.assertTrue(valid(changed, self.independent))
            with self.assertRaises(contract.ContractError):
                review.validate_review(self.request, self.package, changed, self.review_id)

    def test_writing_instruction_mismatch_is_a_valid_structural_rejection(self):
        changed = copy.deepcopy(self.report)
        changed['writing_task2_review']['instruction_alignment'].update(relation='task_mismatch', target_readings=[])
        self.assertTrue(valid(changed, self.independent))
        result = review.validate_review(self.request, self.package, changed, self.review_id)
        self.assertEqual('rejected', result['status'])
        self.assertIn('writing_instruction_task_mismatch', [item['code'] for item in result['blockers']])

    def test_completion_sentence_quote_cannot_substitute_for_exact_phrase(self):
        changed = copy.deepcopy(self.package)
        changed["reading_questions"][3]["source_refs"] = [reference(self.request["paragraphs"][0], "The council approved solar panels.")]
        self.assertTrue(valid(draft_input(changed), self.generation))
        with self.assertRaises(contract.ContractError) as caught: contract.validate_package(self.request, changed)
        self.assertEqual("completion_not_extractive", caught.exception.code)
        self.assertIn("just that phrase", json.dumps(self.generation))
        self.assertIn("just that phrase", json.dumps(self.independent))

    def test_utf16_exact_ranges_and_text_bounds_remain_host_checks(self):
        changed = copy.deepcopy(self.package); changed["reading_questions"][0]["source_refs"][0]["start"] += 1
        self.assertTrue(valid(draft_input(changed), self.generation))
        with self.assertRaises(contract.ContractError): contract.validate_package(self.request, changed)
        changed = copy.deepcopy(self.package); changed["reading_questions"][0]["prompt"] = "🔬" * 601
        self.assertTrue(valid(draft_input(changed), self.generation))
        with self.assertRaises(contract.ContractError) as caught: contract.validate_package(self.request, changed)
        self.assertEqual("text_bound", caught.exception.code)
        self.assertIn("p1=[0,", self.generation["description"])

    def test_review_schema_does_not_contain_generator_answer_material(self):
        raw = json.dumps(self.independent, ensure_ascii=False)
        for forbidden in ("SECRET_RATIONALE_Q1", "PRIVATE_NG_JUSTIFICATION", "PRIVATE_COMPLETION_EXPLANATION", "solar panels", "Solar panels", '"explanation"', '"answer"'):
            self.assertNotIn(forbidden, raw)
        changed = copy.deepcopy(self.package)
        changed["reading_questions"][0].update(answer="D", explanation="changed private reasoning",
                                              source_refs=[reference(self.request["paragraphs"][0], "solar panels")])
        changed["reading_questions"][1].update(answer="not_given", source_refs=[],
                                              reviewed_paragraph_ids=["p1", "p2"],
                                              absence_justification="Private newly changed absence finding")
        changed["reading_questions"][2]["absence_justification"] = "changed private absence rationale"
        other = schemas.review_schema(self.request, changed, self.review_id)
        first = copy.deepcopy(self.independent)
        first["properties"]["package_sha256"]["const"] = "opaque binding"
        other["properties"]["package_sha256"]["const"] = "opaque binding"
        self.assertEqual(first, other, "Only the opaque package digest may depend on generator private fields")

    def test_inputs_and_returned_schemas_are_independent(self):
        before = copy.deepcopy((self.request, self.package, self.report))
        self.generation["properties"]["request_id"]["const"] = "modified"
        self.independent["properties"]["reading_reviews"]["items"]["oneOf"].clear()
        self.assertEqual(before, (self.request, self.package, self.report))
        self.assertTrue(valid(self.candidate, schemas.generation_schema(self.request)))
        self.assertTrue(valid(self.report, schemas.review_schema(self.request, self.package, self.review_id)))

    def test_invalid_request_package_and_review_id_fail_before_schema_delivery(self):
        bad = copy.deepcopy(self.request); bad["reading_requests"][0]["type"] = "unsupported"
        with self.assertRaises(contract.ContractError): schemas.generation_schema(bad)
        bad = copy.deepcopy(self.package); bad["request_id"] = "another"
        with self.assertRaises(contract.ContractError): schemas.review_schema(self.request, bad, self.review_id)
        with self.assertRaises(contract.ContractError): schemas.review_schema(self.request, self.package, "../review")

    def test_generation_import_does_not_require_review_module(self):
        code = """
import builtins, sys
sys.path.insert(0, sys.argv[1])
original = builtins.__import__
def guarded(name, *args, **kwargs):
    if name.endswith('ielts_review'):
        raise AssertionError('generation imported review capability')
    return original(name, *args, **kwargs)
builtins.__import__ = guarded
import ielts_json_schema
from test_ielts_contract import fixture
assert ielts_json_schema.generation_schema(fixture()[0])['type'] == 'object'
"""
        result = subprocess.run([sys.executable, "-c", code, str(Path(__file__).resolve().parent)], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_single_question_schema_has_no_empty_or_ambiguous_union(self):
        request = copy.deepcopy(self.request); request["reading_requests"] = request["reading_requests"][:1]
        package = copy.deepcopy(self.package); package["reading_questions"] = package["reading_questions"][:1]
        package["request_sha256"] = contract.canonical_sha256(request)
        schema = schemas.generation_schema(request)
        self.assertEqual("object", schema["properties"]["reading_questions"]["items"]["type"])
        self.assertTrue(valid(draft_input(package), schema))

    def test_schema_growth_is_bounded_for_maximum_request_shape(self):
        request = copy.deepcopy(self.request)
        request["paragraphs"] = [{"paragraph_id": "p{}".format(i), "start": i * 2, "end": i * 2 + 1, "text": "X"} for i in range(80)]
        request["reading_requests"] = [{"question_id": "q{}".format(i), "type": "identifying_information"} for i in range(20)]
        package = copy.deepcopy(self.package)
        package["request_sha256"] = contract.canonical_sha256(request)
        package["reading_questions"] = [dict(question_id=q["question_id"], type=q["type"], prompt="Claim", answer="true", explanation="Fixture", source_refs=[reference(request["paragraphs"][0], "X")]) for q in request["reading_requests"]]
        package["writing_task2"]["source_refs"] = [reference(request["paragraphs"][0], "X")]
        for schema in (schemas.generation_schema(request), schemas.review_schema(request, package, self.review_id)):
            self.assertLessEqual(ready_bytes(request, schema), 65_536)
        self.assertLess(len(json.dumps(self.generation).encode("utf-8")), 30_000)
        self.assertLess(len(json.dumps(self.independent).encode("utf-8")), 55_000)

    def test_grouping_never_combines_different_question_rules(self):
        request, package, review_id = capacity_fixture(mixed_rules=True, long_ids=False)
        rules = {q["question_id"]: (q["type"], q.get("max_words"), q.get("allow_numbers"))
                 for q in request["reading_requests"]}
        for schema in (schemas.generation_schema(request), schemas.review_schema(request, package, review_id)):
            seen = set()
            for node in schema_nodes(schema):
                identity = node.get("properties", {}).get("question_id")
                if identity is None:
                    continue
                identities = identity.get("enum", [identity.get("const")])
                self.assertEqual(1, len({rules[qid] for qid in identities}))
                seen.update(identities)
            self.assertEqual(set(rules), seen)
        generation = schemas.generation_schema(request)
        self.assertTrue(valid(draft_input(package), generation))
        for field, value in (("max_words", 2), ("allow_numbers", False)):
            changed = copy.deepcopy(package)
            changed["reading_questions"][3][field] = value
            self.assertFalse(valid(draft_input(changed), generation), "A rule from another question cannot be used with this ID")

    def test_equivalent_questions_share_a_branch_but_host_order_stays_exact(self):
        request, package, review_id = capacity_fixture(mixed_rules=False, long_ids=False)
        for schema, field in ((schemas.generation_schema(request), "reading_questions"),
                              (schemas.review_schema(request, package, review_id), "reading_reviews")):
            items = schema["properties"][field]["items"]
            # The two branches are answer/not_given forms, each bound to all
            # twenty same-rule IDs; they do not repeat twenty full schemas.
            self.assertEqual(2, len(items["oneOf"]))
            for branch in items["oneOf"]:
                self.assertEqual([q["question_id"] for q in request["reading_requests"]],
                                 branch["properties"]["question_id"]["enum"])
            self.assertIn("Exactly 20", schema["properties"][field]["description"])
        changed = copy.deepcopy(package)
        changed["reading_questions"].reverse()
        self.assertTrue(valid(draft_input(changed), schemas.generation_schema(request)))
        with self.assertRaises(contract.ContractError): contract.validate_package(request, changed)

    def test_complete_ready_envelope_fits_independent_metadata_budget(self):
        scenarios = [("typical_four", self.request, self.package, self.review_id)]
        for mixed in (False, True):
            for long_ids in (False, True):
                request, package, review_id = capacity_fixture(mixed_rules=mixed, long_ids=long_ids)
                scenarios.append(("maximum_mixed_{}_long_{}".format(mixed, long_ids), request, package, review_id))
        request, package, review_id = capacity_fixture(mixed_rules=True, long_ids=True)
        request["reading_requests"] = request["reading_requests"][:4]
        package["reading_questions"] = package["reading_questions"][:4]
        package["request_sha256"] = contract.canonical_sha256(request)
        scenarios.append(("four_questions_eighty_long_ids", request, package, review_id))
        for name, request, package, review_id in scenarios:
            contract.validate_request(request)
            contract.validate_package(request, package)
            for mode, schema in (("generation", schemas.generation_schema(request)),
                                 ("review", schemas.review_schema(request, package, review_id))):
                with self.subTest(scenario=name, mode=mode):
                    # Startup metadata contains the entire tool schema. It has
                    # its own bound; ordinary model-visible tool results keep
                    # the existing 65,536-byte limit in the plugin.
                    self.assertLessEqual(ready_bytes(request, schema), 262_144)
                    if len(request["reading_requests"]) == 4:
                        self.assertLessEqual(ready_bytes(request, schema), 65_536)
                    if len(request["paragraphs"][0]["paragraph_id"]) == 128:
                        raw = json.dumps(schema, ensure_ascii=False)
                        for paragraph in request["paragraphs"]:
                            self.assertEqual(1, raw.count(paragraph["paragraph_id"]),
                                             "Frozen paragraph membership/bounds are declared once at the root")


if __name__ == "__main__":
    print("Schema validator: {}".format("jsonschema.Draft202012Validator" if jsonschema is not None else "local emitted-subset checker (not full JSON Schema validation)"), file=sys.stderr)
    unittest.main()
