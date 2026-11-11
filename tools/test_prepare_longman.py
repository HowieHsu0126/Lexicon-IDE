#!/usr/bin/env python3
"""Conversion-contract tests. ALL entries below are synthetic, not Longman data."""

import hashlib
import json
import pathlib
import sqlite3
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from prepare_longman import ConversionError, normalize_word, prepare_dictionary


SYNTHETIC_PROVENANCE = "Synthetic converter test fixtures; not a Longman dictionary or extract."


class PrepareLongmanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lexicon-dictionary-test-")
        self.folder = pathlib.Path(self.temp.name)
        self.output = self.folder / "converted.sqlite"

    def tearDown(self):
        self.temp.cleanup()

    def write_json(self, rows):
        source = self.folder / "synthetic.json"
        source.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        return source

    def convert(self, source, **kwargs):
        return prepare_dictionary(source, self.output, source_name="Synthetic format test", provenance=SYNTHETIC_PROVENANCE, **kwargs)

    def synthetic(self, **updates):
        row = {"headword": "testword", "definition": "A made-up label used only to test this converter.", "forms": ["testwords"], "phonetic": "/test/"}
        row.update(updates)
        return row

    def test_json_builds_en_en_schema_and_provenance(self):
        source = self.write_json([self.synthetic()])
        summary = self.convert(source)
        with sqlite3.connect(self.output) as db:
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            self.assertEqual("longman-6", metadata["dictionary_id"])
            self.assertEqual("1", metadata["format_version"])
            self.assertEqual("en-en", metadata["language"])
            self.assertEqual(SYNTHETIC_PROVENANCE, metadata["source_provenance"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), metadata["source_sha256"])
            self.assertEqual("", db.execute("SELECT translation FROM entries").fetchone()[0])
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            self.assertEqual([("testword",)], db.execute("SELECT lemma FROM forms WHERE form='testwords'").fetchall())
        self.assertEqual("1", summary["entry_count"])

    def test_duplicates_merge_all_distinct_senses_and_variants(self):
        first = self.synthetic(headword="ＴＥＳＴＷＯＲＤ", tags=["synthetic", "sense-one"])
        second = self.synthetic(headword="testword", definition="A second invented meaning used for merge testing.", phonetic="/alternate/", forms=["TESTWORDS", "testwording"], tags=["sense-two"])
        self.convert(self.write_json([first, second, second]))
        with sqlite3.connect(self.output) as db:
            row = db.execute("SELECT normalized,definition,phonetic,tags FROM entries").fetchone()
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM entries").fetchone()[0])
            self.assertEqual("testword", row[0])
            self.assertIn(first["definition"], row[1])
            self.assertEqual(1, row[1].count(second["definition"]))
            self.assertIn("/alternate/", row[2])
            self.assertIn("sense-one", row[3])
            self.assertIn("sense-two", row[3])
            self.assertEqual({"testword", "testwords", "testwording"}, {r[0] for r in db.execute("SELECT form FROM forms")})

    def test_one_form_can_reference_multiple_lemmas(self):
        rows = [self.synthetic(headword="testone", forms=["sharedform"]), self.synthetic(headword="testtwo", forms=["sharedform"])]
        self.convert(self.write_json(rows))
        with sqlite3.connect(self.output) as db:
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM forms WHERE form='sharedform'").fetchone()[0])

    def test_fts_rows_match_external_content_and_search_both_fields(self):
        rows = [self.synthetic(headword="zetaexample"), self.synthetic(headword="alphaexample", definition="A synthetic description containing verificationtoken.")]
        self.convert(self.write_json(rows))
        with sqlite3.connect(self.output) as db:
            actual = db.execute("SELECT e.word FROM entries_fts f JOIN entries e ON e.rowid=f.rowid WHERE entries_fts MATCH ?", ('definition : "verificationtoken"',)).fetchall()
            self.assertEqual([("alphaexample",)], actual)
            self.assertEqual([("zetaexample",)], db.execute("SELECT word FROM entries_fts WHERE entries_fts MATCH ?", ('word : "zeta"*',)).fetchall())
            db.execute("INSERT INTO entries_fts(entries_fts,rank) VALUES('integrity-check',1)")

    def test_jsonl_blank_lines_and_utf8_bom(self):
        source = self.folder / "synthetic.jsonl"
        source.write_bytes(b"\xef\xbb\xbf" + (json.dumps(self.synthetic()) + "\n\n" + json.dumps(self.synthetic(headword="otherword")) + "\n").encode())
        self.assertEqual("2", self.convert(source)["entry_count"])

    def test_tsv_handles_quoted_newlines_and_forms_array(self):
        import csv
        source = self.folder / "synthetic.tsv"
        with source.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=["headword", "definition", "phonetic", "forms", "frequency"], delimiter="\t")
            writer.writeheader()
            writer.writerow({"headword": "testword", "definition": "A synthetic first line.\nA synthetic second line.", "phonetic": "/test/", "forms": '["testwords", "testwording"]', "frequency": "12"})
        self.convert(source)
        with sqlite3.connect(self.output) as db:
            self.assertEqual(12, db.execute("SELECT frequency FROM entries").fetchone()[0])
            self.assertIn("\n", db.execute("SELECT definition FROM entries").fetchone()[0])
            self.assertEqual(3, db.execute("SELECT COUNT(*) FROM forms").fetchone()[0])

    def test_definitions_array_and_pipe_forms(self):
        row = self.synthetic(definition=["A synthetic first sense.", "A synthetic second sense."], forms="testwords| testwording")
        summary = self.convert(self.write_json([row]))
        self.assertEqual("2", summary["sense_count"])
        self.assertEqual("3", summary["form_count"])

    def test_normalization_matches_app_including_unicode_spaces(self):
        self.assertEqual("test word", normalize_word("  ＴＥＳＴ\u00a0 WORD  "))
        row = self.synthetic(headword="Test Word", forms=["ＴＥＳＴ\u00a0ＷＯＲＤＳ"])
        self.convert(self.write_json([row]))
        with sqlite3.connect(self.output) as db:
            self.assertEqual([("test word",)], db.execute("SELECT lemma FROM forms WHERE form='test words'").fetchall())

    def test_invalid_definitions_fail_without_creating_output(self):
        for definition in ["", "  ", None, [], "中文释义", "English words 和中文", 12, "12345"]:
            with self.subTest(definition=definition):
                source = self.write_json([self.synthetic(definition=definition)])
                with self.assertRaises(ConversionError):
                    self.convert(source)
                self.assertFalse(self.output.exists())

    def test_unknown_fields_and_conflicting_frequency_are_not_silently_discarded(self):
        bad = self.synthetic(translation="A non-supported extra field.")
        with self.assertRaisesRegex(ConversionError, "unsupported fields"):
            self.convert(self.write_json([bad]))
        rows = [self.synthetic(frequency=1), self.synthetic(frequency=2)]
        with self.assertRaisesRegex(ConversionError, "conflicting frequency"):
            self.convert(self.write_json(rows))

    def test_existing_output_requires_flag_and_survives_invalid_replacement(self):
        self.output.write_bytes(b"original output remains intact")
        original = self.output.read_bytes()
        valid = self.write_json([self.synthetic()])
        with self.assertRaisesRegex(ConversionError, "already exists"):
            self.convert(valid)
        self.assertEqual(original, self.output.read_bytes())
        invalid = self.write_json([self.synthetic(definition="")])
        with self.assertRaises(ConversionError):
            self.convert(invalid, overwrite=True)
        self.assertEqual(original, self.output.read_bytes())
        self.assertEqual([], list(self.folder.glob("*.building")))
        self.convert(self.write_json([self.synthetic()]), overwrite=True)
        self.assertEqual(b"SQLite format 3\0", self.output.read_bytes()[:16])

    def test_never_overwrites_source_even_with_flag(self):
        source = self.write_json([self.synthetic()])
        before = source.read_bytes()
        with self.assertRaisesRegex(ConversionError, "different files"):
            prepare_dictionary(source, source, source_name="Synthetic", provenance=SYNTHETIC_PROVENANCE, overwrite=True)
        self.assertEqual(before, source.read_bytes())

    def test_no_entries_or_missing_provenance_is_rejected(self):
        with self.assertRaisesRegex(ConversionError, "no dictionary entries"):
            self.convert(self.write_json([]))
        source = self.write_json([self.synthetic()])
        with self.assertRaisesRegex(ConversionError, "source_provenance"):
            prepare_dictionary(source, self.output, source_name="Synthetic", provenance="")

    def test_binary_and_mdx_inputs_report_supported_format(self):
        source = self.folder / "synthetic.mdx"
        source.write_bytes(b"\xff\xfe\x00\x01")
        with self.assertRaisesRegex(ConversionError, "UTF-8"):
            self.convert(source)
        source.write_text("Not an actual MDX file", encoding="utf-8")
        with self.assertRaisesRegex(ConversionError, "MDX/MDD"):
            self.convert(source)

    def test_cli_receipt_and_failure_exit_status(self):
        source = self.write_json([self.synthetic()])
        tool = pathlib.Path(__file__).with_name("prepare_longman.py")
        command = [sys.executable, str(tool), "--input", str(source), "--output", str(self.output), "--source-name", "Synthetic test", "--provenance", SYNTHETIC_PROVENANCE]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("en-en", json.loads(result.stdout)["language"])
        failed = subprocess.run(command, text=True, capture_output=True, check=False)
        self.assertEqual(1, failed.returncode)
        self.assertIn("already exists", failed.stderr)
        self.assertEqual("", failed.stdout)


if __name__ == "__main__":
    unittest.main()
