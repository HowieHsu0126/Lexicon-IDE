"""Protocol tests only; synthetic timing values are never device performance evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("collector1200", Path(__file__).with_name("collect_mdx_cold_start.py"))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


def canonical_string(value):
    raw = value.encode()
    return struct.pack(">I", len(raw)) + raw


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        source = (c.WORKSPACE / c.SOURCE_PATHS[0]).read_text()
        block = source.split("val queries = listOf(", 1)[1].split("\n        )", 1)[0]
        self.queries = re.findall(r'"([^\"]*)"', block)
        assert len(self.queries) == 128
        self.apk = c.digest(b"synthetic apk identity")
        for phase in ("before", "after"):
            frozen = self.root / phase / "frozen"
            frozen.mkdir(parents=True)
            records = []
            for kind, name, data in (("source", "sources/MdxColdStartBenchmarkTest.kt", b"synthetic test identity"),
                                     ("apk", "apk.bin", b"synthetic apk identity")):
                target = frozen / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                records.append({"kind": kind, "archive": name, "sha256": c.digest(data), "bytes": len(data)})
            manifest = {"schema": c.MANIFEST_SCHEMA, "protocol": c.PROTOCOL, "phase": phase, "files": records}
            raw = c.dump(manifest)
            (frozen / "source-manifest.json").write_bytes(raw)
            for rep in range(1, 4):
                self.write(phase, rep, self.report(phase, rep, c.digest(raw)))

    def tearDown(self):
        self.temporary.cleanup()

    def stages(self, constructor=False):
        return {name: {"calls": int(name in c.CONSTRUCTOR if constructor else name == "LOOKUP_INDEX"),
                       "elapsed_ns": 10 if constructor and name in c.CONSTRUCTOR else 3 if not constructor and name == "LOOKUP_INDEX" else 0}
                for name in c.CONSTRUCTOR + c.LOOKUP}

    def report(self, phase, rep, manifest):
        hashes = {label: c.digest(label.encode()) for label in
                  ("keys_sha256", "end_offsets_sha256", "normalized_index_sha256", "record_blocks_sha256")}
        full = canonical_string("mdx-whole-index-v1") + b"".join(canonical_string(k) + canonical_string(v) for k, v in hashes.items())
        whole = {"schema": "mdx-whole-index-v1", **hashes, "full_structure_sha256": c.digest(full),
                 "key_count": 37375, "index_reference_count": 37375, "observed_after_all_timing": True,
                 "key_offsets_nondecreasing": False, "unique_normalized_keys": 35000, "record_block_count": 500}
        result = {"match_count": 0, "definition_characters": 0, "ordered_result_sha256": c.digest(b""), "entries": []}
        samples = [{"pass": name, "query_index": i, "query": query, "complete": True,
                    "elapsed_ns": 4, "stages": self.stages(), "result": copy.deepcopy(result)}
                   for name in ("first_128", "second_128_same_order") for i, query in enumerate(self.queries)]
        return {"schema": "lexicon.mdx.cold-start.v1", "protocol": c.PROTOCOL, "phase": phase,
                "repetition": str(rep), "status": "complete", "source_manifest_sha256": manifest,
                "target_apk": {"sha256": self.apk}, "instrumentation_apk": {"sha256": self.apk},
                "dictionary": {"sha256": c.CORPUS_SHA, "bytes": 15895150}, "whole_index": whole,
                "query_order": self.queries, "query_list_sha256": c.QUERY_SHA,
                "filesystem_cache_flushed": False, "forced_gc": False, "test_invokes_network": False,
                "touches_application_database": False, "reader_released": True, "owned_directory_removed": True,
                "constructor_stages": self.stages(True), "constructor_elapsed_ns": 80,
                "constructor_unattributed_ns": 20, "query_samples": samples,
                "device": {"fingerprint": "protocol fixture, not a device", "sdk": 36},
                "pid": rep + (0 if phase == "before" else 10), "process_start_elapsed_ms": rep * 10,
                "process_age_at_test_start_ms": 15}

    def write(self, phase, rep, value):
        path = self.root / phase / f"rep-{rep}.json"
        path.write_bytes(c.dump(value))
        self.log(phase, rep).write_text(
            f"INSTRUMENTATION_STATUS: class={c.TEST_CLASS}\n"
            f"INSTRUMENTATION_STATUS: test={c.TEST_METHOD}\n"
            "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: class={c.TEST_CLASS}\n"
            f"INSTRUMENTATION_STATUS: test={c.TEST_METHOD}\n"
            "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\n"
            "OK (1 test)\nINSTRUMENTATION_CODE: -1\n")

    def log(self, phase="after", rep=3):
        return self.root / phase / f"rep-{rep}-instrumentation.txt"

    def mutate(self, action, phase="after", rep=3):
        path = self.root / phase / f"rep-{rep}.json"
        value = json.loads(path.read_text())
        action(value)
        self.write(phase, rep, value)

    def collect(self):
        return c.collect(self.root, "before", "after")

    def test_complete_three_plus_three(self):
        report = self.collect()
        self.assertTrue(report["comparison"]["eligible"])
        self.assertEqual(report["groups"]["before"]["metrics"]["constructor"]["n"], 3)
        self.assertEqual(len(report["attempts"]), 6)

    def test_missing_runner_does_not_supply_coverage(self):
        self.log().unlink()
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertEqual(sum(row["valid"] for row in report["attempts"]), 5)

    def test_failed_terminal_is_retained(self):
        path = self.log()
        path.write_text(path.read_text().replace("STATUS_CODE: 0", "STATUS_CODE: -2"))
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertTrue(any(row.get("runner", {}).get("terminal_codes") == [-2] for row in report["attempts"]))

    def test_same_process_is_not_a_second_independent_run(self):
        self.mutate(lambda v: v.update(pid=12, process_start_elapsed_ms=20))
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertTrue(any("same_process_observed_more_than_once" in row["reasons"] for row in report["attempts"]))

    def test_changed_corpus_is_ineligible(self):
        self.mutate(lambda v: v["dictionary"].update(sha256="0" * 64))
        self.assertFalse(self.collect()["comparison"]["eligible"])

    def test_changed_query_order_is_ineligible(self):
        self.mutate(lambda v: v["query_order"].reverse())
        self.assertFalse(self.collect()["comparison"]["eligible"])

    def test_between_build_result_drift_blocks_comparison(self):
        def change(value):
            for row in value["query_samples"]:
                row["result"]["ordered_result_sha256"] = "a" * 64
        self.mutate(change)
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertIn("fixed_query_result_signatures_differ", report["comparison"]["reasons"])

    def test_whole_index_drift_blocks_comparison(self):
        def change(value):
            whole = value["whole_index"]
            whole["keys_sha256"] = "c" * 64
            labels = ("keys_sha256", "end_offsets_sha256", "normalized_index_sha256", "record_blocks_sha256")
            raw = canonical_string("mdx-whole-index-v1") + b"".join(canonical_string(k) + canonical_string(whole[k]) for k in labels)
            whole["full_structure_sha256"] = c.digest(raw)
        self.mutate(change)
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertIn("whole_index_structure_differs", report["comparison"]["reasons"])

    def test_changed_frozen_source_blocks_all_phase_runs(self):
        (self.root / "after/frozen/sources/MdxColdStartBenchmarkTest.kt").write_text("changed")
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertEqual(sum(row["valid"] for row in report["attempts"]), 3)

    def test_bad_apk_binding(self):
        self.mutate(lambda v: v["target_apk"].update(sha256="f" * 64))
        self.assertFalse(self.collect()["comparison"]["eligible"])

    def test_nested_record_time_is_not_added_to_constructor(self):
        report = self.collect()
        self.assertNotIn("constructor.RECORD_DECOMPRESS", report["groups"]["before"]["metrics"])
        self.assertEqual(report["groups"]["before"]["metrics"]["constructor"]["median_ms"], .00008)

    def test_malformed_fields_and_incomplete_json_are_retained(self):
        self.mutate(lambda v: v.update(device=None))
        (self.root / "before/rep-3.json").write_text("{")
        report = self.collect()
        self.assertFalse(report["comparison"]["eligible"])
        self.assertEqual(len(report["attempts"]), 6)
        self.assertEqual(sum(row["valid"] for row in report["attempts"]), 4)


if __name__ == "__main__":
    unittest.main()
