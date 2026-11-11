#!/usr/bin/env python3
"""Convert an explicitly supplied Longman 6 export into a local Lexicon package.

This tool contains no dictionary corpus and does not download data. The caller
must supply their own UTF-8 JSON array, JSONL, or TSV file and source provenance.
Only the Python standard library (with SQLite FTS5 support) is required.
"""

import argparse
import csv
import hashlib
import io
import json
import os
import pathlib
import re
import sqlite3
import sys
import tempfile
import unicodedata
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Union


FORMAT_VERSION = "1"
SUPPORTED_FIELDS = {"headword", "definition", "phonetic", "forms", "tags", "frequency"}
SCHEMA = """
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE entries(
    word TEXT PRIMARY KEY NOT NULL,
    normalized TEXT NOT NULL UNIQUE,
    translation TEXT NOT NULL DEFAULT '' CHECK(translation=''),
    definition TEXT NOT NULL CHECK(length(trim(definition))>0),
    phonetic TEXT NOT NULL,
    tags TEXT NOT NULL,
    frequency INTEGER
);
CREATE TABLE forms(
    form TEXT NOT NULL,
    lemma TEXT NOT NULL REFERENCES entries(normalized),
    PRIMARY KEY(form,lemma)
);
CREATE VIRTUAL TABLE entries_fts USING fts5(
    word, definition, translation, content='entries', content_rowid='rowid'
);
"""


class ConversionError(ValueError):
    """Invalid input or output contract; existing outputs are kept intact."""


def normalize_word(value: str) -> str:
    """Match Android's NFKC + Locale.ROOT lowercase + whitespace normalization."""
    return " ".join(unicodedata.normalize("NFKC", value).lower().split())


def _plain_string(value, name: str, location: str, required: bool = False) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise ConversionError(f"{location}: {name} must be a string")
    result = value.strip()
    if required and not result:
        raise ConversionError(f"{location}: {name} must not be empty")
    if any(unicodedata.category(c) == "Cc" and c not in "\n\r\t" for c in result):
        raise ConversionError(f"{location}: {name} contains a control character")
    return result


def _is_cjk(character: str) -> bool:
    point = ord(character)
    return (
        0x3400 <= point <= 0x4DBF or 0x4E00 <= point <= 0x9FFF
        or 0xF900 <= point <= 0xFAFF or 0x20000 <= point <= 0x323AF
        or 0x3040 <= point <= 0x30FF or 0xAC00 <= point <= 0xD7AF
    )


def _definitions(value, location: str) -> List[str]:
    values = value if isinstance(value, list) else [value]
    if not values:
        raise ConversionError(f"{location}: definition must not be empty")
    result = []
    for item in values:
        text = _plain_string(item, "definition", location, required=True)
        # This rejects absent/translational definitions, not a linguistic or
        # edition-authenticity certification. See docs/LONGMAN.md.
        if not re.search(r"[A-Za-z]", text) or any(_is_cjk(c) for c in text):
            raise ConversionError(f"{location}: definition must be English text, not a translation")
        result.append(text)
    return result


def _string_list(value, name: str, location: str) -> List[str]:
    if value is None or value == "":
        return []
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith("["):
            try:
                value = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ConversionError(f"{location}: invalid JSON array in {name}") from exc
        else:
            value = stripped.split("|")
    if not isinstance(value, list):
        raise ConversionError(f"{location}: {name} must be a string or list of strings")
    return [_plain_string(item, name, location, required=True) for item in value]


def _frequency(value, location: str) -> Optional[int]:
    if value is None or value == "":
        return None
    if isinstance(value, str) and re.fullmatch(r"[+]?[0-9]+", value.strip()):
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ConversionError(f"{location}: frequency must be a nonnegative integer or empty")
    if value > 2_147_483_647:
        raise ConversionError(f"{location}: frequency exceeds the app's 32-bit integer range")
    return value


def _parse_records(text: str, input_format: str) -> Iterable[tuple]:
    if input_format == "json":
        try:
            rows = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ConversionError(f"Invalid JSON at line {exc.lineno}, column {exc.colno}") from exc
        if not isinstance(rows, list):
            raise ConversionError("JSON input must be an array of entry objects")
        for index, row in enumerate(rows, 1):
            yield f"entry {index}", row
    elif input_format == "jsonl":
        for number, line in enumerate(text.splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ConversionError(f"Invalid JSONL at line {number}, column {exc.colno}") from exc
            yield f"line {number}", row
    elif input_format == "tsv":
        # Newlines and tabs inside a field are supported through standard CSV quoting.
        csv.field_size_limit(max(csv.field_size_limit(), len(text)))
        reader = csv.DictReader(io.StringIO(text, newline=""), delimiter="\t", strict=True)
        if not reader.fieldnames or not {"headword", "definition"}.issubset(reader.fieldnames):
            raise ConversionError("TSV header must include headword and definition")
        if len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise ConversionError("TSV contains duplicate column names")
        try:
            for row in reader:
                if None in row:
                    raise ConversionError(f"line {reader.line_num}: too many TSV fields")
                yield f"line {reader.line_num}", row
        except csv.Error as exc:
            raise ConversionError(f"Invalid TSV near line {reader.line_num}: {exc}") from exc
    else:
        raise ConversionError(f"Unsupported input format: {input_format}")


def _detect_format(path: pathlib.Path, text: str, requested: str) -> str:
    if requested != "auto":
        if requested not in {"json", "jsonl", "tsv"}:
            raise ConversionError(f"Unsupported input format: {requested}")
        return requested
    suffix = path.suffix.lower()
    if suffix in {".jsonl", ".ndjson"}:
        return "jsonl"
    if suffix == ".json":
        return "json"
    if suffix == ".tsv":
        return "tsv"
    if suffix in {".mdx", ".mdd"}:
        raise ConversionError("MDX/MDD is not supported; supply a UTF-8 JSON, JSONL or TSV export")
    start = text.lstrip()
    if start.startswith("["):
        return "json"
    if start.startswith("{"):
        return "jsonl"
    if "\t" in start.partition("\n")[0]:
        return "tsv"
    raise ConversionError("Cannot detect input format; use --format json, jsonl or tsv")


@dataclass
class _Entry:
    word: str
    normalized: str
    definitions: Dict[str, None] = field(default_factory=dict)
    phonetics: Dict[str, None] = field(default_factory=dict)
    forms: Dict[str, None] = field(default_factory=dict)
    tags: Dict[str, None] = field(default_factory=dict)
    frequency: Optional[int] = None


def _collect(records: Iterable[tuple]) -> tuple:
    entries: Dict[str, _Entry] = {}
    count = 0
    for location, row in records:
        if not isinstance(row, dict):
            raise ConversionError(f"{location}: entry must be an object")
        unknown = set(row) - SUPPORTED_FIELDS
        if unknown:
            raise ConversionError(f"{location}: unsupported fields: {', '.join(sorted(unknown))}; map them explicitly before conversion")
        word = _plain_string(row.get("headword"), "headword", location, required=True)
        word = " ".join(unicodedata.normalize("NFKC", word).split())
        canonical = normalize_word(word)
        if not re.search(r"[A-Za-z]", canonical) or len(canonical) > 200:
            raise ConversionError(f"{location}: headword must contain English letters and be at most 200 characters")
        definitions = _definitions(row.get("definition"), location)
        phonetic = _plain_string(row.get("phonetic"), "phonetic", location)
        forms = _string_list(row.get("forms"), "forms", location)
        tags = _string_list(row.get("tags"), "tags", location)
        frequency = _frequency(row.get("frequency"), location)
        entry = entries.setdefault(canonical, _Entry(word=word, normalized=canonical))
        for sense in definitions:
            entry.definitions[sense] = None
        if phonetic:
            entry.phonetics[phonetic] = None
        for form in [canonical] + forms:
            normalized_form = normalize_word(form)
            if not normalized_form or len(normalized_form) > 200:
                raise ConversionError(f"{location}: forms must be nonempty and at most 200 characters")
            entry.forms[normalized_form] = None
        for tag in tags:
            entry.tags[tag] = None
        if frequency is not None:
            if entry.frequency is not None and entry.frequency != frequency:
                raise ConversionError(f"{location}: conflicting frequency values for duplicate headword {word!r}")
            entry.frequency = frequency
        count += 1
    if not entries:
        raise ConversionError("Input contains no dictionary entries")
    return entries, count


def prepare_dictionary(
    source: Union[str, pathlib.Path], destination: Union[str, pathlib.Path], *,
    source_name: str, provenance: str, input_format: str = "auto", overwrite: bool = False,
) -> dict:
    """Validate and build a SQLite package; never replace a working output on failure."""
    source = pathlib.Path(source).expanduser()
    destination = pathlib.Path(destination).expanduser()
    if not source.is_file():
        raise ConversionError(f"Input file does not exist: {source}")
    if source.resolve() == destination.resolve():
        raise ConversionError("Input and output must be different files, even with --overwrite")
    if os.path.lexists(destination) and not overwrite:
        raise ConversionError(f"Output already exists: {destination}; use --overwrite to replace it")
    source_name = _plain_string(source_name, "source_name", "metadata", required=True)
    provenance = _plain_string(provenance, "source_provenance", "metadata", required=True)
    raw = source.read_bytes()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ConversionError("Input must be valid UTF-8; binary MDX/MDD files are not supported") from exc
    detected = _detect_format(source, text, input_format)
    entries, source_rows = _collect(_parse_records(text, detected))
    total_senses = sum(len(entry.definitions) for entry in entries.values())
    total_forms = sum(len(entry.forms) for entry in entries.values())
    metadata = {
        "dictionary_id": "longman-6", "format_version": FORMAT_VERSION, "language": "en-en",
        "source_name": source_name, "source_provenance": provenance,
        "source_sha256": hashlib.sha256(raw).hexdigest(), "source_format": detected,
        "source_bytes": str(len(raw)), "source_record_count": str(source_rows),
        "entry_count": str(len(entries)), "sense_count": str(total_senses), "form_count": str(total_forms),
        "converter": "Lexicon IDE prepare_longman.py v1",
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".building", dir=destination.parent)
    os.close(handle)
    temporary = pathlib.Path(temporary_name)
    connection = None
    try:
        connection = sqlite3.connect(temporary)
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA journal_mode=DELETE")
        try:
            connection.executescript(SCHEMA)
        except sqlite3.OperationalError as exc:
            if "fts5" in str(exc).lower():
                raise ConversionError("This Python SQLite build lacks FTS5; use a Python build with SQLite FTS5 enabled") from exc
            raise
        connection.executemany("INSERT INTO metadata(key,value) VALUES(?,?)", metadata.items())
        for entry in entries.values():
            senses = list(entry.definitions)
            definition = senses[0] if len(senses) == 1 else "\n\n".join(f"{index}. {sense}" for index, sense in enumerate(senses, 1))
            connection.execute("INSERT INTO entries(word,normalized,translation,definition,phonetic,tags,frequency) VALUES(?,?,?,?,?,?,?)",
                               (entry.word, entry.normalized, "", definition, "\n".join(entry.phonetics), " | ".join(entry.tags), entry.frequency))
            connection.executemany("INSERT INTO forms(form,lemma) VALUES(?,?)", ((form, entry.normalized) for form in entry.forms))
        connection.execute("INSERT INTO entries_fts(entries_fts) VALUES('rebuild')")
        connection.execute("INSERT INTO entries_fts(entries_fts) VALUES('optimize')")
        # rank=1 additionally compares external-content rows with their indexed token content.
        connection.execute("INSERT INTO entries_fts(entries_fts,rank) VALUES('integrity-check',1)")
        connection.commit()
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ConversionError("Generated SQLite failed integrity_check")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise ConversionError("Generated forms contain an unknown lemma")
        connection.execute("VACUUM")
        connection.close()
        connection = None
        with temporary.open("rb") as completed:
            os.fsync(completed.fileno())
        if overwrite:
            os.replace(temporary, destination)
        else:
            # Publishing with a hard link is atomic and fails if a concurrent writer
            # created the output; os.replace alone would silently overwrite it.
            try:
                os.link(temporary, destination)
            except FileExistsError as exc:
                raise ConversionError(f"Output appeared during conversion and was not replaced: {destination}") from exc
            temporary.unlink()
        result = dict(metadata)
        result.update({"output": str(destination), "output_bytes": destination.stat().st_size})
        return result
    finally:
        if connection is not None:
            connection.close()
        temporary.unlink(missing_ok=True)
        pathlib.Path(str(temporary) + "-journal").unlink(missing_ok=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", required=True, type=pathlib.Path, help="User-supplied UTF-8 JSON, JSONL or TSV export")
    parser.add_argument("--output", required=True, type=pathlib.Path, help="Output .sqlite package for import in Lexicon IDE")
    parser.add_argument("--format", choices=("auto", "json", "jsonl", "tsv"), default="auto")
    parser.add_argument("--source-name", required=True, help="Human-readable name of the supplied export")
    parser.add_argument("--provenance", required=True, help="User-provided source/edition provenance; not independently certified by this converter")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing output only after successful conversion")
    arguments = parser.parse_args(argv)
    try:
        result = prepare_dictionary(arguments.input, arguments.output, source_name=arguments.source_name,
                                    provenance=arguments.provenance, input_format=arguments.format, overwrite=arguments.overwrite)
    except (ConversionError, OSError, sqlite3.DatabaseError) as exc:
        print(f"Conversion failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
