"""Bounded, offline IELTS reference retrieval for a host-owned Harness gateway.

This corpus contains self-authored descriptions of already inspected public
samples and official format metadata. It contains no questions, answers, sample
essays, full books, or claimed recent-three-year coverage. The frozen source
snapshot is deliberate: adding references requires a new reviewed corpus version.

Host configuration, never model tool arguments, chooses the input/output paths:

    receipt = build("reference.json")
    corpus = load("reference.json", expected_sha256=receipt["corpus_sha256"])
    result = handle_tool_request(corpus, {
        "tool": "retrieve", "arguments": {"module": "reading", "limit": 4}})
    document = read(corpus, "ielts.reading.identifying_information")
    bundle = style_source_bundle(corpus, [document["document"]["document_id"]])
    # Copy bundle["style_sources"] and bundle["style_blueprint_sha256"] into
    # the client-frozen ielts_contract request. Retain corpus_sha256 in the job.

The SHA chain binds recorded receipts, not an independent re-download or proof
of pedagogical quality. Tool adapters must retain their own default-deny guard;
this module provides no shell, file-read-by-model, network or model execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, Dict, List, Optional
import unicodedata


SCHEMA = "lexicon.ielts.corpus.v1"
CORPUS_ID = "ielts-academic-public-samples-20261108"
DEFAULT_RESEARCH_DIR = Path(__file__).resolve().parents[1] / "docs/research/ielts-20261108"
MAX_INDEX_BYTES = 262_144
MAX_QUERY_UNITS = 240
MAX_TOOL_BYTES = 4096
MAX_RESULTS = 8
MAX_STYLE_SOURCES = 12
MODULES = ("general", "reading", "writing")
READING_TYPES = (
    "diagram_label_completion", "identifying_information", "identifying_writers_views",
    "matching_features", "matching_headings", "multiple_choice", "table_completion",
)
QUESTION_TYPES = READING_TYPES + ("academic_task_1", "extent_of_agreement")
MANIFEST_SHA256 = "15ca04e42c66a67a63c2816386e13a908378d7cbbe74f338af9f39951240c8f9"
BLUEPRINT_SHA256 = "c16d8e852081c5c5c4267af20cdac001f8ff2c606bede986772f1296f5babdd1"
FEATURES_SHA256 = "0e237a057a0613863569e6264694529860943eaa230a30ae61ed1a21899bd43c"
_HASH = re.compile(r"[a-f0-9]{64}\Z")
_DOCUMENT_ID = re.compile(r"ielts\.[a-z0-9_.-]{1,100}\Z")
_LOOKUP_PATH = re.compile(r"(?:[a-z][a-z0-9+.-]*://|(?:file|mailto|data):|(?:^|\s)(?:/|~[/\\]|\.\.?[/\\]|[a-z]:[/\\]))", re.I)
_TOKENS = re.compile(r"[a-z0-9]+|[\u3400-\u9fff]")
_CORPUS_CONSTRUCTION_TOKEN = object()

# Receipt byte identities, not hashes inferred from titles or publication years.
_INPUTS = {
    "style-blueprint": (BLUEPRINT_SHA256, 10735),
    "sample-style-features": (FEATURES_SHA256, 5319),
    "cambridge-preparation": ("72a81026b5e16ea9212de0eeff33532d9b40d286b6e3ff130d8a830e04962b86", 1725),
    "cambridge-samples": ("33b371e203a0c39ed8b45814362eecf35bffa322a208bc6b46e38733ffc81cad", 11459),
    "cambridge-academic-faqs": ("6c4db588aa3043b9c9284533e020a63e2860376e4139e4cdbcc5d7fecec3b452", 1183),
    "cambridge-test-taker-guide-bounded-15mb": ("6fa51a276bb6b69fa2a0e7b07d04f8721da8ca03661f16b2d2f07ec42143945c", 1389),
}
_SOURCES = {
    "cambridge-preparation": {
        "response_sha256": "3df64383c05405a4bdf3ba149ca0aa1b51d312b46ef0c4b5af13ae2645dc3a99",
        "official_url": "https://www.cambridgeenglish.org/exams-and-tests/ielts/preparation/",
        "retrieved_at_utc": "2026-11-08T06:18:13.114871+00:00",
    },
    "cambridge-samples": {
        "response_sha256": "586fa282be76d0a00a5ab36ca400868d81535d7d50d2e8ff905839ae377e399d",
        "official_url": "https://www.cambridgeenglish.org/Images/23435-ielts-sample-papers.zip",
        "retrieved_at_utc": "2026-11-08T06:18:17.096014+00:00",
    },
    "cambridge-academic-faqs": {
        "response_sha256": "bd8afa979356a34692a112cd8806fe5ce813fb08b00ac97e31941cb621879a67",
        "official_url": "https://www.cambridgeenglish.org/Images/269898-ielts-academic-faqs.pdf",
        "retrieved_at_utc": "2026-11-08T06:18:13.115445+00:00",
    },
    "cambridge-test-taker-guide-bounded-15mb": {
        "response_sha256": "d8dced401937bc6969617bc8e4afc106077a0e34d8032afe060fa7bfbdcd18d4",
        "official_url": "https://www.cambridgeenglish.org/Images/23436-ielts-guide-for-test-takers.pdf",
        "retrieved_at_utc": "2026-11-08T06:38:23.139995+00:00",
    },
}
_SAMPLE_MEMBERS = {
    "diagram_label_completion": ("Academic Reading/115012_Academic_Reading_sample_task_-_Diagram_label_completion__2_.pdf", "6f381ee14d77b4dd888492450187c11a1e50bfbdb73cf5e31eaa6841d5a19742", 3),
    "identifying_information": ("Academic Reading/115013_Academic_Reading_sample_task_-_Identifying_information__2_.pdf", "da98df2a77ee5dbe4df10314af2dc0576ead98106658e5ff703aa8bd78b274bd", 5),
    "identifying_writers_views": ("Academic Reading/115014_Academic_Reading_sample_task_-_Identifying_writer_s_views__2_.pdf", "d9a3d8545dd8e2896b2242e8319c6c5ffd5db1aac63e46ac7548c6b02aaf9e77", 4),
    "matching_features": ("Academic Reading/115015_Academic_Reading_sample_task_-_Matching_features__2_.pdf", "a4a4b5eade2c96e34df8c552042bc289385b6f79b6afeec2b74556a140bb0909", 3),
    "matching_headings": ("Academic Reading/115016_Academic_Reading_sample_task_-_Matching_headings__2_.pdf", "6c3607577908b01c7a701be44bb53542bbac3fc3af6132bac2237a8f187e5d42", 4),
    "multiple_choice": ("Academic Reading/115017_Academic_Reading_sample_task_-_Multiple_choice.pdf", "11057e7a1cfbf8b38794be0a6b5a88bc3acc780f5daed2aac2e7f26d38ac8473", 3),
    "table_completion": ("Academic Reading/115018_Academic_Reading_sample_task_-_Table_completion__2_.pdf", "a2e1e00fed9ba7249f4db1ea0f229993d20878d1b328319d9a22d2910e734b52", 3),
    "academic_task_1": ("Academic Writing/115027_Academic_Writing_sample_task_-_Task_1.pdf", "817c88291b893cd6d27d52931ebaa5611ea41f2e902b6fb1afa2519683f51e23", 1),
    "academic_task_2": ("Academic Writing/115028_Academic_Writing_sample_task_-_Task_2.pdf", "517ed812097338f32583a3ebe16d447cf9a85ea90409f4469ef2a1dcb1618f18", 1),
    "feedback_dimensions": ("Academic Writing/113313_AC_sample_scripts.pdf", "4d3b54c16ea4664c1b0189d58d2f361c2eeba87968ab58a03c7e606952c8d9b1", 12),
}


class CorpusError(ValueError):
    """Content-free error; do not include user input, local paths or source text."""

    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


def _fail(code: str, message: str) -> None:
    raise CorpusError(code, message)


def _json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                          allow_nan=False).encode("utf-8", errors="strict")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        _fail("invalid_json", "Value cannot be represented as canonical JSON")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(_json_bytes(value)).hexdigest()


def _pairs(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("duplicate_field", "Duplicate JSON fields are not accepted")
        result[key] = value
    return result


def _decode(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_pairs,
                          parse_constant=lambda _: _fail("invalid_json", "Nonfinite JSON is not accepted"))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        _fail("invalid_json", "Input must be bounded valid UTF-8 JSON")


def _read_bytes(path: Path, maximum: int = MAX_INDEX_BYTES) -> bytes:
    try:
        with path.open("rb") as stream:
            raw = stream.read(maximum + 1)
    except OSError:
        _fail("file_unavailable", "The host-configured reference file is unavailable")
    if len(raw) > maximum:
        _fail("file_bound", "The reference file exceeds the configured byte limit")
    return raw


def _keys(value: Any, required: set, optional: Optional[set] = None) -> dict:
    if not isinstance(value, dict) or any(not isinstance(k, str) for k in value):
        _fail("object_required", "Expected a JSON object")
    if not required <= set(value):
        _fail("missing_field", "A required field is missing")
    if not set(value) <= required | (optional or set()):
        _fail("unknown_field", "Undeclared fields are not accepted")
    return value


def _equal(actual: Any, expected: Any, code: str = "source_mismatch") -> None:
    if _json_bytes(actual) != _json_bytes(expected):
        _fail(code, "Value does not match the frozen reference snapshot")


def _coverage() -> dict:
    return {
        "scope": "official_public_samples_and_format_metadata",
        "requested_publication_years": [2024, 2025, 2026],
        "sample_publication_years_verified": False,
        "recent_three_year_metadata_complete": False,
        "recent_three_year_style_coverage": False,
        "cambridge_19_full_text_read": False,
        "cambridge_20_full_text_read": False,
        "cambridge_21_full_text_read": False,
        "full_books_indexed": False,
        "original_questions_or_model_answers_indexed": False,
        "publication_years_inferred": False,
        "source_assets_retained": False,
    }


def _source_ref(source_id: str, sample: Optional[str] = None) -> dict:
    result = dict(_SOURCES[source_id])
    result.update(source_id=source_id, receipt_sha256=_INPUTS[source_id][0],
                  sample_member=None, sample_pdf_sha256=None)
    if sample is not None:
        member, digest, _ = _SAMPLE_MEMBERS[sample]
        result.update(sample_member="ielts-sample-papers/" + member, sample_pdf_sha256=digest)
    return result


def _documents() -> List[dict]:
    """Curated abstractions only. No raw source excerpt or arbitrary JSON flattening."""
    documents = []

    def add(identifier: str, module: str, title: str, summary: str, facts: dict,
            source_ids: tuple, *, question_type: Optional[str] = None,
            sample: Optional[str] = None, scope: str = "official_public_sample_format") -> None:
        d = {
            "document_id": identifier, "module": module, "question_type": question_type,
            "title": title, "summary": summary, "facts": facts, "scope": scope,
            "content_kind": "self_authored_abstraction" if scope.startswith("official_") and scope != "official_metadata" else "metadata",
            "source_chain": {
                "blueprint_sha256": BLUEPRINT_SHA256,
                "feature_extract_sha256": FEATURES_SHA256 if sample else None,
                "source_receipts": [_source_ref(sid, sample if sid == "cambridge-samples" else None) for sid in source_ids],
            },
        }
        d["document_sha256"] = canonical_sha256(d)
        documents.append(d)

    add("ielts.format.academic_reading", "reading", "Academic Reading 阅读考试格式",
        "学术类完整阅读考试为3篇文章、40道题、60分钟。单篇外刊练习只覆盖局部阅读任务，不能直接换算为完整考试分数。",
        {"passages": 3, "questions": 40, "minutes": 60},
        ("cambridge-academic-faqs",), scope="official_test_format")
    add("ielts.format.academic_writing", "writing", "Academic Writing 写作考试格式",
        "学术类写作有2个任务，总时长60分钟。Task 1与Task 2承担不同任务，不能用同意程度作文代替图表信息写作。",
        {"tasks": 2, "minutes": 60}, ("cambridge-test-taker-guide-bounded-15mb",), scope="official_test_format")
    rules = {
        "identifying_information": (
            "Identifying information 信息判断 T/F/NG",
            "判断陈述与原文信息的关系，答案形式为True、False或Not Given。它针对文中信息，不等同于判断作者观点的Yes/No题。Not Given不能只靠关键词没有出现来认定。",
            {"response_mode": "three_way_choice", "answer_values": ["true", "false", "not_given"], "claim_target": "information_in_passage"}),
        "identifying_writers_views": (
            "Identifying writer’s views 作者观点 Y/N/NG",
            "判断陈述与作者观点或主张的关系，答案形式为Yes、No或Not Given。区分作者立场与文章陈述的事实，不把Yes/No和True/False当成可互换标签。",
            {"response_mode": "three_way_choice", "answer_values": ["yes", "no", "not_given"], "claim_target": "writers_view_or_claim"}),
        "multiple_choice": (
            "Multiple choice 选择题",
            "选择题使用明确列出的选项，并说明每组需要选择几个答案。此资料只记录所观察到的题型结构，不把所有选择题一概限定为四选一。",
            {"response_mode": "option_ids", "required_per_group": ["options", "number_of_answers"]}),
        "matching_features": (
            "Matching features 特征匹配",
            "特征匹配题把题目项目与列出的特征标识对应。题组须清楚列出特征，并说明选项是否可以重复使用。",
            {"response_mode": "feature_ids", "required_per_group": ["features", "allow_option_reuse"]}),
        "matching_headings": (
            "Matching headings 段落标题匹配",
            "标题匹配题为指定段落选择最合适的标题。题目结构包括标题列表、段落标识及选项是否可复用的说明。",
            {"response_mode": "heading_id_per_paragraph", "required_per_group": ["heading_list", "paragraph_ids", "allow_option_reuse"]}),
        "diagram_label_completion": (
            "Diagram label completion 图示标注",
            "图示标注题需要可供核对的图示和空位。回答方式可能是选项标识或短文本；若要求短文本，应明确本题的字数限制。这里没有图示素材，也不提供原题。",
            {"response_mode": "declared_per_group", "allowed_response_modes": ["option_ids", "short_text"],
             "required_per_group": ["diagram", "blank_ids", "answer_mode", "word_limit_if_short_text"]}),
        "table_completion": (
            "Table completion 表格填空",
            "表格填空以短文本填写表格空位，需要明确每组的词数与数字限制。实际查看的一个样例限3词，这不是所有填空题的统一限制；项目单空填空是适配形式，不能声称已取得对应独立官方样例。",
            {"response_mode": "short_text", "required_per_group": ["table_cells", "blank_ids", "max_words", "allow_numbers"],
             "observed_sample_max_words": 3, "sample_limit_is_not_universal": True}),
    }
    for kind in READING_TYPES:
        title, summary, facts = rules[kind]
        add("ielts.reading." + kind, "reading", title, summary, facts,
            ("cambridge-samples",), question_type=kind, sample=kind)
    add("ielts.writing.academic_task_1", "writing", "Academic Task 1 视觉信息报告",
        "所查看的Task 1样例要求报告给定视觉信息，至少150词，建议约20分钟。此索引只提供格式特征，没有可核验图表；不能据此直接生成或判分图表任务。",
        {"response_mode": "free_text", "minimum_words": 150, "suggested_minutes": 20},
        ("cambridge-samples",), question_type="academic_task_1", sample="academic_task_1")
    add("ielts.writing.academic_task_2", "writing", "Academic Task 2 同意程度 extent of agreement",
        "所查看的Task 2样例要求讨论一个议题并表达同意程度，至少250词，建议约40分钟。本次只观察到extent of agreement形式，没有核验双边讨论、利弊或问题解决等其他题型的样例。",
        {"response_mode": "free_text", "minimum_words": 250, "suggested_minutes": 40,
         "observed_sample_prompt_shapes": ["extent_of_agreement"]},
        ("cambridge-samples",), question_type="extent_of_agreement", sample="academic_task_2")
    add("ielts.writing.feedback_dimensions", "writing", "Writing feedback 写作评价维度",
        "官方样例作文评语出现任务完成或任务回应、连贯与衔接、词汇资源、语法范围与准确性等维度。这里只抽取维度名称，未收录完整Band评分标准，也不能证明AI预测分数已经校准。",
        {"task_1": ["task_achievement", "coherence_and_cohesion", "lexical_resource", "grammatical_range_and_accuracy"],
         "task_2": ["task_response", "coherence_and_cohesion", "lexical_resource", "grammatical_range_and_accuracy"],
         "complete_band_descriptors": False}, ("cambridge-samples",), sample="feedback_dimensions")
    for source_id in _SOURCES:
        facts = {"source_id": source_id, "publication_year": None, "publication_year_verified": False}
        summary = "已保存该官方来源的响应元数据及摘要值，未保存网页、PDF或ZIP全文；访问时间不等于出版时间。"
        if source_id == "cambridge-preparation":
            facts.update(observed_title="IELTS 21 Academic", isbn="9781009826723")
            summary += "备考页存在IELTS 21 Academic产品入口，但未核验该书出版年份或取得该书全文。"
        elif source_id == "cambridge-samples":
            facts.update(reading_samples_parsed=7, writing_task_samples_parsed=2, writing_commentary_documents_parsed=1)
            summary += "已解析7份Academic Reading任务样例、2份Writing任务样例和1份写作评语文档；它们不构成近三年真题库。"
        add("ielts.metadata." + source_id, "general", "Source metadata " + source_id, summary,
            facts, (source_id,), scope="official_metadata")
    add("ielts.coverage", "general", "Reference coverage 资料覆盖与年份边界",
        "这是一份公开样例格式与来源元数据索引。2024至2026年书目没有完整核验，剑雅19、20、21整本正文未取得，样例出版年份未知。不能将公开样例称为近三年真题或完整评分语料。",
        _coverage(), tuple(_SOURCES), scope="coverage_only")
    return sorted(documents, key=lambda d: d["document_id"])


def _artifact() -> dict:
    return {
        "schema": SCHEMA, "corpus_id": CORPUS_ID, "as_of": "2026-11-08",
        "research_manifest_sha256": MANIFEST_SHA256,
        "input_files": [{"input_id": key, "sha256": value[0], "bytes": value[1]} for key, value in sorted(_INPUTS.items())],
        "coverage": _coverage(), "documents": _documents(),
    }


def _verify_research(research_dir: Path) -> None:
    """Read only seven fixed basenames; never follow filenames found in a receipt."""
    raw = _read_bytes(research_dir / "final-manifest.json")
    _equal(hashlib.sha256(raw).hexdigest(), MANIFEST_SHA256, "manifest_digest")
    manifest = _decode(raw)
    entries = manifest["source_receipts_and_documents"]
    by_path = {entry["path"]: entry for entry in entries}
    if len(by_path) != len(entries):
        _fail("duplicate_id", "Research manifest repeats a file identity")
    inputs = {}
    for input_id, (digest, size) in _INPUTS.items():
        name = input_id + ".json"
        entry = by_path.get("docs/research/ielts-20261108/" + name)
        if entry is None:
            _fail("missing_source", "A fixed reference input is absent from the manifest")
        _equal(entry["sha256"], digest)
        _equal(entry["bytes"], size)
        raw = _read_bytes(research_dir / name)
        _equal(hashlib.sha256(raw).hexdigest(), digest, "input_digest")
        _equal(len(raw), size)
        inputs[input_id] = _decode(raw)
    b, features = inputs["style-blueprint"], inputs["sample-style-features"]
    for source_id, metadata in _SOURCES.items():
        source = inputs[source_id]
        _equal(source["id"], source_id)
        _equal(source["status"], "retrieved")
        _equal(source["status_code"], 200)
        for target, original in (("response_sha256", "response_sha256"), ("official_url", "final_url"), ("retrieved_at_utc", "retrieved_at_utc")):
            _equal(source[original], metadata[target])
        _equal(source["full_asset_saved"], False)
    bundle_hash = _SOURCES["cambridge-samples"]["response_sha256"]
    _equal(features["zip_sha256"], bundle_hash)
    _equal(b["coverage"]["example_text"]["bundle_sha256"], bundle_hash)
    _equal(inputs["cambridge-samples"]["crc_valid"], True)
    observed = {f["name"]: f for f in features["features"]}
    if len(observed) != len(features["features"]):
        _fail("duplicate_id", "Sample extraction repeats a member identity")
    _equal(len(observed), len(_SAMPLE_MEMBERS))
    reading = {v["id"]: v for v in b["reading"]["types"]}
    writing = {v["id"]: v for v in b["writing"]["tasks"]}
    _equal(sorted(reading), sorted(READING_TYPES))
    for kind, (member, digest, pages) in _SAMPLE_MEMBERS.items():
        feature = observed.get("ielts-sample-papers/" + member)
        if feature is None:
            _fail("missing_source", "An inspected sample is missing")
        _equal(feature["pdf_sha256"], digest)
        _equal(feature["pages"], pages)
        rule = reading.get(kind) or writing.get(kind) or b["writing"]["feedback_dimensions"]
        _equal(rule["source"], {"source_id": "cambridge-samples", "member": feature["name"], "sha256": digest})
    # Every fact used by the curated summaries is checked against the frozen
    # blueprint; implementation requirements are not flattened into the index.
    for d in _documents():
        key = d["document_id"].rsplit(".", 1)[-1]
        if d["document_id"].startswith("ielts.reading."):
            for field, value in d["facts"].items():
                _equal(reading[key][field], value)
        elif key in writing:
            for field, value in d["facts"].items():
                _equal(writing[key][field], value)
        elif key == "feedback_dimensions":
            for field in ("task_1", "task_2"):
                _equal(b["writing"]["feedback_dimensions"][field], d["facts"][field])
    _equal({k: b["format"]["full_academic_reading"][k] for k in ("passages", "questions", "minutes")}, {"passages": 3, "questions": 40, "minutes": 60})
    _equal({k: b["format"]["academic_writing"][k] for k in ("tasks", "minutes")}, {"tasks": 2, "minutes": 60})
    _equal(b["coverage"]["bibliography"]["recent_three_year_metadata_complete"], False)
    for field in ("sample_publication_years_verified", "cambridge_19_full_text_read", "cambridge_20_full_text_read", "cambridge_21_full_text_read", "recent_three_year_style_coverage"):
        _equal(b["coverage"]["example_text"][field], False)


def build(output_path: Any, *, research_dir: Any = DEFAULT_RESEARCH_DIR) -> Dict[str, Any]:
    """Build one frozen corpus. Host-only paths; no overwrite of different data."""
    _verify_research(Path(research_dir))
    raw = _json_bytes(_artifact()) + b"\n"
    if len(raw) > MAX_INDEX_BYTES:
        _fail("file_bound", "The curated corpus exceeds its byte limit")
    output = Path(output_path)
    created = False
    try:
        if output.is_symlink():
            _fail("output_exists", "The output must be a regular host-owned corpus file")
        try:
            fd = os.open(str(output), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            if _read_bytes(output) != raw:
                _fail("output_exists", "The host-configured output already contains different data")
        else:
            created = True
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
            except BaseException:
                output.unlink(missing_ok=True)
                raise
    except OSError:
        _fail("output_unavailable", "The host-configured output cannot be written")
    return {"schema": "lexicon.ielts.corpus.build.v1", "status": "ready", "created": created,
            "corpus_id": CORPUS_ID, "corpus_sha256": hashlib.sha256(raw).hexdigest(),
            "document_count": len(_documents()), "bytes": len(raw), "coverage": _coverage(),
            "research_manifest_sha256": MANIFEST_SHA256, "network_requests": 0}


class Corpus:
    """Immutable, validated bytes; APIs return fresh copies and never mutate it."""
    __slots__ = ("_raw", "_sha256")

    def __init__(self, raw: bytes, digest: str, *, _token: Any = None):
        if _token is not _CORPUS_CONSTRUCTION_TOKEN:
            _fail("corpus_required", "Use load to validate a host-pinned corpus")
        object.__setattr__(self, "_raw", raw)
        object.__setattr__(self, "_sha256", digest)

    def __setattr__(self, name: str, value: Any) -> None:
        raise AttributeError("Corpus is immutable")

    @property
    def sha256(self) -> str:
        return self._sha256

    def _data(self) -> dict:
        return _decode(self._raw)


def load(index_path: Any, *, expected_sha256: str) -> Corpus:
    """Load the host-pinned artifact; never trust a digest supplied by a model."""
    if not isinstance(expected_sha256, str) or _HASH.fullmatch(expected_sha256) is None:
        _fail("invalid_hash", "A host-pinned lowercase SHA-256 digest is required")
    raw = _read_bytes(Path(index_path))
    digest = hashlib.sha256(raw).hexdigest()
    _equal(digest, expected_sha256, "corpus_digest")
    data = _decode(raw)
    _keys(data, {"schema", "corpus_id", "as_of", "research_manifest_sha256", "input_files", "coverage", "documents"})
    if not isinstance(data["documents"], list) or len(data["documents"]) != len(_documents()):
        _fail("document_bound", "The corpus must contain exactly the frozen document set")
    ids = []
    for document in data["documents"]:
        _keys(document, {"document_id", "module", "question_type", "title", "summary", "facts", "scope", "content_kind", "source_chain", "document_sha256"})
        ids.append(document["document_id"])
        _equal(document["document_sha256"], canonical_sha256({k: v for k, v in document.items() if k != "document_sha256"}), "document_digest")
    if any(not isinstance(identifier, str) for identifier in ids):
        _fail("invalid_id", "Document identifiers must be strings")
    if len(set(ids)) != len(ids):
        _fail("duplicate_id", "Document identifiers must be unique")
    # Requiring the exact curated artifact also rejects polluted nested fields,
    # changed summaries and exaggerated coverage even if someone re-hashes it.
    _equal(data, _artifact(), "corpus_content")
    return Corpus(raw, digest, _token=_CORPUS_CONSTRUCTION_TOKEN)


def _corpus(corpus: Corpus) -> Corpus:
    if not isinstance(corpus, Corpus):
        _fail("corpus_required", "Load a host-pinned corpus before retrieval")
    return corpus


def _base(corpus: Corpus, operation: str) -> dict:
    return {"schema": "lexicon.ielts.corpus." + operation + ".v1", "corpus_id": CORPUS_ID,
            "corpus_sha256": _corpus(corpus).sha256, "coverage": _coverage()}


def _query(value: Any) -> str:
    if not isinstance(value, str):
        _fail("query_required", "Search query must be text")
    try:
        units = len(value.encode("utf-16-le", errors="strict")) // 2
    except UnicodeError:
        _fail("invalid_unicode", "Unpaired surrogate characters are not accepted")
    if units > MAX_QUERY_UNITS:
        _fail("query_bound", "Search query exceeds the UTF-16 limit")
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        _fail("control_character", "Search query contains a control character")
    normalized = unicodedata.normalize("NFKC", value).casefold().strip()
    if _LOOKUP_PATH.search(normalized):
        _fail("lookup_target", "Retrieve accepts search terms, not URLs or file paths")
    return normalized


def search(corpus: Corpus, query: str = "", *, module: Optional[str] = None,
           question_type: Optional[str] = None, limit: int = 6) -> Dict[str, Any]:
    """Deterministic all-term matching; integer ranking is not semantic confidence."""
    corpus = _corpus(corpus)
    normalized = _query(query)
    if module is not None and module not in MODULES:
        _fail("unsupported_module", "The requested module is not indexed")
    if question_type is not None and question_type not in QUESTION_TYPES:
        _fail("unsupported_type", "The requested question type was not indexed")
    if type(limit) is not int or not 1 <= limit <= MAX_RESULTS:
        _fail("limit_bound", "Result limit must be an integer from 1 through 8")
    terms = list(dict.fromkeys(_TOKENS.findall(normalized)))
    if normalized and not terms:
        _fail("query_terms", "Search query must contain searchable terms")
    matches = []
    for document in corpus._data()["documents"]:
        if module is not None and document["module"] != module:
            continue
        if question_type is not None and document["question_type"] != question_type:
            continue
        title = unicodedata.normalize("NFKC", document["title"]).casefold()
        text = title + " " + document["summary"].casefold() + " " + document["document_id"] + " " + document["module"]
        if all(term in text for term in terms):
            score = sum(3 if term in title else 1 for term in terms)
            item = {key: document[key] for key in ("document_id", "module", "question_type", "title", "summary", "scope", "document_sha256")}
            item["rank_score"] = score
            matches.append(item)
    matches.sort(key=lambda d: (-d["rank_score"], d["document_id"]))
    response = _base(corpus, "search")
    response.update(status="ok", results=matches[:limit], matched_count=len(matches), returned_count=min(len(matches), limit),
                    truncated=len(matches) > limit, ranking="all_terms_title_weight_then_document_id")
    return response


def read(corpus: Corpus, document_id: str) -> Dict[str, Any]:
    """Return one bounded curated document by fixed ID; there is no path fallback."""
    corpus = _corpus(corpus)
    if not isinstance(document_id, str) or _DOCUMENT_ID.fullmatch(document_id) is None:
        _fail("invalid_id", "Read requires a fixed corpus document identifier")
    for document in corpus._data()["documents"]:
        if document["document_id"] == document_id:
            response = _base(corpus, "read")
            response.update(status="ok", document=document)
            return response
    _fail("document_not_found", "No document with that fixed identifier is indexed")


def style_sources(corpus: Corpus, document_ids: List[str]) -> List[dict]:
    """Map selected style documents to ielts_contract request.style_sources.

    source_id is the fixed document ID. sha256 identifies the whole curated
    document, not just its summary or the original PDF. Retain the read receipt
    to trace that document to the corpus, input files and official response hash.
    """
    if not isinstance(document_ids, list) or not 1 <= len(document_ids) <= MAX_STYLE_SOURCES:
        _fail("source_bound", "Select between 1 and 12 style documents")
    results, seen = [], set()
    for identifier in document_ids:
        document = read(corpus, identifier)["document"]
        if identifier in seen:
            _fail("duplicate_id", "Style document IDs must not repeat")
        seen.add(identifier)
        if document["scope"] not in ("official_public_sample_format", "official_test_format"):
            _fail("source_scope", "Coverage and source metadata are not generation style sources")
        results.append({"source_id": identifier, "sha256": document["document_sha256"],
                        "summary": document["summary"], "scope": document["scope"]})
    return results


def style_source_bundle(corpus: Corpus, document_ids: List[str]) -> Dict[str, Any]:
    response = _base(corpus, "style_sources")
    response.update(status="ok", style_blueprint_sha256=BLUEPRINT_SHA256,
                    style_sources=style_sources(corpus, document_ids))
    return response


def handle_tool_request(corpus: Corpus, request: Any) -> Dict[str, Any]:
    """Only two read-only tool shapes. Unknown tools/arguments fail closed."""
    r = _keys(request, {"tool", "arguments"})
    if len(_json_bytes(r)) > MAX_TOOL_BYTES:
        _fail("request_bound", "Tool request exceeds its byte limit")
    if r["tool"] == "retrieve":
        args = _keys(r["arguments"], set(), {"query", "module", "question_type", "limit"})
        return search(corpus, **args)
    if r["tool"] == "read":
        args = _keys(r["arguments"], {"document_id"})
        return read(corpus, args["document_id"])
    _fail("unsupported_tool", "Only retrieve and read are provided")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    builder = sub.add_parser("build")
    builder.add_argument("--output", required=True)
    builder.add_argument("--research-dir", default=str(DEFAULT_RESEARCH_DIR))
    for name in ("search", "read", "tool", "style-sources"):
        p = sub.add_parser(name)
        p.add_argument("--corpus", required=True, help="Host configuration, never a model tool field")
        p.add_argument("--sha256", required=True, help="Host-pinned corpus byte SHA-256")
        if name == "search":
            p.add_argument("--query", default="")
            p.add_argument("--module", choices=MODULES)
            p.add_argument("--question-type", choices=QUESTION_TYPES)
            p.add_argument("--limit", type=int, default=6)
        elif name == "read":
            p.add_argument("--document-id", required=True)
        elif name == "style-sources":
            p.add_argument("--document-id", action="append", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            result = build(args.output, research_dir=args.research_dir)
        else:
            corpus = load(args.corpus, expected_sha256=args.sha256)
            if args.command == "search":
                result = search(corpus, args.query, module=args.module, question_type=args.question_type, limit=args.limit)
            elif args.command == "read":
                result = read(corpus, args.document_id)
            elif args.command == "style-sources":
                result = style_source_bundle(corpus, args.document_id)
            else:
                raw = sys.stdin.buffer.read(MAX_TOOL_BYTES + 1)
                if len(raw) > MAX_TOOL_BYTES:
                    _fail("request_bound", "Tool request exceeds its byte limit")
                result = handle_tool_request(corpus, _decode(raw))
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, allow_nan=False))
        return 0
    except CorpusError as error:
        print(json.dumps({"schema": "lexicon.ielts.corpus.error.v1", "status": "error",
                          "error": {"code": error.code, "message": str(error)}}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
