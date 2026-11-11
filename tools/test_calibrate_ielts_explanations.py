"""Offline calibration orchestration tests. Mock outputs are not semantic evidence."""
import copy
from contextlib import ExitStack, redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

try:
    from tools import calibrate_ielts_explanations as calibration
except ImportError:
    import calibrate_ielts_explanations as calibration

import ielts_corpus


class CalibrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = TemporaryDirectory()
        cls.corpus = Path(cls.shared.name) / "corpus.json"
        ielts_corpus.build(cls.corpus)
        path = calibration.TOOLS.parent / "docs/research/explanation-calibration-20261110/cases.json"
        cls.original = json.loads(path.read_text())

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cases = self.root / "cases.json"
        self.private = copy.deepcopy(self.original)
        self.private["cases"][0]["label_reason"] = "SCORING_ONLY_SENTINEL"
        self.cases.write_text(json.dumps(self.private, ensure_ascii=False))
        self.out = self.root / "run"

    def run_calibration(self, **kwargs):
        return calibration.run_calibration(self.cases, self.corpus, self.out, **kwargs)

    def test_reasoning_configuration_is_explicit_and_keeps_default(self):
        summary = self.run_calibration(reasoning_effort="low")
        self.assertEqual("low", summary["reasoning_effort"])
        self.assertFalse(summary["publication_eligible"])

    def test_dry_run_builds_five_valid_groups_without_key_route_or_native_access(self):
        with patch.object(calibration, "read_credential") as key, patch.object(calibration, "ModelRoute") as route, \
                patch.object(calibration, "prepare_stage") as prepare, patch.object(calibration, "run_native_stage") as native:
            summary = self.run_calibration()
        for operation in (key, route, prepare, native):
            operation.assert_not_called()
        self.assertEqual("dry_run", summary["status"])
        self.assertEqual(5, summary["group_count"])
        self.assertEqual(9, summary["case_count"])
        self.assertEqual(0, summary["real_provider_requests"])
        self.assertFalse(summary["all_expected_relations_matched"])
        self.assertFalse(summary["publication_eligible"])
        self.assertFalse(summary["independent_answer_review_performed"])
        self.assertEqual(self.cases.read_bytes(), (self.out / "scoring/cases.json").read_bytes())
        self.assertEqual(5, len(list((self.out / "materials").glob("*/prompt.txt"))))

    def test_labels_do_not_reach_prompt_request_package_or_blind_inputs(self):
        _, groups, _ = calibration.load_cases(self.cases)
        all_sources = {case["source"] for case in self.private["cases"]}
        for i, group in enumerate(groups, 1):
            self.assertTrue(all(set(case) == set(calibration.INPUT_FIELDS) for case in group))
            material = calibration.build_material(group, self.corpus, i)
            rendered = json.dumps(material, ensure_ascii=False)
            self.assertNotIn("SCORING_ONLY_SENTINEL", rendered)
            for field in ("expected_relation", "pair_id", "label_reason", "case_kind", "original_explanation_span"):
                self.assertNotIn('"' + field + '"', rendered)
            joined = "\n\n".join(p["text"] for p in material["request"]["paragraphs"])
            self.assertEqual(group[0]["source"], joined)
            for other in all_sources - {joined}:
                self.assertNotIn(other, material["prompt"])
            self.assertEqual([case["explanation"] for case in group],
                [q["explanation"] for q in material["package"]["reading_questions"]])

    def test_material_builder_rejects_unprojected_or_cross_source_cases(self):
        _, groups, _ = calibration.load_cases(self.cases)
        for key in ("expected_relation", "pair_id", "scope", "label_reason"):
            group = copy.deepcopy(groups[0]); group[0][key] = "private"
            with self.subTest(key=key), self.assertRaises(calibration.HarnessError):
                calibration.build_material(group, self.corpus, 1)
        group = copy.deepcopy(groups[0]); group[1]["source"] += " Changed."
        with self.assertRaises(calibration.HarnessError):
            calibration.build_material(group, self.corpus, 1)

    def test_frozen_design_rejects_duplicate_ids_wrong_pairs_and_unknown_labels(self):
        mutations = [lambda d: d["cases"][1].update(case_id=d["cases"][0]["case_id"]),
                     lambda d: d["cases"][0].update(expected_relation="pass"),
                     lambda d: d["cases"][1].update(source="Different passage."),
                     lambda d: d["cases"].pop(),
                     lambda d: d["cases"][1].update(expected_relation="supported"),
                     lambda d: d.update(expected_fields_are_private_to_scoring=False)]
        for mutate in mutations:
            value = copy.deepcopy(self.private); mutate(value)
            self.cases.write_text(json.dumps(value))
            with self.assertRaises(calibration.HarnessError):
                calibration.load_cases(self.cases)

    def test_new_out_is_required_and_existing_files_are_not_overwritten(self):
        self.out.mkdir(); sentinel = self.out / "keep"; sentinel.write_text("KEEP")
        with self.assertRaises(FileExistsError):
            self.run_calibration()
        self.assertEqual("KEEP", sentinel.read_text())
        self.assertEqual([sentinel], list(self.out.iterdir()))

    def test_execute_needs_explicit_key_file_before_any_output(self):
        with self.assertRaises(calibration.HarnessError):
            self.run_calibration(execute=True)
        self.assertFalse(self.out.exists())
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            calibration.main(["--cases", str(self.cases), "--corpus", str(self.corpus), "--out", str(self.out), "--execute"])

    def test_cli_default_ignores_key_file_and_prints_no_material_or_label(self):
        stream = io.StringIO()
        with patch.object(calibration, "read_credential") as key, redirect_stdout(stream):
            status = calibration.main(["--cases", str(self.cases), "--corpus", str(self.corpus), "--out", str(self.out),
                                       "--key-file", str(self.root / "nonexistent-secret")])
        self.assertEqual(0, status); key.assert_not_called()
        self.assertEqual("dry_run", json.loads(stream.getvalue())["status"])
        self.assertNotIn("SCORING_ONLY_SENTINEL", stream.getvalue())
        self.assertNotIn(self.private["cases"][0]["source"], stream.getvalue())

    def _mock_runtime(self, behavior="labels", failure_at=1):
        """Patch runtime identity checks explicitly; tests measure control flow only."""
        stack = ExitStack(); self.addCleanup(stack.close)
        stack.enter_context(patch.object(calibration, "read_credential", return_value="test-private-credential"))
        route = stack.enter_context(patch.object(calibration, "ModelRoute"))
        verify_runtime = stack.enter_context(patch.object(calibration, "_verify_runtime_binding"))
        verify_models = stack.enter_context(patch.object(calibration, "_verify_model_bindings"))
        labels = {case["case_id"]: case["expected_relation"] for case in self.private["cases"]}
        prepared_calls = []

        def prepare(directory, request, corpus, route, **kwargs):
            prepared_calls.append((directory, request, kwargs))
            return {"request": request, "corpus": corpus, **kwargs}

        prepare_mock = stack.enter_context(patch.object(calibration, "prepare_stage", side_effect=prepare))

        def native(prepared, prompt, **kwargs):
            budget = kwargs["model_budget"]
            ordinal = budget.reserve(); budget.started(ordinal, False)
            nth = len(prepared_calls)
            receipt = {"status": "completed", "process_reaped": True, "stage": "explanation_review",
                       "runtime_version": calibration.RUNTIME_VERSION, "model": "deepseek-v4-pro",
                       "reasoning_effort": "high", "session_id": "session-mock-{}".format(nth),
                       "corpus_sha256": calibration.sha256_file(prepared["corpus"])}
            if behavior == "raise" and nth == failure_at:
                raise RuntimeError("test-private-credential must never be printed")
            if behavior == "unknown" and nth == failure_at:
                budget.completion_unknown(ordinal, 200, .1, False, "synthetic_unknown")
                receipt["status"] = "failed"
                return None, receipt
            budget.record(ordinal, 200, .1, 100, False)
            if behavior == "failure" and nth == failure_at:
                receipt["status"] = "failed"
                return None, receipt
            result = {"schema": "lexicon.ielts.explanation-review.v2", "review_id": prepared["review_id"],
                "request_sha256": calibration.contract.canonical_sha256(prepared["request"]),
                "package_sha256": calibration.contract.canonical_sha256(prepared["package"]),
                "blind_review_sha256": calibration.contract.canonical_sha256(prepared["blind_review"]),
                "explanation_reviews": []}
            import ielts_explanation_review as api
            for question in prepared["package"]["reading_questions"]:
                relation = "supported" if behavior == "accept_all" else labels[question["question_id"]]
                result["explanation_reviews"].append({"question_id": question["question_id"],
                    "explanation_sha256": api.explanation_sha256(question["explanation"]),
                    "source_refs": copy.deepcopy(question["source_refs"]),
                    "unit_reviews": [{"unit_id": unit["unit_id"], "scope": "source_fact", "relation": relation,
                        "interpretations": ["Synthetic reading one.", "Synthetic reading two."] if relation == "ambiguous" else ["Synthetic reading."],
                        "reason": "Synthetic contract test; not a semantic model judgement.", "source_ref_indices": [0]}
                        for unit in api.explanation_units(question["explanation"])]})
            return result, receipt

        native_mock = stack.enter_context(patch.object(calibration, "run_native_stage", side_effect=native))
        return route, prepare_mock, native_mock, verify_runtime, verify_models

    def test_completed_semantic_rejections_continue_all_five_groups_on_one_budget(self):
        route, prepare, native, verify_runtime, verify_models = self._mock_runtime()
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("completed", summary["status"])
        self.assertEqual(5, native.call_count)
        self.assertEqual(5, verify_runtime.call_count)
        self.assertEqual(5, verify_models.call_count)
        budgets = [call.kwargs["model_budget"] for call in native.call_args_list]
        self.assertEqual(1, len({id(b) for b in budgets}))
        self.assertEqual(16, budgets[0].max_calls)
        self.assertEqual([1, 2, 3, 4, 5], [call.kwargs["first_request_ordinal"] for call in native.call_args_list])
        self.assertTrue(all(call.kwargs["timeout"] == 240 for call in native.call_args_list))
        self.assertTrue(all(call.kwargs["reasoning_effort"] == "high" for call in prepare.call_args_list))
        self.assertTrue(all(call.kwargs["token_limit"] == 16384 and call.kwargs["provider_deadline_seconds"] == 180
                            for call in route.call_args_list))
        self.assertEqual(8, summary["paired_cases"]["exact_relation"])
        self.assertEqual(1, summary["clarity_cases"]["exact_relation"])
        self.assertTrue(summary["all_expected_relations_matched"])
        self.assertFalse(summary["publication_eligible"])
        self.assertEqual([], summary["unstarted_groups"])
        self.assertEqual(5, len(list((self.out / "materials").glob("*/audit.json"))))

    def test_false_accepts_and_clarity_remain_separate_from_exact_relation(self):
        self._mock_runtime("accept_all")
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("completed", summary["status"])
        self.assertEqual(4, summary["paired_cases"]["false_accept"])
        self.assertEqual(4, summary["paired_cases"]["exact_relation"])
        self.assertEqual(1, summary["clarity_cases"]["false_accept"])
        self.assertEqual(0, summary["by_expected_relation"]["ambiguous"]["case_count"])
        self.assertFalse(summary["all_expected_relations_matched"])

    def test_unknown_provider_completion_stops_before_next_stage(self):
        _, prepare, native, _, _ = self._mock_runtime("unknown", 2)
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("stopped", summary["status"])
        self.assertEqual("provider_completion_unknown", summary["error"])
        self.assertEqual(2, native.call_count); self.assertEqual(2, prepare.call_count)
        self.assertEqual([3, 4, 5], summary["unstarted_groups"])
        self.assertEqual(1, summary["provider_completion_unconfirmed"])
        self.assertEqual(2, summary["paired_cases"]["case_count"])

    def test_runtime_binding_failure_is_unconfirmed_and_does_not_start_another_group(self):
        _, prepare, native, verify_runtime, _ = self._mock_runtime()
        verify_runtime.side_effect = calibration.HarnessError("runtime_binding_mismatch")
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("stopped", summary["status"])
        self.assertTrue(summary["runtime_provenance_unconfirmed"])
        self.assertEqual("runtime_binding_mismatch", summary["error"])
        self.assertEqual(1, native.call_count)
        self.assertEqual(1, prepare.call_count)
        self.assertEqual([], summary["results"])

    def test_changed_source_also_stops_dry_run(self):
        original = calibration.sha256_file
        reads = []
        def changed(path):
            if Path(path).name == "calibrate_ielts_explanations.py":
                reads.append(path)
                if len(reads) > 1:
                    return "0" * 64
            return original(path)
        with patch.object(calibration, "sha256_file", side_effect=changed):
            summary = self.run_calibration()
        self.assertEqual("stopped", summary["status"])
        self.assertEqual("calibration_inputs_changed", summary["error"])
        self.assertEqual(0, summary["real_provider_requests"])

    def test_completed_http_but_failed_runtime_also_stops(self):
        _, _, native, _, _ = self._mock_runtime("failure")
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("calibration_runtime_failed", summary["error"])
        self.assertEqual(1, native.call_count)
        self.assertEqual([2, 3, 4, 5], summary["unstarted_groups"])
        self.assertEqual(0, summary["provider_completion_unconfirmed"])
        self.assertFalse(summary["all_expected_relations_matched"])

    def test_exception_without_receipt_preserves_unknown_attempt_and_hides_message(self):
        _, _, native, _, _ = self._mock_runtime("raise")
        summary = self.run_calibration(execute=True, key_file=self.root / "key")
        self.assertEqual("stopped", summary["status"])
        self.assertTrue(summary["runtime_provenance_unconfirmed"])
        self.assertEqual([1], summary["attempted_groups"])
        self.assertEqual([2, 3, 4, 5], summary["unstarted_groups"])
        self.assertEqual(1, native.call_count)
        self.assertNotIn("test-private-credential", json.dumps(summary))
        self.assertTrue((self.out / "model-requests.json").is_file())

    def test_scoring_distinguishes_false_reject_from_wrong_negative_relation(self):
        cases = self.private["cases"][:2]
        audit = {"explanation_results": [
            {"question_id": cases[0]["case_id"], "status": "fail", "unit_results": [{"relation": "unsupported", "scope": "source_fact"}]},
            {"question_id": cases[1]["case_id"], "status": "fail", "unit_results": [{"relation": "contradicted", "scope": "source_fact"}]}]}
        results = calibration.score_audit(cases, audit)
        self.assertTrue(results[0]["false_reject"])
        self.assertFalse(results[1]["exact_relation"])
        self.assertTrue(results[1]["publication_decision_correct"])
        self.assertFalse(results[1]["false_accept"])


if __name__ == "__main__":
    unittest.main()
