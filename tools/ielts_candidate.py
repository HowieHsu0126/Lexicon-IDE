"""Compile candidate input shorthand into the unchanged strict IELTS contract.

This is a deterministic interface adapter, not an answer solver. It never edits
the passage, answers, explanations or checks. Generation-only writing drafts gain
the fixed host instruction; existing prompts remain verbatim. Independent reviews
receive no generator draft. All ranges are half-open absolute UTF-16.
The caller must still run the stage's strict validator on the returned value.
"""
from __future__ import annotations

import copy

try:
    from . import ielts_contract as contract
except ImportError:
    import ielts_contract as contract


WRITING_AGREEMENT_INSTRUCTION = "To what extent do you agree or disagree with this statement?"
MAX_WRITING_PROMPT_UNITS = 1800
WRITING_DRAFT_CONFLICT = "candidate_writing_prompt_conflict"
WRITING_DRAFT_TASK_TYPE = "candidate_writing_draft_type"
WRITING_DRAFT_TARGET_PARAGRAPH = "candidate_writing_target_paragraph"

# A product practice recipe, not an official IELTS answer-frequency claim.
# Keep it out of answer-blind review prompts/schemas and saved package contracts.
FOCUSED_INFORMATION_GUIDANCE = (
    "FOCUSED CONTRAST PRACTICE: This product's information-judgement set must exercise supported, "
    "contradicted and unreported information: include at least one true, one false and one not_given. "
    "There is no fixed order or equal ratio. Choose the remaining answers from evidence, never by quota. "
    "Build distinct, plausible statements about the passage topic; use meaningful paraphrases or precise "
    "scope distinctions rather than merely copying sentences or negating the same claim. "
    "For NOT GIVEN choose a concrete relevant detail that the complete passage neither confirms nor "
    "contradicts. An explicit statement that an outcome was not measured contradicts a claim that it was "
    "measured; it is not evidence for NOT GIVEN. Do not invent source facts, change existing answer labels "
    "to fill coverage, use outside knowledge, or make unrelated trivia. Replace a deficient question with "
    "a genuinely different statement and recheck the whole set in passage order. If the supplied scope "
    "cannot support defensible questions, report the limitation; do not fabricate a compliant package. "
)


def focused_information_practice(request):
    wanted = request["reading_requests"]
    return len(wanted) >= 4 and all(q["type"] == "identifying_information" for q in wanted)


def validate_focused_coverage(request, candidate):
    """New generation only; response diversity never establishes answer correctness."""
    if not focused_information_practice(request):
        return
    answers = [item.get("answer") for item in candidate["reading_questions"]]
    if any(not isinstance(answer, str) or answer not in ("true", "false", "not_given") for answer in answers):
        _fail("candidate_information_answer", "candidate.reading_questions", "Use true, false or not_given")
    if set(answers) != {"true", "false", "not_given"}:
        _fail("candidate_focused_response_coverage", "candidate.reading_questions",
              "This contrast practice needs supported, contradicted and unreported claims. Rebuild distinct "
              "questions from the full passage to include true, false and not_given; never relabel a question "
              "just to satisfy coverage. Preserve passage order, empty NOT GIVEN source_refs and its full absence audit.")


def _fail(code, path, message):
    raise contract.ContractError(code, path, message)


def _identity(ref):
    return ref["paragraph_id"], ref["start"], ref["end"]


def _occurrences(text, quote):
    cursor = 0
    while True:
        index = text.find(quote, cursor)
        if index < 0:
            return
        yield index
        cursor = index + 1  # Overlapping literal matches also count.


def _reference(request, parts, value, path):
    if isinstance(value, dict) and set(value) == {"paragraph_id", "quote"}:
        pid = contract._identifier(value["paragraph_id"], path + ".paragraph_id")
        if pid not in parts:
            _fail("unknown_paragraph", path, "Locator is outside the supplied paragraph scope")
        quote = contract._text(value["quote"], path + ".quote", contract.MAX_ARTICLE_UNITS, urls=True)
        paragraph = parts[pid]
        matches = iter(_occurrences(paragraph["text"], quote))
        index = next(matches, None)
        if index is None:
            _fail("candidate_quote_not_found", path, "Locator has no exact case-sensitive match")
        if next(matches, None) is not None:
            _fail("candidate_quote_ambiguous", path,
                  "Use lexicon_ielts_read with the quote and provide the intended full reference")
        start = paragraph["start"] + contract.utf16_length(paragraph["text"][:index])
        value = {"paragraph_id": pid, "start": start,
                 "end": start + contract.utf16_length(quote), "quote": quote}
    # A provided full range is verified, never repaired by searching its quote.
    contract._references([value], request, path)
    return copy.deepcopy(value)


def _walk(request, parts, value, path):
    if isinstance(value, list):
        return [_walk(request, parts, member, "{}[{}]".format(path, i)) for i, member in enumerate(value)]
    if not isinstance(value, dict):
        return copy.deepcopy(value)
    result = {}
    for key, member in value.items():
        where = path + "." + key
        if key in ("source_refs", "context_refs"):
            maximum = 32 if where == "candidate.writing_task1.visual.context_refs" else 5
            contract._list(member, where, 0, maximum)
            result[key] = [_reference(request, parts, ref, "{}[{}]".format(where, i))
                           for i, ref in enumerate(member)]
            contract._references(result[key], request, where, minimum=0, maximum=maximum)
        elif key == "source_ref":
            result[key] = _reference(request, parts, member, where)
        else:
            result[key] = _walk(request, parts, member, where)
    return result


def _answer_reference(request, parts, answer, question, refs, path):
    contract._completion_answer(answer, question, path)
    exact = [ref for ref in refs if ref["quote"] == answer]
    if exact:
        if not all(contract.complete_reference_tokens(request, ref) for ref in exact):
            _fail("candidate_answer_token_boundary", path, "Answer must contain complete source tokens")
        # An explicit, verified answer range is already unambiguous. Preserve it
        # even if the same phrase appears elsewhere in a broader context range.
        return exact[0]
    ranges = {}
    for ref in refs:
        ranges.setdefault(ref["paragraph_id"], []).append((ref["start"], ref["end"]))
    matches = {}
    partial_token_match = False
    for pid, intervals in ranges.items():
        merged = []
        for start, end in sorted(intervals):
            if merged and start <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(merged[-1][1], end))
            else:
                merged.append((start, end))
        paragraph = parts[pid]
        for start, end in merged:
            text = contract.utf16_slice(paragraph["text"], start - paragraph["start"], end - paragraph["start"])
            for index in _occurrences(text, answer):
                absolute = start + contract.utf16_length(text[:index])
                ref = {"paragraph_id": pid, "start": absolute,
                       "end": absolute + contract.utf16_length(answer), "quote": answer}
                if not contract.complete_reference_tokens(request, ref):
                    partial_token_match = True
                    continue
                matches[_identity(ref)] = ref
                if len(matches) > 1:
                    _fail("candidate_answer_ambiguous", path,
                          "Use lexicon_ielts_read to provide the intended full answer reference")
    if not matches:
        if partial_token_match:
            _fail("candidate_answer_token_boundary", path, "Answer must contain complete source tokens")
        _fail("candidate_answer_not_found", path, "Answer has no exact occurrence in the supplied context ranges")
    ref = next(iter(matches.values()))
    if not contract.complete_reference_tokens(request, ref):
        _fail("candidate_answer_token_boundary", path, "Answer must contain complete source tokens")
    return ref


def _append(refs, ref, path):
    if any(_identity(existing) == _identity(ref) for existing in refs):
        return
    if len(refs) >= 5:
        _fail("candidate_reference_capacity", path, "Retaining context and answer references exceeds five ranges")
    refs.append(copy.deepcopy(ref))


def validate_focused_sequence(request, candidate):
    """Focused information sets should follow known evidence forward.

    This is a new-generation reading-flow rule, not a rewrite of archived
    questions. NOT GIVEN has no supporting range and is deliberately skipped;
    its placement still needs a semantic review against the full passage.
    """
    wanted = request["reading_requests"]
    if len(wanted) < 2 or any(q["type"] != "identifying_information" for q in wanted):
        return
    previous = -1
    for index, item in enumerate(candidate["reading_questions"]):
        if item.get("answer") == "not_given":
            continue
        refs = contract._references(item.get("source_refs"), request,
                                    "candidate.reading_questions[{}].source_refs".format(index))
        anchor = min(ref["start"] for ref in refs)
        if anchor < previous:
            _fail("candidate_focused_evidence_order", "candidate.reading_questions[{}]".format(index),
                  "Arrange this focused information set in forward passage order; rebuild complete question objects with the requested IDs. Never change an answer just to fix order.")
        previous = anchor


def _generation_writing_prompt(request, candidate):
    """Compile only an explicit draft; never infer a target from a free prompt."""
    path = "candidate.writing_task2"
    writing = candidate.get("writing_task2")
    if not isinstance(writing, dict):
        _fail("object_required", path, "Expected a writing task object")
    if "prompt" in writing and "prompt_draft" in writing:
        _fail(WRITING_DRAFT_CONFLICT, path, "Use prompt or prompt_draft, never both")
    common = {"task_id", "task_type", "minimum_words", "suggested_minutes", "source_refs"}
    if "prompt_draft" not in writing:
        contract._object(writing, path, common | {"prompt"})
        # Revalidating a compiled draft must enforce the same source-background rule.
        pieces = writing["prompt"].split("\n\n") if isinstance(writing["prompt"], str) else []
        if len(pieces) >= 3 and pieces[-1] == WRITING_AGREEMENT_INSTRUCTION:
            _writing_background("\n\n".join(pieces[:-2]), writing["source_refs"], path)
        return  # Legacy and already compiled prompt bytes are preserved.
    contract._object(writing, path, common | {"prompt_draft"})
    if (request["writing_task2_request"]["task_type"] != "extent_of_agreement" or
            writing["task_type"] != "extent_of_agreement"):
        _fail(WRITING_DRAFT_TASK_TYPE, path + ".task_type", "Writing drafts require extent_of_agreement")
    draft_path = path + ".prompt_draft"
    draft = contract._object(writing["prompt_draft"], draft_path, {"background", "target_statement"})
    # An exact empty background is optional; whitespace-only text is not content.
    background = contract._text(draft["background"], draft_path + ".background", MAX_WRITING_PROMPT_UNITS,
                                empty=draft["background"] == "")
    target = contract._text(draft["target_statement"], draft_path + ".target_statement", MAX_WRITING_PROMPT_UNITS)
    if any(character in target for character in "\r\n\t\x85\u2028\u2029"):
        _fail(WRITING_DRAFT_TARGET_PARAGRAPH, draft_path + ".target_statement",
              "Target statement must occupy one paragraph without line or tab separators")
    # Separators and instruction are host-owned. Authored punctuation, casing and
    # spaces are not repaired. A compound target still needs independent review.
    prompt = "\n\n".join(([background] if background else []) + [target, WRITING_AGREEMENT_INSTRUCTION])
    contract._text(prompt, path + ".prompt", MAX_WRITING_PROMPT_UNITS)
    _writing_background(background, writing["source_refs"], path)
    del writing["prompt_draft"]
    writing["prompt"] = prompt


def _writing_background(background, refs, path):
    if background and not any(ref.get("quote") == background for ref in refs):
        _fail("candidate_writing_background_not_extractive", path + ".prompt_draft.background",
              "Use an empty background or one exact source_refs quotation. Do not paraphrase institutions, locations or factual context; keep the debatable target self-contained.")


def _generation_visual_numbers(request, visual):
    """Resolve a unique exact numeric token within the model's own cited context."""
    from ielts_visual import SOURCE_NUMBER, number
    import re
    if not isinstance(visual, dict):
        return
    contexts = visual.get("context_refs")
    if not isinstance(contexts, list):
        return
    for si, series in enumerate(visual.get("series", [])):
        for ci, cell in enumerate(series.get("cells", [])):
            path = "candidate.writing_task1.visual.series[{}].cells[{}]".format(si, ci)
            if not isinstance(cell, dict) or not isinstance(cell.get("source_ref"), dict):
                continue
            raw_value = cell.get("value")
            if isinstance(raw_value, str) and SOURCE_NUMBER.fullmatch(raw_value):
                from decimal import Decimal
                canonical = format(Decimal(raw_value.replace(",", "")), "f")
                cell["value"] = canonical.rstrip("0").rstrip(".") if "." in canonical else canonical
            value = number(cell.get("value"), path + ".value")
            ref = cell["source_ref"]
            quote = ref["quote"]
            if SOURCE_NUMBER.fullmatch(quote):
                continue  # Final validation checks boundary and exact equality.
            matches = []
            for match in re.finditer(r"(?<![\w.,+−-])(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|0|[1-9][0-9]*)(?:\.[0-9]+)?(?![\w]|[.,][0-9])", quote):
                if Decimal(match.group().replace(",", "")) == value:
                    start = ref["start"] + contract.utf16_length(quote[:match.start()])
                    matches.append({"paragraph_id":ref["paragraph_id"], "start":start,
                                    "end":start+contract.utf16_length(match.group()), "quote":match.group()})
            if len(matches) != 1:
                _fail("candidate_visual_number_ambiguous" if matches else "candidate_visual_number_missing", path,
                      "Cite a range containing exactly one occurrence of the numeric value; use full offsets to disambiguate")
            if not any(c["paragraph_id"] == ref["paragraph_id"] and c["start"] <= ref["start"] and ref["end"] <= c["end"] for c in contexts):
                # A newly retained context subsumes its narrower existing citations, without losing source bytes.
                contexts[:] = [c for c in contexts if not (c["paragraph_id"] == ref["paragraph_id"] and
                    ref["start"] <= c["start"] and c["end"] <= ref["end"])]
                if len(contexts) >= 32:
                    _fail("candidate_reference_capacity", path, "Visual context exceeds 32 source ranges")
                contexts.append(copy.deepcopy(ref))
            cell["source_ref"] = matches[0]


def compile_candidate(request, candidate, mode):
    """Return a new strict-range candidate with generation-only draft compilation.

    Locator matching is exact and paragraph-local. Missing completion evidence
    is derived only from the union of the item's supplied context ranges; no
    search outside that union, case folding or token/whitespace repair occurs.
    Existing writing prompts are unchanged; explicit drafts gain one fixed task
    instruction. Review content is never repaired or inferred from a draft.
    """
    if mode not in ("generation", "review", "explanation_review"):
        _fail("candidate_mode", "candidate", "Expected generation, review or explanation_review")
    contract.validate_request(request)
    if not isinstance(candidate, dict):
        _fail("object_required", "candidate", "Expected a candidate object")
    parts = {p["paragraph_id"]: p for p in request["paragraphs"]}
    result = _walk(request, parts, candidate, "candidate")
    if mode == "generation":
        _generation_writing_prompt(request, result)
        if "writing_task1_request" in request and result.get("writing_task1", {}).get("status") == "available":
            from ielts_visual import compile_task
            task = result["writing_task1"]
            if "visual" in task:
                contract._object(task, "candidate.writing_task1", {"status", "visual"})
                _generation_visual_numbers(request, task["visual"])
                result["writing_task1"] = {"status": "available", "task": compile_task(
                    request, request["writing_task1_request"]["task_id"], task["visual"])}
    field = {"generation": "reading_questions", "review": "reading_reviews",
             "explanation_review": "explanation_reviews"}[mode]
    items = contract._list(result.get(field), "candidate." + field,
                           len(request["reading_requests"]), len(request["reading_requests"]))
    for i, (item, question) in enumerate(zip(items, request["reading_requests"])):
        where = "candidate.{}[{}]".format(field, i)
        if not isinstance(item, dict):
            _fail("object_required", where, "Expected a reading item")
        for key in (("question_id",) if mode == "explanation_review" else ("question_id", "type")):
            contract._equal(item.get(key), question[key], where + "." + key)
        # Explanation reviews only quote the source. Never derive an answer or
        # edit a judgement from the frozen generator/independent review.
        if mode == "explanation_review":
            continue
        if question["type"] != "short_text_completion":
            continue
        refs = contract._references(item.get("source_refs"), request, where + ".source_refs", minimum=0)
        answer_key = "answer" if mode == "generation" else "observed_answer"
        answer = item.get(answer_key)
        if answer is not None or mode == "generation":
            ref = _answer_reference(request, parts, answer, question, refs, where + "." + answer_key)
            _append(refs, ref, where + ".source_refs")
        if mode == "review":
            alternatives = contract._list(item.get("alternative_answers"), where + ".alternative_answers", 0, 8)
            for j, alternative in enumerate(alternatives):
                path = "{}.alternative_answers[{}]".format(where, j)
                contract._object(alternative, path, {"text", "source_ref"})
                context = alternative["source_ref"]
                ref = _answer_reference(request, parts, alternative["text"], question, [context], path + ".text")
                if ref != context:
                    # The final alternative has one exact answer ref. Retain its
                    # original longer context in the parent rather than drop it.
                    _append(refs, context, where + ".source_refs")
                    _append(refs, ref, where + ".source_refs")
                    alternative["source_ref"] = copy.deepcopy(ref)
    if mode == "generation":
        validate_focused_sequence(request, result)
        validate_focused_coverage(request, result)
    return result
