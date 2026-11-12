"""Answer-blind, request-bound tool parameter schemas for DeepSeek Harness.

The installed Harness 0.2.1-rc.5 accepts a JSON Schema subset: type, oneOf,
properties, required, additionalProperties, items, scalar enum/const and annotations.
It does not accept tuple items, references, min/max, or conditional keywords.
These fully inlined schemas therefore enforce fields, types, response vocabulary
and each permitted question identity/type pair. Descriptions state exact counts,
order, UTF-16 bounds and extraction rules. Tool inputs accept either full ranges
or exact paragraph/quote locators; the host compiles locators before validation.
New generation inputs separate writing background and the target statement. The
host assembles the final prompt; saved packages and reviews keep their old shape.
ielts_contract / ielts_review remain the authoritative validators for complete
artifact references, all other constraints and publication decisions.

No model, network, filesystem, or credential access occurs here. In particular,
review schema shape never depends on the generator's answers or selected ranges.
The opaque package digest binds the independent review to its candidate.
"""
from __future__ import annotations

from typing import Any, Dict

try:
    from . import ielts_contract as contract
except ImportError:
    import ielts_contract as contract

__all__ = ["generation_schema", "review_schema"]

_INPUT_RULES = (
    "All fields required; no extras. Text must be nonblank valid Unicode except an explicitly empty writing background; only tab/newline controls are allowed unless a field narrows this. "
    "Text limits use UTF-16 units. Paragraph IDs must belong to the frozen table below; the compiler and final contract check membership. "
    "References accept either all four fields or only paragraph_id/quote. Full offsets are absolute, checked and never repaired. "
    "Locators require one case-sensitive literal match in their paragraph; whitespace/punctuation are not normalized. "
    "A blank, absent or repeated quote is rejected; use lexicon_ielts_read and full offsets for repeated text. "
    "The host compiles locators into complete references. Completion answers use complete original tokens under "
    + contract.WORD_RULE + "; do not paraphrase, change inflection or join separated words. Prefer an exact answer quote. "
    "If only context is cited, a unique complete-token match inside its range union can add an exact reference covering just that phrase. "
    "Original context is retained; at most five parent references remain. Alternatives obey the same rule. "
    "The unchanged final contract checks counts/order, limits, ranges and answers; schema acceptance alone does not prove correctness."
)


def _object(properties: dict, description: str = "") -> dict:
    value = {"type": "object", "properties": properties,
             "required": list(properties), "additionalProperties": False}
    if description:
        value["description"] = description
    return value


def _constant(value: Any) -> dict:
    if type(value) not in (str, int, bool, type(None)):
        raise TypeError("Harness const supports scalar values only")
    kind = ("null" if value is None else "boolean" if type(value) is bool else
            "integer" if type(value) is int else "string")
    return {"type": kind, "const": value}


def _choice(values: list, *, nullable: bool = False) -> dict:
    value = {"type": "string", "enum": list(values)}
    return {"oneOf": [value, {"type": "null"}]} if nullable else value


def _union(values: list) -> dict:
    return values[0] if len(values) == 1 else {"oneOf": values}


def _text(maximum: int, description: str = "", *, quote: bool = False) -> dict:
    rules = "1–{} UTF-16 units.".format(maximum)
    if not quote:
        rules += " No URLs."
    if description:
        rules += " " + description
    return {"type": "string", "description": rules}


def _array(items: dict, description: str) -> dict:
    return {"type": "array", "items": items, "description": description}


def _ordered(items: list, identities: list, noun: str) -> dict:
    return _array(_union(items), "Exactly {} {}, once each in this order: {}. The host checks order and completeness.".format(
        len(identities), noun, ", ".join(identities)))


def _fixed_ids(identities: list) -> dict:
    return _array({"type": "string"}, "Exactly {} paragraph IDs, once each in the frozen root-table order; checked by the host.".format(len(identities)))


def _reference(request: dict) -> dict:
    full = _object({
        "paragraph_id": {"type": "string"},
        "start": {"type": "integer", "description": "Inclusive absolute UTF-16 offset."},
        "end": {"type": "integer", "description": "Exclusive absolute UTF-16 offset."},
        "quote": _text(contract.MAX_ARTICLE_UNITS, quote=True),
    })
    locator = _object({
        "paragraph_id": {"type": "string"},
        "quote": _text(contract.MAX_ARTICLE_UNITS, quote=True),
    })
    return {"oneOf": [full, locator]}


def _bounds(request: dict) -> str:
    return " Frozen absolute UTF-16 paragraph bounds: " + "; ".join(
        "{}=[{},{})".format(p["paragraph_id"], p["start"], p["end"]) for p in request["paragraphs"])


def _references(request: dict, minimum: int = 0, maximum: int = 5) -> dict:
    description = ("Exactly []; absence is not a supporting quote." if maximum == 0 else
                   "{}–{} references; no duplicate resolved ranges.".format(minimum, maximum))
    return _array(_reference(request), description)


def _completion_answer(question: dict, *, nullable: bool = False, alternative: bool = False) -> dict:
    value = _text(200, "1–{} complete original words; digits {}. {}".format(
        question["max_words"], "allowed" if question["allow_numbers"] else "forbidden",
        "Must match source_ref.quote after compilation." if alternative else "See root completion rules."))
    return {"oneOf": [value, {"type": "null", "description": "Use null if no defensible independent answer exists; it blocks publication."}]} if nullable else value


def _question_groups(questions: list) -> list:
    """Share only identical input rules, retaining every question identity."""
    groups = {}
    for question in questions:
        key = (question["type"], question.get("max_words"), question.get("allow_numbers"))
        groups.setdefault(key, []).append(question)
    return [(members[0], [member["question_id"] for member in members]) for members in groups.values()]


def _question_id(identities: list) -> dict:
    return _constant(identities[0]) if len(identities) == 1 else _choice(identities)


def _generation_question(request: dict, wanted: dict, identities: list) -> dict:
    kind = wanted["type"]
    properties = {
        "question_id": _question_id(identities), "type": _constant(kind),
        "prompt": _text(1200), "answer": {}, "explanation": _text(1600),
        "source_refs": _references(request, 1),
    }
    if kind == "multiple_choice":
        properties["answer"] = _choice(list("ABCD"))
        properties["number_of_answers"] = _constant(1)
        properties["options"] = _ordered([
            _object({"option_id": _constant(option), "text": _text(600, "All four option texts must differ after whitespace and case normalization.")})
            for option in "ABCD"
        ], list("ABCD"), "options")
    elif kind in ("identifying_information", "identifying_writers_views"):
        properties["answer"] = _choice(["true", "false"] if kind == "identifying_information" else ["yes", "no"])
        normal = _object(properties)
        absent = dict(properties, answer=_constant("not_given"), source_refs=_references(request, 0, 0),
                      reviewed_paragraph_ids=_fixed_ids([p["paragraph_id"] for p in request["paragraphs"]]),
                      absence_justification=_text(1600, "Explain why all supplied paragraphs leave the claim unresolved; absence of a keyword alone is insufficient."))
        return {"oneOf": [normal, _object(absent)], "description": "Use the absence fields only when answer is not_given. Otherwise omit them entirely."}
    else:
        properties["prompt"] = _text(1200, "Exactly one blank spelled ____ (four underscores); no second blank or run of five or more underscores.")
        properties["answer"] = _completion_answer(wanted)
        properties["max_words"] = _constant(wanted["max_words"])
        properties["allow_numbers"] = _constant(wanted["allow_numbers"])
        properties["normalization_rule"] = _constant(contract.WORD_RULE)
    return _object(properties)


def _visual_schema(request):
    return _object({
        "schema": _constant("lexicon.ielts.visual.v1"), "kind": _choice(["grouped_bar", "table"]),
        "title": _text(240, "Neutral English description of what is measured, where and when; no conclusions."),
        "unit": _text(80, "One common English unit. Never convert or infer units."),
        "categories": _array(_text(80), "2–8 distinct labels in chart order."),
        "series": _array(_object({"label": _text(80), "cells": _array(_object({
            "value": {"type": "string", "description": "Canonical nonnegative decimal, up to 12 integer and 4 fractional digits; no exponent or trailing fractional zeros. Exact source value, commas may be removed."},
            "source_ref": _reference(request),
        }), "One cell per category in order; each cites its exact source numeric lexeme or a source context containing exactly one occurrence of this value. The host extracts the unique token and preserves the context.")}), "1–3 series; 4–24 total cells; at least two distinct values."),
        "context_refs": _references(request, 1, 32),
    })


def _visual_generation(request):
    if "writing_task1_request" not in request:
        return _object({"status": _constant("unavailable"), "reason": _constant("no_verified_visual")})
    return _union([
        _object({"status": _constant("unavailable"), "reason": _constant("insufficient_comparable_data")}),
        _object({"status": _constant("available"), "visual": _visual_schema(request)}),
    ])


def _visual_review_schema(request, package):
    task = package["writing_task1"]
    names = ["data_sufficiency", "task_suitability"]
    indices = []
    if task["status"] == "available":
        names += ["title_and_units", "category_series_mapping", "scope_and_comparability"]
        indices = [(si, ci) for si, series in enumerate(task["task"]["visual"]["series"]) for ci, _ in enumerate(series["cells"])]
    check = _object({"status": _choice(["pass", "fail", "insufficient"]), "reason": _text(320)})
    cell = _object({"series_index": {"type": "integer"}, "category_index": {"type": "integer"},
                    "status": _choice(["pass", "fail", "insufficient"]), "reason": _text(320)})
    return _object({"task_id": _constant(request["writing_task1_request"]["task_id"]),
                    "availability": _constant(task["status"]),
                    "checks": _object({name:check for name in names}),
                    "cell_reviews": _array(cell, "Exactly these (series_index,category_index) pairs, once in order: " + str(indices))})


def generation_schema(request: Any) -> Dict[str, Any]:
    """Return the candidate object schema for one validated, frozen request.

    This is the value schema, not the enclosing {candidate: ...} tool envelope.
    It uses only the executable schema subset supported by the pinned Harness.
    """
    receipt = contract.validate_request(request)
    wanted = request["reading_requests"]
    writing = request["writing_task2_request"]
    try:
        from .ielts_candidate import focused_information_practice, FOCUSED_INFORMATION_GUIDANCE
    except ImportError:
        from ielts_candidate import focused_information_practice, FOCUSED_INFORMATION_GUIDANCE
    practice_guidance = FOCUSED_INFORMATION_GUIDANCE if focused_information_practice(request) else ""
    return _object({
        "schema": _constant(contract.PACKAGE_SCHEMA),
        "request_id": _constant(request["request_id"]),
        "article_id": _constant(request["article_id"]),
        "body_hash": _constant(request["body_hash"]),
        "request_sha256": _constant(receipt["request_sha256"]),
        "label": _constant(contract.PRACTICE_LABEL),
        "style_source_ids": _array(_choice([s["source_id"] for s in request["style_sources"]]),
                                   "1–{} distinct supplied style-source IDs.".format(len(request["style_sources"]))),
        "reading_questions": _ordered([_generation_question(request, q, ids) for q, ids in _question_groups(wanted)],
                                      [q["question_id"] for q in wanted], "reading questions"),
        "writing_task2": _object({
            "task_id": _constant(writing["task_id"]), "task_type": _constant(writing["task_type"]),
            "prompt_draft": _object({
                "background": {"type": "string", "description": "0–1800 UTF-16 units. Prefer empty. A nonempty value must exactly equal one writing source_refs quotation. Do not paraphrase factual context, names or institutions. No URLs or task instruction."},
                "target_statement": _text(1800, "One nonempty paragraph containing a complete proposition to evaluate, not a report that two opinions exist. No CR, LF, tab, U+0085, U+2028 or U+2029. A comparison or compound policy claim may form one proposition. Do not add the task instruction."),
            }, "Generation input only; do not also provide prompt. The host joins the nonempty background, target_statement, and 'To what extent do you agree or disagree with this statement?' with two newlines. The compiled prompt must fit 1800 UTF-16 units. Text is preserved; structural assembly does not prove a clear evaluation target."),
            "minimum_words": _constant(250), "suggested_minutes": _constant(40),
            "source_refs": _references(request, 1),
        }),
        "writing_task1": _visual_generation(request),
    }, "Original single-passage AI practice input; at most 1,000,000 serialized UTF-8 bytes. " + practice_guidance + _INPUT_RULES + _bounds(request))


def _checks(names: tuple, states: tuple) -> dict:
    return _object({name: _object({"status": _choice(list(states)), "reason": _text(1600, "Concrete independent observation, not bare approval.")}) for name in names})


def _absence_review(request: dict) -> dict:
    paragraphs = request["paragraphs"]
    return _object({
        "missing_claim": _text(1000),
        "reviewed_paragraph_ids": _fixed_ids([p["paragraph_id"] for p in paragraphs]),
        "absence_reason": _text(1800),
        "paragraph_reviews": _array(
            _object({
                "paragraph_id": {"type": "string"},
                "what_is_stated": _text(1000), "why_not_answered": _text(1000),
                "context_refs": dict(_references(request, 0, 2), description="0–2 context references from this item's paragraph. At least one is needed for publication; context alone does not prove absence."),
            }), "Exactly {} paragraph reviews in frozen root-table order; checked by the host.".format(len(paragraphs))),
    })


def _review_question(request: dict, question: dict, identities: list, review_module: Any) -> dict:
    kind = question["type"]
    choices = {"multiple_choice": list("ABCD"), "identifying_information": ["true", "false"],
               "identifying_writers_views": ["yes", "no"]}
    answer = (_choice(choices[kind], nullable=True) if kind in choices else
              _completion_answer(question, nullable=True))
    properties = {
        "question_id": _question_id(identities), "type": _constant(kind),
        "observed_answer": answer, "conclusion": _text(1600),
        "source_refs": _references(request),
        "checks": _checks(review_module.READING_CHECKS, review_module.CHECK_STATES),
    }
    if kind == "multiple_choice":
        properties["option_reviews"] = _ordered([
            _object({
                "option_id": _constant(option), "status": _choice(list(review_module.OPTION_STATES)),
                "reason": _text(1600),
                "source_ref_indices": _array({"type": "integer", "description": "Index in this reading review's source_refs array; 0 <= index < its length."},
                                             "0–5 distinct indices, no more than source_refs.length. Supported and contradicted each require at least one exact source reference index; an empty list is a correctable structure error. Other statuses may use an empty list. Do not change a semantic assessment merely to avoid citing its evidence."),
            }) for option in "ABCD"
        ], list("ABCD"), "independent option reviews")
    elif kind in ("identifying_information", "identifying_writers_views"):
        properties.update(claim=_text(1000), not_given_review=_constant(None))
        normal = _object(properties)
        absent = dict(properties, observed_answer=_constant("not_given"), source_refs=_references(request, 0, 0),
                      not_given_review=_absence_review(request))
        return {"oneOf": [normal, _object(absent)], "description": "Choose solely from your independent answer. not_given requires the full absence review and source_refs=[]. Any other answer, including null, requires not_given_review=null."}
    else:
        properties["alternative_answers"] = _array(_object({
            "text": _completion_answer(question, alternative=True), "source_ref": _reference(request),
        }), "0–8 other plausible exact source phrases. Each text must equal its source_ref.quote, differ from observed_answer, and not repeat another text. Report alternatives honestly: a nonempty list blocks unique-answer publication.")
    return _object(properties)


def review_schema(request: Any, package: Any, reviewId: str) -> Dict[str, Any]:
    """Return an independent-review schema without generator answer material.

    Only candidate_view fields and opaque validated digests enter this schema.
    Unanswerable/null answers and fail/insufficient observations remain expressible.
    review is imported lazily because generation has no review-module capability.
    """
    try:
        from . import ielts_review as review_module
    except ImportError:
        import ielts_review as review_module
    review_module._review_id(reviewId)
    receipt = contract.validate_package(request, package)
    visible = contract.candidate_view(request, package)
    questions = visible["reading_questions"]
    writing = visible["writing_task2"]
    return _object({
        "schema": _constant(review_module.REVIEW_SCHEMA), "review_id": _constant(reviewId),
        "request_sha256": _constant(receipt["request_sha256"]), "package_sha256": _constant(receipt["package_sha256"]),
        "reading_reviews": _ordered([_review_question(request, q, ids, review_module) for q, ids in _question_groups(questions)],
                                    [q["question_id"] for q in questions], "independent reading reviews"),
        **({"writing_task1_review": _visual_review_schema(request, package)} if "writing_task1_request" in request else {}),
        "writing_task2_review": _object({
            "task_id": _constant(writing["task_id"]), "task_type": _constant(writing["task_type"]),
            "article_focus": _text(1000), "discussion_focus": _text(1000), "conclusion": _text(1600),
            "source_refs": _references(request),
            "checks": _checks(review_module.WRITING_CHECKS, review_module.CHECK_STATES),
            "instruction_alignment": _object({
                "instruction_quote": _text(1800, "Unique exact continuous writing-prompt quote containing complete tokens; not article evidence. No offsets."),
                "target_readings": _array(_object({
                    "target_quote": _text(1800, "Unique exact continuous writing-prompt quote containing the proposed evaluation target and complete tokens. It cannot be the identical span as instruction_quote; a proper contained proposition is allowed. No offsets."),
                    "interpretation": _text(review_module.MAX_TARGET_INTERPRETATION, "State what this reading asks the learner to evaluate; different readings must differ in meaning."),
                }), "clear: exactly 1; ambiguous: exactly 2 distinct interpretations; task_mismatch/insufficient: 0–2. Counts, literal quotes and token boundaries are checked by the host."),
                "relation": _choice(list(review_module.INSTRUCTION_RELATIONS)),
                "reason": _text(review_module.MAX_ALIGNMENT_REASON),
            }),
        }),
    }, "Independent answer-blind review input; at most 1,000,000 serialized UTF-8 bytes. Null answers and failed/insufficient checks are valid observations that block publication. " + _INPUT_RULES + _bounds(request))
