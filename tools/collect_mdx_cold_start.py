#!/usr/bin/env python3
"""Freeze build inputs or collect Reader-only 1.20 measurements. No Gradle/ADB/API.

After building, before instrumenting:
  python3 tools/collect_mdx_cold_start.py --freeze before --apk PATH --build-log PATH
Pass the printed manifest_sha256 to sourceManifestSha256. Copy device output to
  dist/validation-1.20.0/mdx-cold-start/before/rep-1.json
and its runner output to the companion rep-1-instrumentation.txt. Repeat fresh
processes with rep=2/3, then freeze the optimized build as after and run again.

Collect with: python3 tools/collect_mdx_cold_start.py
Raw reports, frozen sources, APKs and logs are immutable. Only this collector's
current-report.json is replaced. Existing failed and incomplete attempts remain.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import struct
import tempfile

WORKSPACE = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = WORKSPACE / "dist/validation-1.20.0/mdx-cold-start"
SCHEMA = "lexicon.mdx.cold-start.summary.v1"
MANIFEST_SCHEMA = "lexicon.mdx.cold-start.sources.v1"
PROTOCOL = "1.20-reader-fresh-process-fixed128-v1"
CORPUS_SHA = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
QUERY_SHA = "c45a27b3cd58eee102151017b9e4c2d810757ddfe3dbc776c3ae2d24cee8156c"
TEST_CLASS = "dev.lexicon.data.MdxColdStartBenchmarkTest"
TEST_METHOD = "fixedDictionaryFreshProcessConstructorAndQueries"
CONSTRUCTOR = ("HEADER", "KEY_METADATA", "KEY_DECODE", "RECORD_METADATA", "END_OFFSETS", "NORMALIZED_INDEX")
LOOKUP = ("LOOKUP_INDEX", "LOOKUP_RESOLVE", "LOOKUP_SANITIZE", "RECORD_DECOMPRESS")
SOURCE_PATHS = (
    "data/src/androidTest/kotlin/dev/lexicon/data/MdxColdStartBenchmarkTest.kt",
    "data/src/main/kotlin/dev/lexicon/data/mdict/MdxReader.kt",
    "data/src/main/kotlin/dev/lexicon/data/mdict/Lzo1x.kt",
    "data/src/main/kotlin/dev/lexicon/data/mdict/KingsoftPhonetics.kt",
    "data/build.gradle.kts", "gradle/libs.versions.toml", "settings.gradle.kts",
    "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def dump(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()


def safe_label(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", value):
        raise ValueError("Invalid label")
    return value


def checked_read(path):
    before = path.stat()
    data = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("Input changed during read")
    return data


def freeze(root, phase, apks, build_log):
    destination = root / safe_label(phase) / "frozen"
    destination.mkdir(parents=True, exist_ok=False)
    records = []
    sources = list(SOURCE_PATHS)
    # The observer may live beside MdxReader or in the same file.
    for observed_source in (
        "data/src/main/kotlin/dev/lexicon/data/mdict/MdxReadObserver.kt",
        "data/src/main/kotlin/dev/lexicon/data/mdict/MdxTextObserver.kt",
        "data/src/androidTest/kotlin/dev/lexicon/data/MdxTextProfileTest.kt",
    ):
        if (WORKSPACE / observed_source).is_file():
            sources.append(observed_source)
    paths = [("source", WORKSPACE / path, Path("sources") / path) for path in sources]
    paths += [("apk", path.resolve(), Path("apks") / f"{index}-{path.name}") for index, path in enumerate(apks)]
    if build_log:
        paths.append(("build_log", build_log.resolve(), Path("build-log.txt")))
    for kind, source, archived in paths:
        data = checked_read(source)
        target = destination / archived
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as output:
            output.write(data)
        if checked_read(target) != data:
            raise ValueError("Frozen copy differs")
        records.append({"kind": kind, "original_path": str(source), "archive": str(archived),
                        "bytes": len(data), "sha256": digest(data)})
    manifest = {"schema": MANIFEST_SCHEMA, "protocol": PROTOCOL, "phase": phase,
                "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "files": records,
                "binding_scope": "Source and built APK byte snapshots; build log is supplied evidence, not a binary/source equivalence proof"}
    payload = dump(manifest)
    path = destination / "source-manifest.json"
    with path.open("xb") as output:
        output.write(payload)
    return {"manifest": str(path), "manifest_sha256": digest(payload), "file_count": len(records)}


class Inputs:
    def __init__(self):
        self.records = {}

    def read(self, path, kind):
        path = path.resolve()
        row = self.records.setdefault(str(path), {"path": str(path), "kinds": []})
        row["kinds"] = sorted(set(row["kinds"] + [kind]))
        try:
            data = checked_read(path)
            row.update(state="read", bytes=len(data), sha256=digest(data), mtime_ns=path.stat().st_mtime_ns)
            return data
        except (OSError, ValueError) as error:
            row.update(state="unavailable", error_class=type(error).__name__)
            return None

    def load(self, path, kind):
        data = self.read(path, kind)
        if data is not None:
            try:
                value = json.loads(data)
                if not isinstance(value, dict):
                    raise ValueError("Object required")
                return value
            except (ValueError, UnicodeError) as error:
                self.records[str(path.resolve())].update(state="invalid_json", error_class=type(error).__name__)
        return None

    def ref(self, path):
        row = self.records[str(path.resolve())]
        return {key: row[key] for key in ("path", "state", "sha256") if key in row}


def manifests(root, inputs):
    values = {}
    for path in sorted(root.glob("*/frozen/source-manifest.json")):
        value = inputs.load(path, "source_manifest")
        record = {"input": inputs.ref(path), "valid": False, "reasons": []}
        if value is None:
            record["reasons"].append("manifest_unavailable")
        else:
            reasons = record["reasons"]
            if value.get("schema") != MANIFEST_SCHEMA or value.get("protocol") != PROTOCOL:
                reasons.append("manifest_schema_or_protocol")
            rows = value.get("files", [])
            if not isinstance(rows, list):
                rows = []
                reasons.append("invalid_file_inventory")
            if any(not isinstance(row, dict) for row in rows):
                reasons.append("invalid_manifest_file")
                rows = [row for row in rows if isinstance(row, dict)]
            for row in rows:
                try:
                    archive = (path.parent / row["archive"]).resolve()
                    if not archive.is_relative_to(path.parent.resolve()):
                        raise ValueError("Outside frozen directory")
                    data = inputs.read(archive, "frozen_" + row["kind"])
                    if data is None or digest(data) != row["sha256"] or len(data) != row["bytes"]:
                        reasons.append("frozen_file_mismatch:" + str(row.get("archive", "")))
                except (KeyError, TypeError, ValueError):
                    reasons.append("invalid_manifest_file")
            tests = [row.get("sha256") for row in rows if str(row.get("archive", "")).endswith("/MdxColdStartBenchmarkTest.kt")]
            if len(tests) != 1:
                reasons.append("frozen_test_source_missing_or_ambiguous")
            record.update(phase=value.get("phase"), test_source_sha256=tests[0] if len(tests) == 1 else None,
                          apk_sha256=sorted(row.get("sha256") for row in rows if row.get("kind") == "apk"))
            if not record["apk_sha256"]:
                reasons.append("frozen_apk_missing")
            record["valid"] = not reasons
        if record["input"].get("sha256"):
            values[record["input"]["sha256"]] = record
    return values


def runner_status(data):
    if data is None:
        return {"complete": False, "reason": "runner_log_unavailable"}
    text = data.decode(errors="replace")
    block, terminals, starts, final_codes, counts = {}, [], 0, [], []
    abort = False
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            block[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.partition(":")[2])
            except ValueError:
                abort = True
                code = None
            if (block.get("class"), block.get("test")) == (TEST_CLASS, TEST_METHOD):
                if code == 1:
                    starts += 1
                elif code is not None and code <= 0:
                    terminals.append(code)
            if block.get("numtests", "").isdigit():
                counts.append(int(block["numtests"]))
            block = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            final_codes.append(line.partition(":")[2].strip())
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:", "INSTRUMENTATION_RESULT: shortMsg=")):
            abort = True
    complete = (not abort and starts == 1 and terminals == [0] and final_codes == ["-1"] and
                bool(counts) and set(counts) == {1} and re.search(r"\bOK\s*\(1 test\)", text) is not None)
    return {"complete": complete, "starts": starts, "terminal_codes": terminals,
            "runner_final_codes": final_codes, "aborted": abort}


def valid_stage(value):
    return (isinstance(value, dict) and set(value) == set(CONSTRUCTOR + LOOKUP) and
            all(type(value[name].get(key)) is int and value[name][key] >= 0
                for name in CONSTRUCTOR + LOOKUP if isinstance(value[name], dict)
                for key in ("calls", "elapsed_ns")) and all(isinstance(value[name], dict) for name in value))


def valid_result(value):
    if not isinstance(value, dict) or not re.fullmatch("[a-f0-9]{64}", str(value.get("ordered_result_sha256", ""))):
        return False
    entries = value.get("entries")
    if not isinstance(entries, list) or value.get("match_count") != len(entries):
        return False
    for row in entries:
        if not isinstance(row, dict):
            return False
        if not all(isinstance(row.get(key), int) and row[key] >= 0 for key in ("headword_characters", "definition_characters", "definition_utf8_bytes")):
            return False
        if not all(re.fullmatch("[a-f0-9]{64}", str(row.get(key, ""))) for key in ("headword_sha256", "definition_sha256")):
            return False
    return value.get("definition_characters") == sum(row["definition_characters"] for row in entries)


def valid_whole_index(value):
    if not isinstance(value, dict) or value.get("schema") != "mdx-whole-index-v1":
        return False
    labels = ("keys_sha256", "end_offsets_sha256", "normalized_index_sha256", "record_blocks_sha256")
    if not all(re.fullmatch("[a-f0-9]{64}", str(value.get(label, ""))) for label in labels):
        return False
    def string(text):
        raw = text.encode()
        return struct.pack(">I", len(raw)) + raw
    canonical = string("mdx-whole-index-v1") + b"".join(string(label) + string(value[label]) for label in labels)
    return (digest(canonical) == value.get("full_structure_sha256") and value.get("key_count") == 37_375 and
            value.get("index_reference_count") == 37_375 and value.get("observed_after_all_timing") is True and
            isinstance(value.get("key_offsets_nondecreasing"), bool) and
            all(isinstance(value.get(key), int) and value[key] > 0 for key in ("unique_normalized_keys", "record_block_count")))


def inspect_report(path, inputs, source_manifests):
    value = inputs.load(path, "raw_measurement")
    log = path.with_name(path.stem + "-instrumentation.txt")
    run = runner_status(inputs.read(log, "runner_log"))
    record = {"input": inputs.ref(path), "runner_input": inputs.ref(log), "runner": run,
              "valid": False, "reasons": []}
    reasons = record["reasons"]
    if value is None:
        reasons.append("report_unavailable_or_invalid")
        return record, None
    record.update(phase=value.get("phase"), repetition=value.get("repetition"), status=value.get("status"))
    if value.get("phase") != path.parent.name or value.get("repetition") != path.stem.removeprefix("rep-"):
        reasons.append("report_path_labels_differ")
    if value.get("schema") != "lexicon.mdx.cold-start.v1" or value.get("protocol") != PROTOCOL:
        reasons.append("report_schema_or_protocol")
    if value.get("status") != "complete":
        reasons.append("benchmark_not_complete")
    if not run["complete"]:
        reasons.append("runner_completion_not_verified")
    source = source_manifests.get(value.get("source_manifest_sha256"))
    if not source or not source["valid"] or source.get("phase") != value.get("phase"):
        reasons.append("source_manifest_not_verified")
    elif any(value.get(key, {}).get("sha256") not in source["apk_sha256"] for key in ("target_apk", "instrumentation_apk")):
        reasons.append("actual_apk_not_in_frozen_manifest")
    else:
        record["test_source_sha256"] = source["test_source_sha256"]
    dictionary = value.get("dictionary", {})
    if dictionary.get("sha256") != CORPUS_SHA or dictionary.get("bytes") != 15_895_150:
        reasons.append("fixed_dictionary_mismatch")
    if not valid_whole_index(value.get("whole_index")):
        reasons.append("whole_index_identity_missing_or_invalid")
    queries = value.get("query_order")
    if (not isinstance(queries, list) or len(queries) != 128 or not all(isinstance(q, str) for q in queries) or
            len(set(queries)) != 128 or queries[:2] != ["book", "world"] or
            digest(("\n".join(queries) + "\n").encode()) != QUERY_SHA or value.get("query_list_sha256") != QUERY_SHA):
        reasons.append("fixed_queries_mismatch")
        queries = []
    for key, expected in (("filesystem_cache_flushed", False), ("forced_gc", False),
                          ("test_invokes_network", False), ("touches_application_database", False),
                          ("reader_released", True), ("owned_directory_removed", True)):
        if value.get(key) is not expected:
            reasons.append("protocol_flag:" + key)
    stages = value.get("constructor_stages")
    if not valid_stage(stages) or any(stages[name]["calls"] != 1 for name in CONSTRUCTOR) or any(stages[name]["calls"] for name in LOOKUP):
        reasons.append("constructor_stage_coverage")
    elapsed = value.get("constructor_elapsed_ns")
    if type(elapsed) is not int or elapsed <= 0:
        reasons.append("constructor_time_invalid")
    if (type(value.get("constructor_unattributed_ns")) is not int or value["constructor_unattributed_ns"] < 0 or
            (valid_stage(stages) and isinstance(elapsed, int) and
             value["constructor_unattributed_ns"] != elapsed - sum(stages[name]["elapsed_ns"] for name in CONSTRUCTOR))):
        reasons.append("constructor_residual_invalid")
    samples = value.get("query_samples", [])
    if not isinstance(samples, list) or len(samples) != 256:
        reasons.append("query_sample_count")
        samples = []
    if queries and samples:
        for index, row in enumerate(samples):
            if not isinstance(row, dict):
                reasons.append("invalid_query_sample")
                break
            expected_pass = "first_128" if index < 128 else "second_128_same_order"
            if (row.get("pass") != expected_pass or row.get("query_index") != index % 128 or
                    row.get("query") != queries[index % 128] or row.get("complete") is not True or
                    not isinstance(row.get("elapsed_ns"), int) or row["elapsed_ns"] < 0 or
                    not valid_stage(row.get("stages")) or not valid_result(row.get("result"))):
                reasons.append("invalid_query_sample:" + str(index))
                break
            if row["stages"]["LOOKUP_INDEX"]["calls"] != 1 or any(row["stages"][name]["calls"] for name in CONSTRUCTOR):
                reasons.append("query_stage_coverage:" + str(index))
        if all(isinstance(row, dict) for row in samples) and any(samples[i].get("result") != samples[i + 128].get("result") for i in range(128)):
            reasons.append("between_pass_result_mismatch")
    identity = (value.get("device", {}).get("fingerprint"), value.get("pid"), value.get("process_start_elapsed_ms"))
    if not isinstance(identity[0], str) or type(identity[1]) is not int or type(identity[2]) is not int:
        reasons.append("process_identity_missing")
    if not isinstance(value.get("device"), dict):
        reasons.append("device_missing")
    record.update(process_identity=list(identity), process_age_at_test_start_ms=value.get("process_age_at_test_start_ms"))
    record["valid"] = not reasons
    return record, value


def summary(values):
    return {"n": len(values), "raw_ms": values, "median_ms": statistics.median(values),
            "min_ms": min(values), "max_ms": max(values)} if values else {"n": 0, "raw_ms": []}


def metrics(value):
    result = {"constructor": value["constructor_elapsed_ns"] / 1e6}
    result.update({"constructor." + key: value["constructor_stages"][key]["elapsed_ns"] / 1e6 for key in CONSTRUCTOR})
    result["constructor.unattributed"] = value["constructor_unattributed_ns"] / 1e6
    for pass_name in ("first_128", "second_128_same_order"):
        samples = [s for s in value["query_samples"] if s["pass"] == pass_name]
        for name in ("book", "world"):
            row = next(s for s in samples if s["query"] == name)
            result[f"{pass_name}.{name}"] = row["elapsed_ns"] / 1e6
            result.update({f"{pass_name}.{name}.{key}": row["stages"][key]["elapsed_ns"] / 1e6 for key in LOOKUP})
        ordered = sorted(s["elapsed_ns"] / 1e6 for s in samples)
        result[pass_name + ".fixed_words_p50"] = ordered[math.ceil(.50 * len(ordered)) - 1]
        result[pass_name + ".fixed_words_p95"] = ordered[math.ceil(.95 * len(ordered)) - 1]
    return result


def collect(root, before, after):
    inputs = Inputs()
    frozen = manifests(root, inputs)
    attempts, groups, identities = [], {}, set()
    for path in sorted(root.glob("*/rep-*.json")):
        try:
            record, value = inspect_report(path, inputs, frozen)
        except (KeyError, TypeError, ValueError, IndexError, AttributeError) as error:
            # A corrupt or interrupted report is evidence of an incomplete attempt.
            # Keep it visible, without copying exception text or aborting other runs.
            record, value = {"input": inputs.ref(path), "valid": False,
                             "reasons": ["malformed_report_fields"], "error_class": type(error).__name__}, None
        identity = tuple(record.get("process_identity", []))
        if record["valid"] and identity in identities:
            record["valid"] = False
            record["reasons"].append("same_process_observed_more_than_once")
        elif record["valid"]:
            identities.add(identity)
        attempts.append(record)
        if record["valid"]:
            groups.setdefault(record["phase"], []).append((record, value))
    summaries = {}
    for phase, rows in groups.items():
        observations = [metrics(value) for _, value in rows]
        summaries[phase] = {"complete_fresh_process_runs": len(rows),
                            "metrics": {key: summary([row[key] for row in observations]) for key in observations[0]}}
    reasons = []
    for phase in (before, after):
        if len(groups.get(phase, [])) < 3:
            reasons.append(phase + ":fewer_than_three_verified_fresh_process_runs")
    selected = groups.get(before, []) + groups.get(after, [])
    if selected:
        first_record, first_value = selected[0]
        result_identity = [row["result"] for row in first_value["query_samples"][:128]]
        for record, value in selected:
            if value["device"] != first_value["device"]:
                reasons.append("device_or_ABI_differs")
            if record["test_source_sha256"] != first_record["test_source_sha256"]:
                reasons.append("test_source_differs")
            if [row["result"] for row in value["query_samples"][:128]] != result_identity:
                reasons.append("fixed_query_result_signatures_differ")
            if value["whole_index"] != first_value["whole_index"]:
                reasons.append("whole_index_structure_differs")
        for phase in (before, after):
            if len({value["source_manifest_sha256"] for _, value in groups.get(phase, [])}) > 1:
                reasons.append(phase + ":multiple_builds_in_one_group")
    comparison = {"eligible": not reasons, "reasons": sorted(set(reasons)), "before": before, "after": after,
                  "scope": "Observed medians of this small fresh-process sample on one device; no population P95, statistical-significance or MagicOS claim"}
    if selected:
        comparison["whole_index_identity"] = selected[0][1]["whole_index"]
    if not reasons:
        a, b = summaries[before]["metrics"], summaries[after]["metrics"]
        comparison["median_changes"] = {key: {"before_ms": a[key]["median_ms"], "after_ms": b[key]["median_ms"],
                                               "change_ms": b[key]["median_ms"] - a[key]["median_ms"],
                                               "relative_change_percent": (b[key]["median_ms"] / a[key]["median_ms"] - 1) * 100 if a[key]["median_ms"] else None}
                                         for key in a}
    return {"schema": SCHEMA, "protocol": PROTOCOL, "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "source_manifests": list(frozen.values()), "attempts": attempts, "groups": summaries,
            "comparison": comparison, "inputs": sorted(inputs.records.values(), key=lambda r: r["path"]),
            "record_decompress_is_inclusive_child_of_lookup_resolve": True,
            "failed_or_incomplete_attempts_retained": True, "no_dictionary_definition_text_in_summary": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--freeze", type=safe_label)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--build-log", type=Path)
    parser.add_argument("--before", type=safe_label, default="before")
    parser.add_argument("--after", type=safe_label, default="after")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.freeze:
        if not args.apk:
            parser.error("--freeze requires at least one --apk")
        print(json.dumps(freeze(root, args.freeze, args.apk, args.build_log), ensure_ascii=False))
        return
    if args.apk or args.build_log:
        parser.error("--apk/--build-log belong to --freeze")
    if args.before == args.after:
        parser.error("--before and --after must identify different phases")
    report = collect(root, args.before, args.after)
    root.mkdir(parents=True, exist_ok=True)
    output = root / "current-report.json"
    if output.exists() and json.loads(output.read_text()).get("schema") != SCHEMA:
        raise ValueError("Refusing to replace a file from another producer")
    with tempfile.NamedTemporaryFile(dir=root, prefix=".summary-", delete=False) as temporary:
        temporary.write(dump(report))
        name = temporary.name
    Path(name).replace(output)
    print(json.dumps({"report": str(output), "attempts": len(report["attempts"]),
                      "comparison_eligible": report["comparison"]["eligible"],
                      "reasons": report["comparison"]["reasons"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
