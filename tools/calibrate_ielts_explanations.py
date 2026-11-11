"""Bounded native explanation calibration; dry-run by default, never publishes exercises.

The questions and accepted blind-review objects are synthetic scaffolding. Only
the explanation stage is measured; this is not a new independent answer review.
Private labels live outside every native stage's sandbox and are used only after
the production explanation validator returns.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
import time

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import ielts_contract as contract
import ielts_review as blind_api
from harness_ielts import (HarnessError, RUNTIME_VERSION, prepare_stage, run_native_stage,
                           sha256_file, stage_execution_limits, write_json_new)
from harness_model_proxy import ModelBudget, ModelRoute, contains_credential
from ielts_pipeline import (normalize_submission, stage_prompt, _verify_runtime_binding,
                            _verify_model_bindings)
from ielts_explanation_review import validate_explanation_review
from serve_ielts import read_credential

SCHEMA = "lexicon.ielts.explanation-calibration-run.v1"
CASE_SCHEMA = "lexicon.ielts.explanation-calibration-cases.v1"
RELATIONS = ("supported", "contradicted", "unsupported", "ambiguous")
INPUT_FIELDS = ("case_id", "source", "explanation")
CODE_FILES = ("calibrate_ielts_explanations.py", "harness_ielts.py", "harness_ielts_plugin.mjs",
              "harness_model_proxy.py", "ielts_pipeline.py", "ielts_explanation_review.py",
              "ielts_contract.py", "ielts_review.py", "ielts_candidate.py", "ielts_json_schema.py",
              "ielts_corpus.py", "ielts_jobs.py", "serve_ielts.py", "ielts_visual.py")


def _fail(code):
    raise HarnessError(code)


def load_cases(path):
    """Validate the frozen nine-case design; do not expose its scoring fields."""
    raw = Path(path).read_bytes()
    if len(raw) > 200_000:
        _fail("calibration_cases_too_large")
    value = json.loads(raw)
    if (not isinstance(value, dict) or value.get("schema") != CASE_SCHEMA or
            value.get("model_input_fields") != list(INPUT_FIELDS) or
            value.get("expected_fields_are_private_to_scoring") is not True):
        _fail("calibration_cases_contract")
    cases = value.get("cases")
    if not isinstance(cases, list) or len(cases) != 9:
        _fail("calibration_case_count")
    seen, pairs, clarity = set(), {}, []
    for case in cases:
        if not isinstance(case, dict):
            _fail("calibration_case_shape")
        for name, maximum in (("source", contract.MAX_ARTICLE_UNITS), ("explanation", 1600)):
            contract._text(case.get(name), "calibration." + name, maximum)
        cid = contract._identifier(case.get("case_id"), "calibration.case_id")
        if cid in seen or case.get("expected_relation") not in RELATIONS:
            _fail("calibration_case_identity")
        seen.add(cid)
        if case.get("scope") not in ("article_report", "world_claim", "ambiguous"):
            _fail("calibration_scope")
        contract._text(case.get("label_reason"), "calibration.label_reason", 1600)
        if case.get("case_kind") == "authored_pair":
            pair = contract._identifier(case.get("pair_id"), "calibration.pair_id")
            pairs.setdefault(pair, []).append(case)
        elif case.get("case_kind") == "legacy_clarity":
            clarity.append(case)
        else:
            _fail("calibration_case_kind")
    if len(pairs) != 4 or len(clarity) != 1 or clarity[0]["expected_relation"] != "ambiguous":
        _fail("calibration_design")
    for pair in pairs.values():
        if (len(pair) != 2 or pair[0]["source"] != pair[1]["source"] or
                sum(case["expected_relation"] == "supported" for case in pair) != 1 or
                any(case["expected_relation"] == "ambiguous" for case in pair)):
            _fail("calibration_pair_design")
    # Only these projected fields enter material construction. No category or
    # label is used to choose a question, reference, prompt, or blind observation.
    groups = [[{name: case[name] for name in INPUT_FIELDS} for case in pair]
              for pair in pairs.values()]
    groups.append([{name: clarity[0][name] for name in INPUT_FIELDS}])
    return value, groups, raw


def _reference(paragraph, quote):
    position = paragraph["text"].find(quote)
    if position < 0:
        _fail("calibration_reference")
    start = paragraph["start"] + contract.utf16_length(paragraph["text"][:position])
    return {"paragraph_id": paragraph["paragraph_id"], "start": start,
            "end": start + contract.utf16_length(quote), "quote": quote}


def build_material(projected, corpus_path, group_number):
    """Build synthetic but strictly valid question/answer scaffolding from a projection."""
    if (not projected or any(set(case) != set(INPUT_FIELDS) for case in projected) or
            any(case["source"] != projected[0]["source"] for case in projected)):
        _fail("calibration_projection")
    body = projected[0]["source"]
    paragraphs, start = [], 0
    for i, text in enumerate(body.split("\n\n")):
        end = start + contract.utf16_length(text)
        paragraphs.append({"paragraph_id": "p{}".format(i + 1), "start": start, "end": end, "text": text})
        start = end + 2
    gid = "calibration-{:02d}".format(group_number)
    submission = {"schema": "lexicon.ielts.submit.v1", "request_id": gid, "article_id": gid,
        "body": body, "paragraphs": paragraphs,
        "reading_requests": [{"question_id": case["case_id"], "type": "multiple_choice"} for case in projected],
        "writing_task2_request": {"task_id": "w2", "task_type": "extent_of_agreement"}}
    request, _, _ = normalize_submission(submission, corpus_path)
    first_sentence = re.split(r"(?<=[.!?])\s+", paragraphs[0]["text"], maxsplit=1)[0]
    reference = _reference(paragraphs[0], first_sentence)
    options = [{"option_id": letter, "text": text} for letter, text in zip("ABCD", (
        first_sentence, "The project concerns medieval manuscript restoration.",
        "The project concerns deep-ocean volcanic mapping.", "The project concerns orchestral instrument tuning."))]
    package = {"schema": contract.PACKAGE_SCHEMA, "request_id": gid, "article_id": gid,
        "body_hash": request["body_hash"], "request_sha256": contract.canonical_sha256(request),
        "label": contract.PRACTICE_LABEL, "style_source_ids": [s["source_id"] for s in request["style_sources"]],
        "reading_questions": [{"question_id": case["case_id"], "type": "multiple_choice",
            "prompt": "Which source statement is explicitly present for calibration item {}?".format(case["case_id"]),
            "options": copy.deepcopy(options), "number_of_answers": 1, "answer": "A",
            "explanation": case["explanation"], "source_refs": [copy.deepcopy(reference)]} for case in projected],
        "writing_task2": {"task_id": "w2", "task_type": "extent_of_agreement",
            "prompt": "Public decisions should prioritise the kinds of evidence described in this passage. To what extent do you agree or disagree?",
            "minimum_words": 250, "suggested_minutes": 40, "source_refs": [copy.deepcopy(reference)]},
        "writing_task1": {"status": "unavailable", "reason": "no_verified_visual"}}
    synthetic = "Synthetic calibration scaffold; no independent model answer review was run."
    checks = lambda names: {name: {"status": "pass", "reason": synthetic} for name in names}
    blind = {"schema": blind_api.REVIEW_SCHEMA, "review_id": gid + "-synthetic-blind",
        "request_sha256": contract.canonical_sha256(request), "package_sha256": contract.canonical_sha256(package),
        "reading_reviews": [{"question_id": case["case_id"], "type": "multiple_choice", "observed_answer": "A",
            "conclusion": synthetic, "source_refs": [copy.deepcopy(reference)], "checks": checks(blind_api.READING_CHECKS),
            "option_reviews": [{"option_id": letter, "status": "supported" if letter == "A" else "not_supported",
                "reason": "Exact first source sentence." if letter == "A" else "Unrelated synthetic distractor.",
                "source_ref_indices": [0] if letter == "A" else []} for letter in "ABCD"]} for case in projected],
        "writing_task2_review": {"task_id": "w2", "task_type": "extent_of_agreement",
            "article_focus": synthetic, "discussion_focus": synthetic, "conclusion": synthetic,
            "source_refs": [copy.deepcopy(reference)], "checks": checks(blind_api.WRITING_CHECKS),
            "instruction_alignment": {
                "instruction_quote": "To what extent do you agree or disagree?",
                "target_readings": [{"target_quote": "Public decisions should prioritise the kinds of evidence described in this passage.",
                                     "interpretation": "Whether public decisions should prioritise the kinds of evidence described in this passage."}],
                "relation": "clear", "reason": synthetic,
            }}}
    contract.validate_request(request)
    contract.validate_package(request, package)
    blind_receipt = blind_api.validate_review(request, package, blind, blind["review_id"])
    if blind_receipt["status"] != "eligible_for_caller_publication":
        _fail("calibration_scaffold_rejected")
    review_id = gid + "-explanations"
    prompt = stage_prompt(request, package=package, blind_review=blind, review_id=review_id)
    return {"request": request, "package": package, "blind_review": blind,
            "review_id": review_id, "prompt": prompt,
            "synthetic_blind_scaffold": True, "independent_answer_review_performed": False,
            "publication_eligible": False}


def score_audit(private_cases, audit):
    """Score model observations only after the production audit validates them."""
    by_id = {case["case_id"]: case for case in private_cases}
    results = []
    for item in audit["explanation_results"]:
        case = by_id[item["question_id"]]
        relations = {unit["relation"] for unit in item["unit_results"]}
        observed = next(relation for relation in ("contradicted", "unsupported", "ambiguous", "supported") if relation in relations)
        expected = case["expected_relation"]
        accept, want_accept = item["status"] == "pass", expected == "supported"
        results.append({"case_id": case["case_id"], "case_kind": case["case_kind"],
            "expected_relation": expected, "observed_relation": observed, "exact_relation": observed == expected,
            "unit_relations": [unit["relation"] for unit in item["unit_results"]],
            "observed_scopes": [unit["scope"] for unit in item["unit_results"]],
            "publication_decision": "accept" if accept else "reject",
            "expected_publication_decision": "accept" if want_accept else "reject",
            "publication_decision_correct": accept == want_accept,
            "false_accept": accept and not want_accept, "false_reject": not accept and want_accept})
    return results


def _counts(rows):
    return {"case_count": len(rows), "exact_relation": sum(row["exact_relation"] for row in rows),
            "publication_decision_correct": sum(row["publication_decision_correct"] for row in rows),
            "false_accept": sum(row["false_accept"] for row in rows),
            "false_reject": sum(row["false_reject"] for row in rows)}


def _safe_error(error):
    # Error messages may contain local/private material. Only a bounded code is
    # returned; no exception message, credential, or model output goes to stdout.
    code = getattr(error, "code", None)
    return code if isinstance(code, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code) else "calibration_local_error"


def run_calibration(cases_path, corpus_path, out, *, execute=False, key_file=None, reasoning_effort="high"):
    if reasoning_effort not in ("off", "low", "high", "max"):
        _fail("calibration_reasoning_invalid")
    if execute and key_file is None:
        _fail("calibration_key_file_required")
    cases_path, corpus_path, out = Path(cases_path), Path(corpus_path), Path(out)
    private, groups, raw_cases = load_cases(cases_path)
    source_hashes = {name: sha256_file(TOOLS / name) for name in CODE_FILES}
    cases_hash, corpus_hash = hashlib.sha256(raw_cases).hexdigest(), sha256_file(corpus_path)
    # Validate all five groups before any credential access or native startup.
    materials = [build_material(group, corpus_path, i + 1) for i, group in enumerate(groups)]
    out.mkdir(parents=True, exist_ok=False, mode=0o700)
    scoring = out / "scoring"; scoring.mkdir(mode=0o700)
    (scoring / "cases.json").write_bytes(raw_cases); (scoring / "cases.json").chmod(0o600)
    protocol = cases_path.with_name("PROTOCOL.md")
    protocol_hash = None
    if protocol.is_file():
        protocol_bytes = protocol.read_bytes(); protocol_hash = hashlib.sha256(protocol_bytes).hexdigest()
        (scoring / "PROTOCOL.md").write_bytes(protocol_bytes); (scoring / "PROTOCOL.md").chmod(0o600)
    frozen_corpus = out / "corpus.json"
    frozen_corpus.write_bytes(corpus_path.read_bytes()); frozen_corpus.chmod(0o600)
    if sha256_file(frozen_corpus) != corpus_hash:
        _fail("calibration_corpus_changed")
    limits = stage_execution_limits(reasoning_effort)
    manifest = {"schema": SCHEMA, "mode": "execute" if execute else "dry_run", "cases_sha256": cases_hash,
        "protocol_sha256": protocol_hash, "corpus_sha256": corpus_hash, "code_sha256": source_hashes,
        "group_count": len(materials), "case_count": len(private["cases"]), "max_model_requests": 16,
        "stage_timeout_seconds": 240, "model": "deepseek-v4-pro", "reasoning_effort": reasoning_effort, **limits,
        "synthetic_blind_scaffold": True, "independent_answer_review_performed": False,
        "publication_eligible": False, "new_package_published": False,
        "semantic_guarantee": False, "official_exam_validity": False}
    write_json_new(out / "manifest.json", manifest)
    for i, material in enumerate(materials, 1):
        directory = out / "materials" / "group-{:02d}".format(i); directory.mkdir(parents=True, mode=0o700)
        for name in ("request", "package", "blind_review"):
            write_json_new(directory / (name + ".json"), material[name])
        (directory / "prompt.txt").write_text(material["prompt"], encoding="utf-8")
        (directory / "prompt.txt").chmod(0o600)
    def frozen_inputs_unchanged():
        try:
            return ({name: sha256_file(TOOLS / name) for name in CODE_FILES} == source_hashes and
                    sha256_file(cases_path) == cases_hash and sha256_file(corpus_path) == corpus_hash and
                    (protocol_hash is None or sha256_file(protocol) == protocol_hash))
        except OSError:
            return False

    budget, results, stages, native_receipts, attempted_groups = ModelBudget(16), [], [], [], []
    error, started, completed = None, time.monotonic(), not execute
    try:
        if execute:
            key = read_credential(key_file)
            for i, material in enumerate(materials, 1):
                if (budget.halted or budget.calls + 2 > budget.max_calls or
                        any(record["phase"] != "completed" for record in budget.snapshot())):
                    _fail("calibration_budget_stopped")
                if not frozen_inputs_unchanged():
                    _fail("calibration_inputs_changed")
                if contains_credential(material["prompt"].encode("utf-8"), key):
                    _fail("credential_in_input")
                first = budget.calls + 1
                attempted_groups.append(i)
                with ModelRoute(key, model="deepseek-v4-pro", token_limit=limits["token_limit"],
                        provider_deadline_seconds=limits["provider_deadline_seconds"], budget=budget) as route:
                    prepared = prepare_stage(out / "runtime" / "group-{:02d}".format(i), material["request"],
                        frozen_corpus, route, mode="explanation_review", package=material["package"],
                        blind_review=material["blind_review"], review_id=material["review_id"], reasoning_effort=reasoning_effort)
                    result, receipt = run_native_stage(prepared, material["prompt"], mode="explanation_review",
                        timeout=240, model_budget=budget, first_request_ordinal=first)
                directory = out / "materials" / "group-{:02d}".format(i)
                if not isinstance(receipt, dict):
                    _fail("calibration_runtime_receipt_missing")
                receipt = dict(receipt, model_request_ordinals=list(range(first, budget.calls + 1)))
                write_json_new(directory / "runtime-receipt.json", receipt)
                native_receipts.append(receipt)
                stages.append({"group_number": i, "status": receipt.get("status"),
                               "process_reaped": receipt.get("process_reaped") is True,
                               "runtime_binding_verified": False,
                               "model_request_ordinals": receipt["model_request_ordinals"]})
                records = budget.snapshot()
                if any(record.get("phase") != "completed" for record in records):
                    _fail("provider_completion_unknown")
                if (result is None or receipt.get("status") != "completed" or
                        receipt.get("process_reaped") is not True):
                    _fail("calibration_runtime_failed")
                _verify_model_bindings(native_receipts, records)
                _verify_runtime_binding(receipt, material["prompt"], result)
                if (receipt.get("stage") != "explanation_review" or receipt.get("runtime_version") != RUNTIME_VERSION or
                        receipt.get("model") != "deepseek-v4-pro" or receipt.get("reasoning_effort") != reasoning_effort or
                        receipt.get("corpus_sha256") != corpus_hash or
                        len({r.get("session_id") for r in native_receipts}) != len(native_receipts)):
                    _fail("calibration_runtime_binding")
                if contains_credential(json.dumps(result, ensure_ascii=False).encode("utf-8"), key):
                    _fail("credential_in_output")
                stages[-1]["runtime_binding_verified"] = True
                write_json_new(directory / "result.json", result)
                audit = validate_explanation_review(material["request"], material["package"], material["blind_review"],
                    result, material["review_id"])
                write_json_new(directory / "audit.json", audit)
                measured = score_audit(private["cases"], audit)
                write_json_new(directory / "scores.json", measured)
                results.extend(measured)
                # A semantic rejection is a completed measurement, not a retry trigger.
            completed = len(stages) == len(materials) and len(results) == len(private["cases"])
    except (Exception, KeyboardInterrupt) as failure:
        error = "calibration_interrupted" if isinstance(failure, KeyboardInterrupt) else _safe_error(failure)
    finally:
        records = budget.snapshot()
        write_json_new(out / "model-requests.json", {"max_calls": budget.max_calls, "reserved_calls": budget.calls,
            "halted": budget.halted, "requests": records,
            "real_provider_requests": sum(record["real_provider_request"] for record in records),
            "provider_completion_unconfirmed": sum(record["phase"] != "completed" for record in records)})
    unchanged = frozen_inputs_unchanged()
    if not unchanged:
        error, completed = "calibration_inputs_changed", False
    paired = [row for row in results if row["case_kind"] == "authored_pair"]
    clarity = [row for row in results if row["case_kind"] == "legacy_clarity"]
    summary = {**manifest, "status": "stopped" if error else "dry_run" if not execute else "completed" if completed else "stopped",
        "error": error, "elapsed_seconds": round(time.monotonic() - started, 3), "source_files_unchanged": unchanged,
        "stages": stages, "attempted_groups": attempted_groups,
        "runtime_provenance_unconfirmed": len(attempted_groups) != len(stages) or any(
            not stage["process_reaped"] or not stage["runtime_binding_verified"] for stage in stages),
        "results": results, "paired_cases": _counts(paired), "clarity_cases": _counts(clarity),
        "by_expected_relation": {relation: _counts([row for row in paired if row["expected_relation"] == relation])
                                 for relation in RELATIONS},
        "unstarted_groups": [i for i in range(1, len(materials) + 1) if i not in attempted_groups],
        "real_provider_requests": sum(record["real_provider_request"] for record in records),
        "provider_completion_unconfirmed": sum(record["phase"] != "completed" for record in records),
        "all_expected_relations_matched": bool(execute and completed and not error and all(row["exact_relation"] for row in results))}
    write_json_new(out / "summary.json", summary)
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--reasoning-effort", choices=("off", "low", "high", "max"), default="high")
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args(argv)
    if args.execute and args.key_file is None:
        parser.error("--execute requires --key-file")
    try:
        summary = run_calibration(args.cases, args.corpus, args.out, execute=args.execute, key_file=args.key_file, reasoning_effort=args.reasoning_effort)
        print(json.dumps({key: summary[key] for key in ("status", "error", "paired_cases", "clarity_cases",
            "real_provider_requests", "provider_completion_unconfirmed", "all_expected_relations_matched")}, sort_keys=True))
        return 0 if summary["status"] in ("dry_run", "completed") else 2
    except (Exception, KeyboardInterrupt) as failure:
        print(json.dumps({"status": "stopped", "error": _safe_error(failure)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
