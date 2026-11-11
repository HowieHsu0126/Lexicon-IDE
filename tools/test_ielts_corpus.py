"""Offline retrieval/integrity tests; no model, Android or official question generation."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("ielts_corpus", Path(__file__).with_name("ielts_corpus.py"))
corpus_api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus_api)
contract_spec = importlib.util.spec_from_file_location("corpus_contract_test", Path(__file__).with_name("test_ielts_contract.py"))
contract_tests = importlib.util.module_from_spec(contract_spec)
contract_spec.loader.exec_module(contract_tests)


class IeltsCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = TemporaryDirectory()
        cls.path = Path(cls.temporary.name) / "corpus.json"
        cls.receipt = corpus_api.build(cls.path)
        cls.corpus = corpus_api.load(cls.path, expected_sha256=cls.receipt["corpus_sha256"])
        cls.raw = cls.path.read_bytes()
        cls.index = json.loads(cls.raw)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def rejected(self, call, code=None):
        with self.assertRaises(corpus_api.CorpusError) as caught:
            call()
        if code is not None:
            self.assertEqual(code, caught.exception.code)
        return caught.exception

    def changed_artifact(self, mutate):
        value = copy.deepcopy(self.index)
        mutate(value)
        raw = json.dumps(value, ensure_ascii=False, sort_keys=True).encode("utf-8")
        with TemporaryDirectory() as directory:
            path = Path(directory) / "altered.json"
            path.write_bytes(raw)
            return self.rejected(lambda: corpus_api.load(path, expected_sha256=hashlib.sha256(raw).hexdigest()))

    def copy_research(self, target):
        for key in corpus_api._INPUTS:
            shutil.copyfile(corpus_api.DEFAULT_RESEARCH_DIR / (key + ".json"), target / (key + ".json"))
        shutil.copyfile(corpus_api.DEFAULT_RESEARCH_DIR / "final-manifest.json", target / "final-manifest.json")

    def test_build_verifies_actual_frozen_files_and_returns_17_documents(self):
        self.assertEqual("ready", self.receipt["status"])
        self.assertEqual(17, self.receipt["document_count"])
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), self.receipt["corpus_sha256"])
        for item in self.index["input_files"]:
            actual = (corpus_api.DEFAULT_RESEARCH_DIR / (item["input_id"] + ".json")).read_bytes()
            self.assertEqual(item["sha256"], hashlib.sha256(actual).hexdigest())
            self.assertEqual(item["bytes"], len(actual))

    def test_repeated_build_is_byte_identical_and_does_not_overwrite(self):
        before = self.path.stat().st_mtime_ns
        receipt = corpus_api.build(self.path)
        self.assertFalse(receipt["created"])
        self.assertEqual(before, self.path.stat().st_mtime_ns)
        with TemporaryDirectory() as directory:
            path = Path(directory) / "second.json"
            second = corpus_api.build(path)
            self.assertEqual(self.raw, path.read_bytes())
            self.assertEqual(self.receipt["corpus_sha256"], second["corpus_sha256"])

    def test_different_existing_output_is_preserved(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "occupied.json"
            path.write_bytes(b"private-data-placeholder")
            self.rejected(lambda: corpus_api.build(path), "output_exists")
            self.assertEqual(b"private-data-placeholder", path.read_bytes())

    def test_symlink_output_is_not_followed(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "alias.json"
            path.symlink_to(self.path)
            self.rejected(lambda: corpus_api.build(path), "output_exists")
            self.assertEqual(self.raw, self.path.read_bytes())

    def test_modified_or_missing_source_prevents_build(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_research(root)
            feature = root / "sample-style-features.json"
            original = feature.read_bytes()
            feature.write_bytes(original + b" ")
            self.rejected(lambda: corpus_api.build(root / "output.json", research_dir=root), "input_digest")
            feature.unlink()
            self.rejected(lambda: corpus_api.build(root / "output.json", research_dir=root), "file_unavailable")
            self.assertFalse((root / "output.json").exists())

    def test_modified_manifest_cannot_authorize_unverified_sources(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_research(root)
            manifest = root / "final-manifest.json"
            value = json.loads(manifest.read_text())
            value["source_receipts_and_documents"].append(value["source_receipts_and_documents"][0])
            manifest.write_text(json.dumps(value))
            self.rejected(lambda: corpus_api.build(root / "output.json", research_dir=root), "manifest_digest")

    def test_extra_files_are_never_indexed_or_opened(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_research(root)
            (root / "unverified-2026-exam.json").write_text('{"question":"UNVERIFIED_PRIVATE_QUESTION"}')
            (root / "API.txt").write_text("DO_NOT_OPEN_SECRET_PLACEHOLDER")
            opened = []
            original_read = corpus_api._read_bytes
            def record(path, maximum=corpus_api.MAX_INDEX_BYTES):
                opened.append(path.name)
                return original_read(path, maximum)
            with patch.object(corpus_api, "_read_bytes", side_effect=record):
                corpus_api.build(root / "output.json", research_dir=root)
            self.assertEqual(set(opened), {key + ".json" for key in corpus_api._INPUTS} | {"final-manifest.json"})
            self.assertEqual(self.raw, (root / "output.json").read_bytes())

    def test_raw_questions_essays_excerpts_and_unimplemented_specs_are_not_copied(self):
        text = self.raw.decode("utf-8")
        for source in corpus_api._SOURCES:
            data = json.loads((corpus_api.DEFAULT_RESEARCH_DIR / (source + ".json")).read_text())
            if data.get("excerpt"):
                self.assertNotIn(data["excerpt"], text)
            for item in data.get("parsed_samples", []):
                self.assertNotIn(item["excerpt"], text)
        for key in ("harness_handoff", "proposed_validator_contract", "candidate_links", "task_1_data_matches_rendered_visual", "request_and_material_hash_match"):
            self.assertNotIn(key, text)

    def test_every_rule_document_matches_blueprint_and_sample_member_digest(self):
        b = json.loads((corpus_api.DEFAULT_RESEARCH_DIR / "style-blueprint.json").read_text())
        f = json.loads((corpus_api.DEFAULT_RESEARCH_DIR / "sample-style-features.json").read_text())
        rules = {item["id"]: item for item in b["reading"]["types"] + b["writing"]["tasks"]}
        samples = {item["name"]: item for item in f["features"]}
        for d in self.index["documents"]:
            self.assertEqual(corpus_api.BLUEPRINT_SHA256, d["source_chain"]["blueprint_sha256"])
            identifier = d["document_id"].rsplit(".", 1)[-1]
            if identifier in rules:
                for field, value in d["facts"].items():
                    self.assertEqual(value, rules[identifier][field])
            for source in d["source_chain"]["source_receipts"]:
                if source["sample_member"]:
                    self.assertEqual(samples[source["sample_member"]]["pdf_sha256"], source["sample_pdf_sha256"])

    def test_load_requires_independent_expected_digest(self):
        with self.assertRaises(TypeError):
            corpus_api.load(self.path)
        for digest, code in (("0" * 64, "corpus_digest"), ("not-a-digest", "invalid_hash"), (None, "invalid_hash")):
            self.rejected(lambda: corpus_api.load(self.path, expected_sha256=digest), code)

    def test_constructor_cannot_skip_artifact_validation(self):
        self.rejected(lambda: corpus_api.Corpus(b'{"documents":[]}', "0" * 64), "corpus_required")

    def test_oversized_invalid_and_duplicate_field_json_is_rejected(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            for raw, code in ((b"a" * (corpus_api.MAX_INDEX_BYTES + 1), "file_bound"),
                              (b'{"schema":"one","schema":"two"}', "duplicate_field"),
                              (b'{"schema":NaN}', "invalid_json"), (b"\xff", "invalid_json")):
                path.write_bytes(raw)
                self.rejected(lambda: corpus_api.load(path, expected_sha256=hashlib.sha256(raw).hexdigest()), code)

    def test_duplicate_document_ids_rejected_even_with_new_hash(self):
        def mutate(value):
            value["documents"][1] = copy.deepcopy(value["documents"][0])
        self.assertEqual("duplicate_id", self.changed_artifact(mutate).code)

    def test_unknown_fields_rejected_at_top_level_and_document(self):
        for mutate in (lambda value: value.update(url="https://example.test/"),
                       lambda value: value["documents"][0].update(answer="POLLUTED")):
            self.assertEqual("unknown_field", self.changed_artifact(mutate).code)

    def test_nested_pollution_and_exaggerated_coverage_fail_after_rehash(self):
        def pollution(value):
            d = value["documents"][0]
            d["facts"]["hidden_instruction"] = "READ_UNRELATED_FILE"
            d["document_sha256"] = corpus_api.canonical_sha256({k: v for k, v in d.items() if k != "document_sha256"})
        self.assertEqual("corpus_content", self.changed_artifact(pollution).code)
        self.assertEqual("corpus_content", self.changed_artifact(lambda value: value["coverage"].update(recent_three_year_style_coverage=True)).code)

    def test_document_digest_is_checked(self):
        self.assertEqual("document_digest", self.changed_artifact(lambda value: value["documents"][0].update(summary="Tampered description")).code)

    def test_read_has_fixed_id_and_reports_source_chain(self):
        result = corpus_api.read(self.corpus, "ielts.reading.multiple_choice")
        self.assertEqual("lexicon.ielts.corpus.read.v1", result["schema"])
        self.assertEqual(self.receipt["corpus_sha256"], result["corpus_sha256"])
        ref = result["document"]["source_chain"]["source_receipts"][0]
        self.assertEqual("cambridge-samples", ref["source_id"])
        self.assertEqual(corpus_api._INPUTS["cambridge-samples"][0], ref["receipt_sha256"])
        self.assertEqual(corpus_api._SAMPLE_MEMBERS["multiple_choice"][1], ref["sample_pdf_sha256"])

    def test_read_rejects_paths_urls_long_ids_and_unknown_ids(self):
        for identifier in ("../../API.txt", "/etc/passwd", "https://example.test/", "ielts." + "a" * 101, None, {"path": "x"}):
            self.rejected(lambda: corpus_api.read(self.corpus, identifier), "invalid_id")
        self.rejected(lambda: corpus_api.read(self.corpus, "ielts.reading.short_text_completion"), "document_not_found")

    def test_search_filters_module_type_and_enforces_limit(self):
        all_reading = corpus_api.search(self.corpus, module="reading", limit=3)
        self.assertEqual(8, all_reading["matched_count"])
        self.assertEqual(3, all_reading["returned_count"])
        self.assertTrue(all_reading["truncated"])
        result = corpus_api.search(self.corpus, module="writing", question_type="extent_of_agreement")
        self.assertEqual(["ielts.writing.academic_task_2"], [item["document_id"] for item in result["results"]])
        wrong_module = corpus_api.search(self.corpus, module="reading", question_type="extent_of_agreement")
        self.assertEqual([], wrong_module["results"])
        self.assertFalse(wrong_module["truncated"])

    def test_search_is_deterministic_for_english_and_chinese_terms(self):
        for query, expected in (("matching headings", "ielts.reading.matching_headings"),
                                ("同意程度", "ielts.writing.academic_task_2"),
                                ("表格填空", "ielts.reading.table_completion"),
                                ("ＴＲＵＥ　ＦＡＬＳＥ", "ielts.reading.identifying_information")):
            first = corpus_api.search(self.corpus, query)
            self.assertEqual(expected, first["results"][0]["document_id"])
            self.assertEqual(first, corpus_api.search(self.corpus, query))

    def test_search_unknown_terms_returns_empty_not_fabricated_reference(self):
        result = corpus_api.search(self.corpus, "UNOBSERVEDQUOKKARULE")
        self.assertEqual(0, result["matched_count"])
        self.assertEqual([], result["results"])

    def test_search_rejects_unobserved_types_unknown_modules_and_invalid_limits(self):
        for value in (True, False, 0, -1, 9, 1.5, "4"):
            self.rejected(lambda: corpus_api.search(self.corpus, limit=value), "limit_bound")
        self.rejected(lambda: corpus_api.search(self.corpus, module="listening"), "unsupported_module")
        self.rejected(lambda: corpus_api.search(self.corpus, question_type="short_text_completion"), "unsupported_type")
        self.rejected(lambda: corpus_api.search(self.corpus, question_type="discuss_both_views"), "unsupported_type")

    def test_query_bounds_unicode_control_characters_and_target_rejection(self):
        for value, code in (("a" * 241, "query_bound"), ("😀" * 121, "query_bound"),
                            ("\ud800", "invalid_unicode"), ("reading\nwrite", "control_character"),
                            ("?*", "query_terms"), (None, "query_required")):
            self.rejected(lambda: corpus_api.search(self.corpus, value), code)
        for value in ("https://example.test/x", "file:///etc/passwd", "../API.txt", "/etc/passwd", "C:\\secret.txt", "read ~/secret", "ｈｔｔｐｓ：／／example.test"):
            self.rejected(lambda: corpus_api.search(self.corpus, value), "lookup_target")
        self.assertTrue(corpus_api.search(self.corpus, "T/F/NG")["results"])

    def test_every_response_retains_incomplete_coverage_without_inferred_years(self):
        responses = [corpus_api.search(self.corpus), corpus_api.read(self.corpus, "ielts.writing.academic_task_2"),
                     corpus_api.style_source_bundle(self.corpus, ["ielts.writing.academic_task_2"])]
        for r in responses:
            self.assertFalse(r["coverage"]["recent_three_year_style_coverage"])
            self.assertFalse(r["coverage"]["cambridge_21_full_text_read"])
        d = corpus_api.read(self.corpus, "ielts.metadata.cambridge-preparation")["document"]
        self.assertIsNone(d["facts"]["publication_year"])
        self.assertFalse(d["facts"]["publication_year_verified"])

    def test_sample_limit_and_writing_scope_are_not_expanded(self):
        table = corpus_api.read(self.corpus, "ielts.reading.table_completion")["document"]
        self.assertEqual(3, table["facts"]["observed_sample_max_words"])
        self.assertTrue(table["facts"]["sample_limit_is_not_universal"])
        task2 = corpus_api.read(self.corpus, "ielts.writing.academic_task_2")["document"]
        self.assertEqual(["extent_of_agreement"], task2["facts"]["observed_sample_prompt_shapes"])
        task1 = corpus_api.read(self.corpus, "ielts.writing.academic_task_1")["document"]
        self.assertNotIn("visual", task1["facts"])

    def test_mutating_results_does_not_change_loaded_index(self):
        result = corpus_api.read(self.corpus, "ielts.reading.multiple_choice")
        result["document"]["summary"] = "MODIFIED"
        result["document"]["source_chain"]["source_receipts"].clear()
        self.assertNotEqual("MODIFIED", corpus_api.read(self.corpus, "ielts.reading.multiple_choice")["document"]["summary"])
        with self.assertRaises(AttributeError):
            self.corpus._raw = b"changed"

    def test_retrieval_after_load_performs_no_filesystem_reads_or_writes(self):
        with patch("builtins.open", side_effect=AssertionError("No file I/O during retrieval")), \
             patch.object(Path, "open", side_effect=AssertionError("No file I/O during retrieval")), \
             patch.object(corpus_api.os, "open", side_effect=AssertionError("No file I/O during retrieval")):
            result = corpus_api.handle_tool_request(self.corpus, {"tool": "retrieve", "arguments": {"question_type": "multiple_choice"}})
            doc = corpus_api.handle_tool_request(self.corpus, {"tool": "read", "arguments": {"document_id": result["results"][0]["document_id"]}})
            self.assertEqual("ielts.reading.multiple_choice", doc["document"]["document_id"])

    def test_tool_protocol_only_accepts_declared_arguments(self):
        self.assertEqual("lexicon.ielts.corpus.search.v1", corpus_api.handle_tool_request(self.corpus, {"tool": "retrieve", "arguments": {"module": "reading", "limit": 2}})["schema"])
        self.assertEqual("lexicon.ielts.corpus.read.v1", corpus_api.handle_tool_request(self.corpus, {"tool": "read", "arguments": {"document_id": "ielts.coverage"}})["schema"])
        for request in ({"tool": "retrieve", "arguments": {"url": "https://example.test/"}},
                        {"tool": "read", "arguments": {"document_id": "ielts.coverage", "path": "API.txt"}},
                        {"tool": "retrieve", "arguments": {}, "corpus": "/tmp/corpus"},
                        {"tool": "shell", "arguments": {"command": "anything"}},
                        {"tool": "build", "arguments": {"output": "anything"}}):
            self.rejected(lambda: corpus_api.handle_tool_request(self.corpus, request))

    def test_tool_and_read_errors_do_not_echo_sensitive_targets(self):
        marker = "SENSITIVE_PLACEHOLDER_TOKEN"
        error = self.rejected(lambda: corpus_api.read(self.corpus, "/private/" + marker))
        self.assertNotIn(marker, str(error))
        self.rejected(lambda: corpus_api.handle_tool_request(self.corpus, {"tool": "retrieve", "arguments": {"query": "a" * 5000}}), "request_bound")

    def test_style_source_bundle_satisfies_actual_request_contract(self):
        ids = ["ielts.reading.multiple_choice", "ielts.reading.table_completion", "ielts.writing.academic_task_2"]
        bundle = corpus_api.style_source_bundle(self.corpus, ids)
        request, _, _ = contract_tests.fixture()
        request.update(style_blueprint_sha256=bundle["style_blueprint_sha256"], style_sources=bundle["style_sources"])
        result = contract_tests.contract.validate_request(request)
        self.assertEqual("structurally_valid", result["status"])
        for source in bundle["style_sources"]:
            doc = corpus_api.read(self.corpus, source["source_id"])["document"]
            self.assertEqual(doc["document_sha256"], source["sha256"])
            self.assertEqual(doc["summary"], source["summary"])

    def test_style_mapping_rejects_metadata_duplicates_and_excess(self):
        for identifiers, code in (([], "source_bound"), (["ielts.coverage"], "source_scope"),
                                  (["ielts.metadata.cambridge-samples"], "source_scope"),
                                  (["ielts.reading.multiple_choice"] * 2, "duplicate_id"),
                                  (["ielts.reading.multiple_choice"] * 13, "source_bound")):
            self.rejected(lambda: corpus_api.style_sources(self.corpus, identifiers), code)

    def test_cli_build_search_read_and_style_bundle(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "cli.json"
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(0, corpus_api.main(["build", "--output", str(path)]))
            receipt = json.loads(output.getvalue())
            for command, tail in (("search", ["--module", "reading", "--limit", "2"]),
                                  ("read", ["--document-id", "ielts.reading.multiple_choice"]),
                                  ("style-sources", ["--document-id", "ielts.reading.multiple_choice"])):
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(0, corpus_api.main([command, "--corpus", str(path), "--sha256", receipt["corpus_sha256"]] + tail))
                self.assertEqual(receipt["corpus_sha256"], json.loads(output.getvalue())["corpus_sha256"])

    def test_cli_tool_stdin_and_unknown_fields_fail_closed(self):
        command = [sys.executable, str(Path(corpus_api.__file__)), "tool", "--corpus", str(self.path), "--sha256", self.receipt["corpus_sha256"]]
        good = subprocess.run(command, input=json.dumps({"tool": "read", "arguments": {"document_id": "ielts.coverage"}}), text=True, capture_output=True, check=False)
        self.assertEqual(0, good.returncode, good.stderr)
        self.assertEqual("ielts.coverage", json.loads(good.stdout)["document"]["document_id"])
        bad = subprocess.run(command, input='{"tool":"read","tool":"retrieve","arguments":{}}', text=True, capture_output=True, check=False)
        self.assertEqual(2, bad.returncode)
        self.assertEqual("duplicate_field", json.loads(bad.stderr)["error"]["code"])
        self.assertEqual("", bad.stdout)

    def test_cli_error_is_structured_and_does_not_leak_path(self):
        output, error = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(error):
            result = corpus_api.main(["read", "--corpus", "/missing/private/SENSITIVE", "--sha256", "0" * 64, "--document-id", "ielts.coverage"])
        self.assertEqual(2, result)
        self.assertEqual("", output.getvalue())
        self.assertEqual("file_unavailable", json.loads(error.getvalue())["error"]["code"])
        self.assertNotIn("SENSITIVE", error.getvalue())


if __name__ == "__main__":
    unittest.main()
