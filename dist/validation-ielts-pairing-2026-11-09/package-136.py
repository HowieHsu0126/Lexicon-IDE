#!/usr/bin/env python3
"""Stage/publish 1.36 only from exact, successful three-stage and device evidence.

AUTHORING: do not execute this file. A later explicit invocation performs a fresh
OFFLINE source rebuild, but never runs ADB, a model API, or a harness session.
Missing gates fail closed. It does not repair receipts or upgrade old evidence.

Required inputs:
  --trial-dir: checkpointed jobs.sqlite, job-receipt.json, exercise-package.json,
               work/<job-id>/{generation,review,explanation_review}/{inputs,state}.
  --online-ui: exact production-HTTPS online UI receipt.json (or its folder).
  --ui-night: exact offline real-material night/font200 receipt (or its folder).
  --content-review: development content review bound to all exact artifact hashes.
  --release-checks: JSON with schema=lexicon.release-inputs.v1, version=1.36.0,
                   version_code=40 and the following four sections.

Every file reference below is {"path": "project-relative-or-absolute", "sha256":
"64 lowercase hex"}; referenced files must be regular files inside this project.
No path or command from evidence is executed. All referenced bytes are frozen.

build = {receipt: ref, log: ref, summary: ref, lint: ref, sources: ref,
         junit: {domain: [ref...], data: [ref...], app: [ref...]}, apk_sha256: str}
  sources JSON: {files_sha256: {project-relative source path: SHA...}}. It must
  cover every packaged app/data/domain and Gradle build input, including tests.
  Raw JUnit XML is recounted (at least 398/248/105 tests, at most 1/9/0 skipped),
  summary must match, and raw lint XML must contain no Error/Fatal findings.
device = {data: {receipt: ref, log: ref}, app: {receipt: ref, log: ref},
          online: {receipt: ref, log: ref}, night: {receipt: ref, log: ref},
          apk_identity: ref, settings_before: ref, settings_after: ref}
  Instrumentation logs must pass at least 40/26/1/1 tests respectively. Each
  runner receipt must bind build/production_apk/production_build to the selected
  build directory name. apk_identity must record the tested APK hash, bytes,
  application_id, version, version_code, device, user, and evidence_sha256 with
  exact hashes under data_log/app_log/online_log/night_log/online_ui/night_ui.
  settings snapshots are flat objects; settings_after may wrap them as `after`.
  All recorded UI/network/rotation/user values must match, with an after receipt
  assertion app_force_stopped_before_network_restore=true.
security = {receipt: ref, logs: [ref...], settings: [ref...], databases: [ref...]}
  receipt schema=lexicon.release-security.v1, apk_sha256, and categories mapping
  each of logs/settings/databases to {scanned_files: N, plaintext_key_matches: 0}.
  Every category must be nonempty. The packager repeats exact-key byte scanning
  of these files, all bound evidence, the APK, source ZIP and rebuild output.
  Log/settings files are streamed with a 512,000,000-byte per-file limit;
  database files remain limited to 64,000,000 bytes. JSON limits do not change.
preservation = {receipt: ref, before_database: ref, after_database: ref,
                baseline_archive: ref}
  Both DBs must be checkpointed copies. Every table schema, row count and sorted
  row-content digest is compared directly. The receipt binds before_sha256,
  after_sha256, baseline_archive_sha256, android_user=0 and scope=
  current_user0_baseline_only. This does not certify recovery of former user10.

Content review retains lexicon.ielts.manual-content-review.v1 for compatibility:
reviewer.method=development_code_assistant_review, human_test_taker_trial=false,
human_examiner_certification=false; reading item answer/explanation statuses
accepted, unique answers, exact question coverage, and Task2 accepted or accepted
with nonblocking design observation. The name does not imply human adjudication.
Its job_id/request/package/artifact/provenance/review/explanation hashes must all
match; blocking_items must be empty. UI receipts never substitute for this gate.
Each stage also requires its actual profile.json and effective-profile.yml.
The patch, effective profile digest, native receipt and fixed host execution
limits must agree; older 8192-token reasoning runs cannot certify the new limit.
When a stage records one malformed-tool correction, both attempt receipts and
plugin receipts must remain at state/attempt-01 and state/attempt-02. Their exact
bytes, the fixed repair prompt, same-session context, original remaining time
and complete request ledger are rechecked; no unknown request can be resumed.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = Path(__file__).resolve().parent
VERSION = '1.36.0'
VERSION_CODE = 40
OLD_SOURCE = ROOT / 'dist/Lexicon-IDE-1.35.0-source.zip'
OLD_APK = ROOT / 'dist/Lexicon-IDE-1.35.0-android16-debug.apk'
OLD_SOURCE_SHA = 'ff2194af3eb5e84141ed690e504474ee9bc7a3727e12433a47291c77f01db5ac'
OLD_APK_SHA = 'd1cc68fb15e6caf968113109e9f34f38d1bf4bceba41dc90d3383406b258cfb8'
REFERENCE = 'dist/validation-ielts-corpus-2026-11-08/corpus-01/reference.json'
REFERENCE_SHA = 'f83745f02a6f1fcf878969d13cf3a4d686535eec920931fab4f438c6b9b54b8f'
SHA = re.compile(r'[a-f0-9]{64}\Z')
MAX_SECURITY_EVIDENCE_BYTES = 512_000_000
MAX_DATABASE_EVIDENCE_BYTES = 64_000_000
EVIDENCE_HASH_CHUNK_BYTES = 1024 * 1024
FORBIDDEN_PARTS = {'.cache', '.gradle', '.build-support', '__pycache__', 'build', 'sessions', 'dsh-home'}
FORBIDDEN_SUFFIXES = {'.mdx', '.mdd', '.sqlite', '.sqlite3', '.db', '.apkg', '.pyc', '.pem', '.key', '.p12', '.pfx', '.crt', '.cer', '.der', '.jks', '.keystore', '.jsonl', '.zstd'}
STEPS = {
    'material_structure_and_host_claims_validated',
    'all_reading_forms_and_short_writing_saved_before_exit',
    'draft_restored_through_history_with_new_viewmodel',
    'persisted_submission_precedes_reference_reveal',
    'references_evidence_and_short_writing_boundary_rendered_at_font200',
    'submitted_history_reopens_without_duplicate_commit_or_network',
}


class PackageError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition:
        raise PackageError(code)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def strict_json_value(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate_json_key')
            result[key] = value
        return result
    def constant(_):
        raise PackageError('nonfinite_json')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=constant)


def strict_json(raw):
    value = strict_json_value(raw)
    require(isinstance(value, dict), 'json_object_required')
    return value


class Inputs:
    """Freeze exact evidence bytes; JSON fields never select executable or source paths."""
    def __init__(self):
        self.hashes = {}
        self.empty_wals = set()
        self.stream_bounds = {}

    def stream_file(self, original, bound):
        """Register bounded evidence without materializing archives in memory."""
        require(type(bound) is int and 0 < bound <= MAX_SECURITY_EVIDENCE_BYTES, 'evidence_size_bound')
        original = Path(original)
        require(not original.is_symlink(), 'evidence_symlink')
        path = original.resolve(strict=True)
        require(path.is_file() and path.is_relative_to(ROOT), 'evidence_not_regular_or_outside_project')
        descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
        try:
            before = os.fstat(descriptor)
            require(stat.S_ISREG(before.st_mode), 'evidence_not_regular_or_outside_project')
            require(before.st_size <= bound, 'evidence_size_bound')
            digest, count = hashlib.sha256(), 0
            with os.fdopen(descriptor, 'rb', closefd=False) as stream:
                while True:
                    block = stream.read(min(EVIDENCE_HASH_CHUNK_BYTES, bound + 1 - count))
                    if not block:
                        break
                    count += len(block)
                    require(count <= bound, 'evidence_size_bound')
                    digest.update(block)
            after = os.fstat(descriptor)
            require(not path.is_symlink(), 'evidence_symlink')
            current = path.stat()
            identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
            require(identity(before) == identity(after) == identity(current) and count == before.st_size,
                    'evidence_changed_during_read')
        finally:
            os.close(descriptor)
        digest = digest.hexdigest()
        require(path not in self.hashes or self.hashes[path] == digest, 'evidence_changed_during_read')
        self.hashes[path] = digest
        self.stream_bounds[path] = min(bound, self.stream_bounds.get(path, bound))
        return path, digest

    def bytes(self, path, bound=2_000_000):
        original = Path(path)
        require(not original.is_symlink(), 'evidence_symlink')
        path = original.resolve(strict=True)
        require(path.is_file() and path.is_relative_to(ROOT), 'evidence_not_regular_or_outside_project')
        with path.open('rb') as stream:
            raw = stream.read(bound + 1)
        require(len(raw) <= bound, 'evidence_size_bound')
        digest = hashlib.sha256(raw).hexdigest()
        require(path not in self.hashes or self.hashes[path] == digest, 'evidence_changed_during_read')
        self.hashes[path] = digest
        return raw

    def json(self, path, bound=2_000_000):
        return strict_json(self.bytes(path, bound))

    def unchanged(self):
        for path, digest in list(self.hashes.items()):
            require(path.is_file() and not path.is_symlink(), 'evidence_changed')
            if path in self.stream_bounds:
                self.stream_file(path, self.stream_bounds[path])
            else:
                require(sha(path) == digest, 'evidence_changed')
        require(all(not path.exists() or path.stat().st_size == 0 for path in self.empty_wals), 'trial_database_wal_changed')

    def manifest(self):
        return {str(path): digest for path, digest in sorted(self.hashes.items(), key=lambda item: str(item[0]))}


def write_new(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n'); stream.flush(); os.fsync(stream.fileno())


def validate_relative(name):
    path = PurePosixPath(name)
    require(name and '\\' not in name and not path.is_absolute() and '..' not in path.parts and '.' not in path.parts,
            'unsafe_source_member')
    lower = [part.lower() for part in path.parts]
    require(not FORBIDDEN_PARTS.intersection(lower), 'private_source_directory')
    require(not any('api.txt' in part or 'pairing-token' in part or 'private-runtime' in part for part in lower), 'private_source_name')
    require(not (path.suffix.lower() == '.json' and any(word in path.name.lower() for word in ('pairing', 'credential', 'connection-document'))), 'private_pairing_file')
    require(not (ROOT / name).is_symlink(), 'source_symlink')
    require(path.suffix.lower() not in FORBIDDEN_SUFFIXES, 'private_source_suffix')
    require(lower[:2] != ['data', 'dic'], 'private_dictionary_directory')
    require(path.parts[0] != 'dist' or name == REFERENCE, 'unapproved_dist_member')
    resolved = (ROOT / name).resolve(strict=True)
    require(resolved.is_file() and resolved.is_relative_to(ROOT), 'source_outside_project')
    return path


def source_names():
    require(sha(OLD_SOURCE) == OLD_SOURCE_SHA and sha(OLD_APK) == OLD_APK_SHA, 'old_release_identity_changed')
    with zipfile.ZipFile(OLD_SOURCE) as archive:
        names = set(archive.namelist())
        require(len(names) == len(archive.infolist()), 'duplicate_old_source_member')
        require(all(not item.is_dir() and not stat.S_ISLNK(item.external_attr >> 16) for item in archive.infolist()), 'old_source_member_kind')
    for module in ('app', 'data', 'domain'):
        for suffix in ('*.kt', '*.xml'):
            names.update(str(path.relative_to(ROOT)) for path in (ROOT / module / 'src').rglob(suffix))
    for suffix in ('*.py', '*.mjs'):
        names.update(str(path.relative_to(ROOT)) for path in (ROOT / 'tools').glob(suffix))
    research = ROOT / 'docs/research/ielts-20261109-coverage'
    manifest = strict_json((research / 'manifest.json').read_bytes())
    require(manifest.get('schema') == 'lexicon.ielts.coverage-research-manifest.v1', 'coverage_manifest_invalid')
    files = manifest.get('files_sha256')
    require(isinstance(files, dict) and files, 'coverage_manifest_empty')
    research_names = set()
    for path in research.rglob('*'):
        require(not path.is_symlink(), 'coverage_symlink')
        if path.is_file():
            require(path.suffix.lower() in {'.json', '.md', '.txt', '.csv', '.tsv', '.yaml', '.yml'}, 'coverage_nontext_file')
            path.read_bytes().decode('utf-8')
            research_names.add(path.relative_to(research).as_posix())
    require(research_names == set(files) | {'manifest.json'}, 'coverage_manifest_members_differ')
    for name, digest in files.items():
        require(isinstance(name, str) and not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts,
                'coverage_member_unsafe')
        require(isinstance(digest, str) and SHA.fullmatch(digest) and sha(research / name) == digest, 'coverage_member_changed')
    names.update((research / name).relative_to(ROOT).as_posix() for name in research_names)
    names.update({REFERENCE, 'data/src/androidTest/AndroidManifest.xml',
                  'tools/ielts_explanation_review.py', 'tools/test_ielts_explanation_review.py',
                  'docs/IELTS-HARNESS.md', 'docs/VALIDATION-1.36.0.md', 'docs/UX-ITERATIONS-1.36.0.md'})
    required = {'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
                'gradle/libs.versions.toml', 'gradle.properties', 'settings.gradle.kts', 'build.gradle.kts',
                'app/build.gradle.kts', 'data/build.gradle.kts', 'domain/build.gradle.kts',
                'tools/harness_ielts_plugin.mjs', 'tools/test_harness_ielts_plugin.mjs'}
    require(required <= names, 'required_source_missing')
    for name in names:
        validate_relative(name)
    require(sha(ROOT / REFERENCE) == REFERENCE_SHA, 'reference_identity_changed')
    return sorted(names)


def assert_sources(snapshot):
    require(all((ROOT / name).is_file() and sha(ROOT / name) == digest for name, digest in snapshot.items()), 'source_changed')


def reference_path(value):
    require(isinstance(value, dict) and set(value) == {'path', 'sha256'}, 'file_reference_invalid')
    name, digest = value['path'], value['sha256']
    require(isinstance(name, str) and isinstance(digest, str) and SHA.fullmatch(digest), 'file_reference_invalid')
    path = Path(name)
    if not path.is_absolute():
        path = ROOT / path
    require(path.name.lower() != 'api.txt', 'credential_cannot_be_evidence')
    return path, digest


def file_ref(inputs, value, *, bound=64_000_000):
    path, digest = reference_path(value)
    raw = inputs.bytes(path, bound)
    require(hashlib.sha256(raw).hexdigest() == digest, 'referenced_file_changed')
    return path.resolve(strict=True), raw


def security_file_ref(inputs, value, *, bound=MAX_SECURITY_EVIDENCE_BYTES):
    path, expected = reference_path(value)
    path, digest = inputs.stream_file(path, bound)
    require(digest == expected, 'referenced_file_changed')
    return path


def json_ref(inputs, value):
    path, raw = file_ref(inputs, value, bound=8_000_000)
    return path, strict_json(raw)


def exact(value, expected, code):
    require(isinstance(value, dict) and all(type(value.get(k)) is type(v) and value[k] == v for k, v in expected.items()), code)


def sqlite_snapshot(inputs, reference):
    path, raw = file_ref(inputs, reference)
    require(raw.startswith(b'SQLite format 3\x00'), 'preservation_not_sqlite')
    wal = Path(str(path) + '-wal')
    require(not wal.exists() or wal.stat().st_size == 0, 'preservation_database_not_checkpointed')
    inputs.empty_wals.add(wal)
    db = sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        require(db.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'preservation_database_corrupt')
        tables = db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        require(tables and {'articles', 'cards', 'captures', 'preferences', 'review_logs', 'sources'} <= {r[0] for r in tables},
                'preservation_tables_missing')
        output = {}
        for name, schema in tables:
            quoted = '"' + name.replace('"', '""') + '"'
            encoded = []
            for row in db.execute('SELECT * FROM ' + quoted):
                normalized = [[type(v).__name__, v.hex() if isinstance(v, bytes) else v] for v in row]
                encoded.append(json.dumps(normalized, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8'))
            digest = hashlib.sha256()
            for row in sorted(encoded):
                digest.update(str(len(row)).encode('ascii') + b':' + row)
            output[name] = {'schema': schema, 'rows': len(encoded), 'content_sha256': digest.hexdigest()}
        return output
    finally:
        db.close()


def prerequisites(inputs, location, snapshot, online_path, night_path):
    top = inputs.json(location, 8_000_000)
    exact(top, {'schema': 'lexicon.release-inputs.v1', 'version': VERSION, 'version_code': VERSION_CODE}, 'release_input_schema')
    require(set(top) == {'schema', 'version', 'version_code', 'build', 'device', 'security', 'preservation'}, 'release_input_sections')
    build, device, security, preservation = (top[key] for key in ('build', 'device', 'security', 'preservation'))
    require(all(isinstance(v, dict) for v in (build, device, security, preservation)), 'release_input_section_type')
    build_path, receipt = json_ref(inputs, build['receipt'])
    exact(receipt, {'exit_code': 0, 'offline': True}, 'build_failed')
    tasks = receipt.get('tasks')
    require(isinstance(tasks, list) and {':domain:test', ':data:testDebugUnitTest', ':app:testDebugUnitTest', ':app:assembleDebug',
        ':data:assembleDebugAndroidTest', ':app:assembleDebugAndroidTest', ':app:lintDebug'} <= set(tasks), 'build_tasks_missing')
    _, log_raw = file_ref(inputs, build['log'], bound=8_000_000)
    log = log_raw.decode('utf-8')
    require('BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log, 'build_log_not_successful')
    _, summary = json_ref(inputs, build['summary'])
    _, sources = json_ref(inputs, build['sources'])
    sources = sources.get('files_sha256')
    required_inputs = {name: digest for name, digest in snapshot.items() if
        name.split('/')[0] in {'app', 'data', 'domain', 'gradle'} or
        name in {'gradlew', 'gradlew.bat', 'gradle.properties', 'settings.gradle.kts', 'build.gradle.kts'}}
    require(isinstance(sources, dict) and all(sources.get(k) == v for k, v in required_inputs.items()), 'build_source_binding_missing')
    current_apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(current_apk)
    require(build.get('apk_sha256') == apk_hash, 'build_apk_mismatch')
    totals = {}
    for module, minimum, maximum_skips in [('domain', 398, 1), ('data', 248, 9), ('app', 105, 0)]:
        refs = build['junit'].get(module)
        require(isinstance(refs, list) and refs, 'junit_evidence_missing')
        count = skipped = 0
        seen_paths, seen_cases = set(), set()
        for reference in refs:
            path, raw = file_ref(inputs, reference, bound=8_000_000)
            require(path not in seen_paths, 'junit_file_repeated'); seen_paths.add(path)
            require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'xml_declaration_forbidden')
            suite = ET.fromstring(raw)
            require(suite.tag == 'testsuite' and int(suite.attrib.get('failures', '-1')) == 0 and
                    int(suite.attrib.get('errors', '-1')) == 0, 'junit_failure')
            cases = suite.findall('testcase')
            require(len(cases) == int(suite.attrib['tests']), 'junit_count_mismatch')
            local_skipped = 0
            for case in cases:
                identity = (case.attrib.get('classname'), case.attrib.get('name'))
                require(all(isinstance(v, str) and v for v in identity) and identity not in seen_cases, 'junit_case_repeated')
                seen_cases.add(identity)
                require(case.find('failure') is None and case.find('error') is None, 'junit_case_failed')
                local_skipped += case.find('skipped') is not None
            require(local_skipped == int(suite.attrib.get('skipped', '0')), 'junit_skip_mismatch')
            count += len(cases); skipped += local_skipped
        require(count >= minimum and skipped <= maximum_skips, 'junit_baseline_regressed')
        actual = {'tests': count, 'failures': 0, 'errors': 0, 'skipped': skipped}
        exact(summary.get(module), actual, 'junit_summary_mismatch')
        totals[module] = actual
    _, lint_raw = file_ref(inputs, build['lint'], bound=8_000_000)
    require(b'<!DOCTYPE' not in lint_raw and b'<!ENTITY' not in lint_raw, 'xml_declaration_forbidden')
    lint = ET.fromstring(lint_raw)
    require(lint.tag == 'issues' and not any(r.get('severity', '').lower() in {'error', 'fatal'} for r in lint.findall('issue')), 'lint_failed')

    device_counts, device_log_hashes = {}, {}
    for role, minimum in [('data', 40), ('app', 26), ('online', 1), ('night', 1)]:
        row = device[role]
        _, run = json_ref(inputs, row['receipt'])
        exact(run, {'passed': True, 'exit_code': 0}, 'device_runner_failed')
        require(any(run.get(k) == build_path.parent.name for k in ('build', 'production_apk', 'production_build')), 'device_build_binding_missing')
        if role in {'data', 'app'}:
            exact(run, {'offline': True, 'synthetic': True}, 'device_regression_scope_mismatch')
        if role == 'online':
            exact(run, {'transport_under_test': 'production_https_with_imported_leaf_over_adb_reverse',
                        'file_picker_item_selected': True, 'system_trust_modified': False}, 'online_runner_scope_mismatch')
        path, raw = file_ref(inputs, row['log'], bound=8_000_000)
        log = raw.decode('utf-8')
        matches = re.findall(r'^OK \((\d+) tests?\)\s*$', log, re.MULTILINE)
        require(len(matches) == 1 and int(matches[0]) >= minimum and
                re.search(r'^INSTRUMENTATION_CODE: -1\s*$', log, re.MULTILINE) and
                not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', log), 'device_log_not_passed')
        expected_classes = {
            'data': {'dev.lexicon.data.IeltsPracticeStorageTest', 'dev.lexicon.data.IeltsGenerationRepositoryTest',
                     'dev.lexicon.data.IeltsGatewaySettingsTest'},
            'app': {'dev.lexicon.ide.ui.IeltsPracticeScreenTest', 'dev.lexicon.ide.ui.IeltsExerciseRouteTest',
                    'dev.lexicon.ide.ui.IeltsMainNavigationUiTest'},
            'online': {'dev.lexicon.ide.ui.IeltsOnlineGatewayTrialTest'},
            'night': {'dev.lexicon.ide.ui.IeltsGeneratedMaterialTrialTest'},
        }[role]
        observed_classes = set(re.findall(r'^INSTRUMENTATION_STATUS: class=(\S+)\s*$', log, re.MULTILINE))
        statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', log, re.MULTILINE)
        require(expected_classes <= observed_classes and set(statuses) <= {'0', '1'} and
                statuses.count('0') == int(matches[0]), 'device_classes_or_completed_tests_missing')
        device_counts[role] = int(matches[0]); device_log_hashes[role + '_log'] = sha(path)
    _, identity_value = json_ref(inputs, device['apk_identity'])
    exact(identity_value, {'application_id': 'dev.lexicon.ide', 'version': VERSION, 'version_code': VERSION_CODE,
                         'user': 0, 'apk_sha256': apk_hash, 'bytes': current_apk.stat().st_size,
                         'installed_matches_current_build_apk': True}, 'tested_apk_identity_mismatch')
    require(isinstance(identity_value.get('device'), str) and identity_value['device'], 'tested_device_missing')
    for role, path in [('online_ui', online_path), ('night_ui', night_path)]:
        inputs.bytes(path); device_log_hashes[role] = sha(path)
    exact(identity_value.get('evidence_sha256'), device_log_hashes, 'tested_apk_evidence_not_bound')
    _, before_settings = json_ref(inputs, device['settings_before'])
    _, after_receipt = json_ref(inputs, device['settings_after'])
    after_settings = after_receipt.get('after', after_receipt)
    settings_keys = {'font_scale', 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale',
                     'rotation', 'accelerometer_rotation', 'user', 'night', 'airplane', 'wifi', 'mobile_data'}
    require(settings_keys <= set(before_settings) and settings_keys <= set(after_settings) and
            all(type(before_settings[k]) is type(after_settings[k]) and before_settings[k] == after_settings[k] for k in settings_keys),
            'device_settings_changed')
    exact(after_receipt, {'app_force_stopped_before_network_restore': True}, 'device_restore_order_unconfirmed')

    _, secure = json_ref(inputs, security['receipt'])
    exact(secure, {'schema': 'lexicon.release-security.v1', 'apk_sha256': apk_hash}, 'security_scope_mismatch')
    security_files = []
    for role in ('logs', 'settings', 'databases'):
        refs = security.get(role)
        require(isinstance(refs, list) and refs, 'security_category_empty')
        paths = []
        for reference in refs:
            path = security_file_ref(inputs, reference,
                bound=MAX_DATABASE_EVIDENCE_BYTES if role == 'databases' else MAX_SECURITY_EVIDENCE_BYTES)
            require(path not in paths, 'security_file_repeated'); paths.append(path)
        exact(secure.get('categories', {}).get(role), {'scanned_files': len(paths), 'plaintext_key_matches': 0}, 'security_findings_or_missing')
        security_files += paths

    _, preserved = json_ref(inputs, preservation['receipt'])
    baseline_path, _ = file_ref(inputs, preservation['baseline_archive'])
    before = sqlite_snapshot(inputs, preservation['before_database'])
    after = sqlite_snapshot(inputs, preservation['after_database'])
    require(before == after, 'user0_database_changed')
    exact(preserved, {'scope': 'current_user0_baseline_only', 'android_user': 0,
        'before_sha256': preservation['before_database']['sha256'], 'after_sha256': preservation['after_database']['sha256'],
        'baseline_archive_sha256': sha(baseline_path)}, 'preservation_identity_mismatch')
    return {'build': build_path.parent.name, 'jvm': totals, 'device_tests': device_counts,
            'installed_apk_sha256': apk_hash, 'installed_apk_evidence_bound': True,
            'current_user0_tables_preserved': len(before), 'original_user10_preservation': 'unverified',
            'device_settings_restored': True, 'security_files': len(set(security_files)),
            'release_inputs_sha256': sha(location)}


def runtime_profile(inputs, stage, receipt):
    """Read only; never evaluate configuration expressions or start the runtime."""
    from harness_ielts import (HarnessError, stage_execution_limits,
                               validate_effective_model_profile, validate_stage_model)
    try:
        validate_stage_model(receipt.get('stage'), receipt.get('model'), receipt.get('reasoning_effort'))
        limits = stage_execution_limits(receipt.get('reasoning_effort'))
    except (HarnessError, ValueError, TypeError):
        raise PackageError('runtime_execution_policy_invalid') from None
    for name, expected in limits.items():
        require(type(receipt.get(name)) is int and receipt[name] == expected,
                'runtime_execution_policy_mismatch')
    raw_patch = inputs.bytes(stage / 'inputs/profile.json', 200_000)
    rows = strict_json_value(raw_patch)
    require(isinstance(rows, list) and rows and all(isinstance(row, dict) for row in rows),
            'runtime_patch_rows_invalid')
    ids = [row['id'] for row in rows if 'id' in row]
    require(all(isinstance(value, str) for value in ids) and len(set(ids)) == len(ids),
            'runtime_patch_duplicate_identity')
    by_id = {row['id']: row for row in rows if 'id' in row}
    # Model settings have one direct definition. An inserted replacement must
    # not override a checked row later during configuration resolution.
    for row in rows:
        if 'insert' in row:
            require(isinstance(row['insert'], list) and all(isinstance(item, dict) for item in row['insert']),
                    'runtime_patch_insert_invalid')
            require(all(item.get('id') not in {'agent-default-model', 'llm-deepseek'} for item in row['insert']),
                    'runtime_patch_model_override')
    default, adapter = by_id.get('agent-default-model'), by_id.get('llm-deepseek')
    require(isinstance(default, dict) and isinstance(adapter, dict) and
            set(default) == {'id', 'config'} and set(adapter) == {'id', 'config'}, 'runtime_patch_model_missing')
    model, reasoning = receipt['model'], receipt['reasoning_effort']
    require(default['config'] == {'provider': 'deepseek-official', 'model': model, 'reasoningEffort': reasoning},
            'runtime_patch_default_model_mismatch')
    config = adapter['config']
    require(isinstance(config, dict) and
            set(config) == {'baseURL', 'apiKeyEnv', 'reasoningEffort', 'maxTokens',
                            'streamIdleTimeoutMs', 'retryPolicy', 'models'}, 'runtime_patch_adapter_shape')
    require(isinstance(config.get('baseURL'), str) and
            re.fullmatch(r'http://127\.0\.0\.1:([1-9][0-9]{0,4})(?:/v1/?)?', config['baseURL']) is not None,
            'runtime_patch_route_invalid')
    port = int(re.fullmatch(r'http://127\.0\.0\.1:([1-9][0-9]{0,4})(?:/v1/?)?', config['baseURL']).group(1))
    require(port <= 65535 and config['apiKeyEnv'] == 'LEXICON_DUMMY_KEY', 'runtime_patch_route_invalid')
    require(config['reasoningEffort'] == reasoning and
            type(config['maxTokens']) is int and config['maxTokens'] == limits['token_limit'] and
            type(config['streamIdleTimeoutMs']) is int and
            config['streamIdleTimeoutMs'] == limits['stream_idle_timeout_ms'], 'runtime_patch_budget_mismatch')
    require(config['retryPolicy'] == {'mode': 'normal', 'maxRetries': 0} and
            type(config['retryPolicy']['maxRetries']) is int, 'runtime_patch_retries_enabled')
    expected_models = [{'id': model, 'inputModalities': ['text'], 'contextWindow': 1048576,
                        'maxTokens': limits['token_limit']}]
    require(config['models'] == expected_models and
            type(config['models'][0]['maxTokens']) is int and
            type(config['models'][0]['contextWindow']) is int, 'runtime_patch_exact_model_mismatch')
    effective = inputs.bytes(stage / 'inputs/effective-profile.yml', 200_000)
    digest = hashlib.sha256(effective).hexdigest()
    require(digest == receipt.get('profile_sha256'), 'runtime_effective_profile_digest_mismatch')
    try:
        validate_effective_model_profile(effective, model=model, reasoning_effort=reasoning, limits=limits)
    except (HarnessError, ValueError, TypeError):
        raise PackageError('runtime_effective_profile_settings_mismatch') from None
    adapter_rows = [row for row in re.split(r'(?m)^- id: ', effective.decode('utf-8'))[1:]
                    if row.splitlines()[0].strip() == 'llm-deepseek']
    require(len(adapter_rows) == 1, 'runtime_effective_profile_route_mismatch')
    for name in ('baseURL', 'apiKeyEnv'):
        values = re.findall(r'(?m)^    ' + name + r': ([^\r\n]+)$', adapter_rows[0])
        expected = config[name]
        require(len(values) == 1 and values[0] in {expected, "'" + expected + "'", '"' + expected + '"'},
                'runtime_effective_profile_route_mismatch')
    return {'stage': receipt['stage'], 'model': model, 'reasoning_effort': reasoning, **limits,
            'profile_sha256': digest, 'patch_sha256': hashlib.sha256(raw_patch).hexdigest()}


def runtime_delivery(inputs, stage, receipt, manifest):
    """Bind the output authority and exact artifact bytes to the frozen manifest."""
    from harness_ielts import (HarnessError, LEGACY_DELIVERY, candidate_delivery_kind,
                               validate_candidate_delivery_receipt)
    try:
        delivery = candidate_delivery_kind(receipt)
        validate_candidate_delivery_receipt(receipt)
    except (HarnessError, ValueError, TypeError):
        raise PackageError('runtime_candidate_delivery_invalid') from None
    require(manifest.get('candidateDelivery', LEGACY_DELIVERY) == delivery,
            'runtime_candidate_delivery_mismatch')
    raw = inputs.bytes(stage / 'state/validated-candidate.json', 200000)
    plugin = receipt['tool_receipt']
    require(hashlib.sha256(raw).hexdigest() == receipt.get('candidate_sha256') == plugin.get('candidateSha256') and
            type(receipt.get('candidate_bytes')) is int and type(plugin.get('candidateBytes')) is int and
            len(raw) == receipt['candidate_bytes'] == plugin['candidateBytes'], 'runtime_candidate_bytes_mismatch')
    return delivery


def runtime_recovery(inputs, stage, receipt, records):
    """Bind one bounded correction to original bytes; do not infer or replay it."""
    from harness_ielts import (HarnessError, MAX_STAGE_TOOL_CALLS, continuation_seed,
                               validate_native_recovery_receipt, validate_stage_tool_receipt,
                               LEGACY_DELIVERY, candidate_delivery_kind)
    state = stage / 'state'
    input_directory = stage / 'inputs'
    seed_path = input_directory / 'continuation.json'
    attempts_on_disk = {path.name for path in state.glob('attempt-*')}
    if 'recovery' not in receipt:
        require(not attempts_on_disk and not os.path.lexists(seed_path), 'unclaimed_runtime_recovery_evidence')
    try:
        validate_native_recovery_receipt(receipt, records)
    except (HarnessError, ValueError, TypeError):
        raise PackageError('runtime_recovery_binding_mismatch') from None
    if 'recovery' not in receipt:
        return None
    recovery = receipt['recovery']
    require(attempts_on_disk == {'attempt-01', 'attempt-02'}, 'runtime_recovery_attempts_missing')
    context = receipt.get('execution_context_sha256')
    require(isinstance(context, str) and SHA.fullmatch(context), 'runtime_recovery_context_invalid')
    require(input_directory.is_dir() and not input_directory.is_symlink(), 'runtime_recovery_input_directory_invalid')
    require(os.path.lexists(seed_path), 'runtime_recovery_continuation_missing')
    raw_manifest = inputs.bytes(input_directory / 'manifest.json', 32768)
    manifest = strict_json(raw_manifest)
    manifest_sha = hashlib.sha256(raw_manifest).hexdigest()
    require(manifest_sha == receipt.get('manifest_sha256') and
            manifest.get('schema') == 'lexicon.ielts.harness.manifest.v1' and
            manifest.get('mode') == receipt.get('stage'), 'runtime_recovery_manifest_mismatch')
    require(manifest.get('candidateDelivery', LEGACY_DELIVERY) == candidate_delivery_kind(receipt),
            'runtime_candidate_delivery_mismatch')
    require(manifest.get('continuationPath') == str(input_directory.resolve() / 'continuation.json'),
            'runtime_recovery_continuation_path_mismatch')
    limits = manifest.get('limits')
    require(isinstance(limits, dict) and type(limits.get('maxToolCalls')) is int and
            limits['maxToolCalls'] == MAX_STAGE_TOOL_CALLS, 'runtime_recovery_tool_budget_mismatch')
    raw_seed = inputs.bytes(seed_path, 4096)
    seed_sha = hashlib.sha256(raw_seed).hexdigest()
    require(seed_sha == recovery.get('continuation_sha256'), 'runtime_recovery_continuation_digest_mismatch')
    seed = strict_json(raw_seed)
    file_bindings, originals = [], []
    for ordinal, attempt in enumerate(recovery['attempts'], 1):
        folder = state / ('attempt-%02d' % ordinal)
        require(folder.is_dir() and not folder.is_symlink(), 'runtime_recovery_attempt_directory_invalid')
        raw_runtime = inputs.bytes(folder / 'runtime-receipt.json')
        raw_plugin = inputs.bytes(folder / 'plugin-receipt.json')
        runtime_sha = hashlib.sha256(raw_runtime).hexdigest()
        plugin_sha = hashlib.sha256(raw_plugin).hexdigest()
        require(runtime_sha == attempt['runtime_receipt_sha256'] and
                plugin_sha == attempt['plugin_receipt_sha256'], 'runtime_recovery_attempt_digest_mismatch')
        original = strict_json(raw_runtime)
        plugin = strict_json(raw_plugin)
        require(original == attempt['receipt'] and original.get('tool_receipt') == plugin,
                'runtime_recovery_attempt_content_mismatch')
        require(plugin.get('limits') == limits, 'runtime_recovery_tool_budget_mismatch')
        originals.append(original)
        file_bindings.append({'attempt': ordinal, 'runtime_receipt_sha256': runtime_sha,
                              'plugin_receipt_sha256': plugin_sha,
                              'model_request_ordinals': attempt['model_request_ordinals'],
                              'per_tool': plugin.get('perTool'), 'completed_per_tool': plugin.get('completedPerTool'),
                              'allowed_tool_calls': plugin.get('allowedCount'),
                              'completed_tool_calls': plugin.get('completedCount'),
                              'carried_tool_calls': plugin.get('carriedToolCalls', 0)})
    first, second = originals
    try:
        expected_seed = continuation_seed(first, file_bindings[0]['runtime_receipt_sha256'],
                                          file_bindings[0]['plugin_receipt_sha256'])
        validate_stage_tool_receipt(receipt['stage'], second.get('tool_receipt'))
    except (HarnessError, ValueError, TypeError):
        raise PackageError('runtime_recovery_binding_mismatch') from None
    require(seed == expected_seed, 'runtime_recovery_continuation_content_mismatch')
    first_plugin, second_plugin = first['tool_receipt'], second['tool_receipt']
    carried = first_plugin['allowedCount']
    require(second.get('continuation_sha256') == seed_sha == second_plugin.get('continuationSha256') and
            type(second.get('carried_tool_calls')) is int and second['carried_tool_calls'] == carried and
            type(second_plugin.get('carriedToolCalls')) is int and second_plugin['carriedToolCalls'] == carried and
            type(second.get('tool_calls')) is int and second['tool_calls'] == second_plugin['allowedCount'] and
            carried + second_plugin['allowedCount'] <= MAX_STAGE_TOOL_CALLS,
            'runtime_recovery_tool_budget_mismatch')
    require(receipt.get('tool_receipt') == recovery['attempts'][1]['receipt'].get('tool_receipt'),
            'runtime_recovery_final_plugin_mismatch')
    return {'stage': receipt['stage'], 'kind': recovery['kind'], 'attempt_count': 2,
            'session_id': receipt['session_id'], 'execution_context_sha256': context,
            'original_prompt_sha256': recovery['original_prompt_sha256'],
            'repair_prompt_sha256': recovery['repair_prompt_sha256'],
            'manifest_sha256': manifest_sha, 'continuation_sha256': seed_sha,
            'carried_tool_calls': carried, 'max_stage_tool_calls': MAX_STAGE_TOOL_CALLS,
            'stage_tool_calls': carried + second_plugin['allowedCount'],
            'stage_timeout_ms': recovery['stage_timeout_ms'],
            'stage_elapsed_ms': recovery['stage_elapsed_ms'],
            'remaining_timeout_ms': recovery['remaining_timeout_ms'], 'attempt_files': file_bindings}


def runtime_quality_revision(inputs, work, request, receipts, records, snapshot):
    """Recheck original rejection, original artifacts and exact revision input."""
    from ielts_contract import canonical_sha256
    from ielts_pipeline import verify_rejected_round
    path = work / 'quality-revision.json'
    if not os.path.lexists(path):
        require(len(receipts) == 3 and not os.path.lexists(work / 'revision-01'), 'unclaimed_quality_revision')
        return None, work, receipts, [], []
    rejected = inputs.json(path)
    prior = rejected.get('receipts')
    require(isinstance(prior, list) and len(prior) in (2, 3) and len(receipts) == len(prior) + 3 and
            receipts[:len(prior)] == prior, 'quality_revision_receipts_mismatch')
    count = sum(len(r.get('model_request_ordinals', [])) for r in prior)
    try:
        context = verify_rejected_round(request, rejected, records[:count])
    except (ValueError, TypeError, KeyError):
        raise PackageError('quality_revision_rejection_invalid') from None
    context_path = work / 'revision-01/generation/inputs/quality-revision-context.json'
    require(inputs.json(context_path) == context and
            receipts[len(prior)].get('quality_revision_sha256') == canonical_sha256(context), 'quality_revision_context_mismatch')
    profiles, recoveries = [], []
    for index, receipt in enumerate(prior):
        mode = receipt['stage']; stage = work / mode
        stored = inputs.json(stage / 'state/runtime-receipt.json')
        require(stored == {k:v for k,v in receipt.items() if k != 'model_request_ordinals'}, 'quality_revision_original_receipt_mismatch')
        plugin = inputs.json(stage / 'state/plugin-receipt.json')
        require(plugin == receipt.get('tool_receipt'), 'quality_revision_original_plugin_mismatch')
        exact(plugin, {'mode':mode,'pluginReady':True,'globalGuard':True,'registrationIdentityVerified':True,
            'candidateSchemaStrict':True,'candidateCompilerReady':True,'deniedCount':0,'identityFailures':0}, 'runtime_plugin_gate_failed')
        manifest_path = stage / 'inputs/manifest.json'; manifest = inputs.json(manifest_path)
        require(sha(manifest_path) == receipt.get('manifest_sha256') == plugin.get('manifestSha256'), 'runtime_manifest_binding')
        exact(manifest, {'schema':'lexicon.ielts.harness.manifest.v1','mode':mode}, 'runtime_manifest_mode')
        runtime_delivery(inputs, stage, receipt, manifest)
        expected = rejected['package'] if index == 0 else rejected['review'] if index == 1 else rejected['explanation_review']
        require(inputs.json(stage/'state/validated-candidate.json') == expected, 'quality_revision_original_candidate_changed')
        files = {'request':request}
        if index: files['package'] = rejected['package']
        if index == 2: files['blindReview'] = rejected['review']
        for key,value in files.items():
            source = stage/'inputs'/(key+'.json')
            require(inputs.json(source) == value and sha(source) == manifest['files'][key]['sha256'], 'runtime_frozen_input_changed')
        modules = {'contract':'ielts_contract.py','schemaModule':'ielts_json_schema.py','candidateModule':'ielts_candidate.py',
                   'corpusModule':'ielts_corpus.py'}
        if 'writing_task1_request' in request or 'visualModule' in manifest['files']:
            modules['visualModule'] = 'ielts_visual.py'
            require(receipt.get('visual_module_sha256') == snapshot['tools/ielts_visual.py'] and
                    plugin.get('visualModuleSha256') == receipt.get('visual_module_sha256'), 'visual_runtime_binding')
        if index: modules['reviewModule'] = 'ielts_review.py'
        if index == 2: modules['explanationReviewModule'] = 'ielts_explanation_review.py'
        for key,name in modules.items():
            source = stage/'inputs'/name; inputs.bytes(source)
            require(sha(source) == manifest['files'][key]['sha256'] == snapshot['tools/'+name], 'runtime_frozen_source_changed')
        for field,name in [('schema_module_sha256','ielts_json_schema.py'),('candidate_module_sha256','ielts_candidate.py')]:
            require(receipt.get(field) == snapshot['tools/'+name], 'trial_runtime_source_changed')
        if index:
            require(manifest.get('reviewId') == expected['review_id'], 'runtime_review_identity_changed')
        if index == 2:
            require(receipt.get('explanation_review_module_sha256') == snapshot['tools/ielts_explanation_review.py'] and
                    receipt.get('blind_review_input_sha256') == sha(stage/'inputs/blindReview.json') and
                    plugin.get('explanationReviewModuleSha256') == receipt['explanation_review_module_sha256'] and
                    plugin.get('blindReviewInputSha256') == receipt['blind_review_input_sha256'], 'explanation_plugin_binding')
        frozen_plugin = stage/'inputs/harness_ielts_plugin.mjs'; inputs.bytes(frozen_plugin)
        require(sha(frozen_plugin) == receipt['plugin_sha256'] == snapshot['tools/harness_ielts_plugin.mjs'], 'runtime_frozen_plugin_changed')
        corpus = stage/'inputs'/PurePosixPath(REFERENCE).name; inputs.bytes(corpus)
        require(sha(corpus) == manifest['files']['corpus']['sha256'] == snapshot[REFERENCE], 'runtime_frozen_source_changed')
        profiles.append(runtime_profile(inputs, stage, receipt))
        correction = runtime_recovery(inputs, stage, receipt, records)
        if correction is not None: recoveries.append(correction)
    return rejected, work/'revision-01', receipts[len(prior):], profiles, recoveries


def real_trial(inputs, directory, snapshot):
    sys.path.insert(0, str(ROOT / 'tools'))
    from ielts_contract import canonical_sha256, validate_package
    from ielts_pipeline import verified_output
    from ielts_jobs import verify_body
    from harness_ielts import HARNESS_TOOL_NAMES, required_stage_tools, validate_stage_tool_receipt
    import ielts_corpus
    ielts_corpus._verify_research(ielts_corpus.DEFAULT_RESEARCH_DIR)
    regenerated = ielts_corpus._json_bytes(ielts_corpus._artifact()) + b'\n'
    require(hashlib.sha256(regenerated).hexdigest() == REFERENCE_SHA, 'packaged_corpus_not_reproducible')
    directory = directory.resolve(strict=True)
    require(directory.is_dir(), 'trial_directory_required')
    job = inputs.json(directory / 'job-receipt.json')
    job_id = job.get('id')
    require(isinstance(job_id, str) and re.fullmatch(r'job-[a-f0-9]{32}', job_id), 'trial_job_id_invalid')
    require(job.get('state') == 'ready' and job.get('error') is None and type(job.get('process_reaped')) is int and job['process_reaped'] == 1,
            'real_package_not_ready')
    artifact_bytes = inputs.bytes(directory / 'exercise-package.json')
    artifact = strict_json(artifact_bytes)
    require(set(artifact) == {'schema', 'request', 'package', 'provenance'} and artifact['schema'] == 'lexicon.ielts.verified-package.v1', 'verified_wrapper_invalid')
    request, package, provenance = artifact['request'], artifact['package'], artifact['provenance']
    validate_package(request, package)
    require(job.get('request_id') == request['request_id'], 'trial_request_binding')
    require(provenance.get('kind') == 'deepseek_harness' and provenance.get('semantic_review') == 'independent_answers_agree'
            and provenance.get('recent_three_year_coverage') is False and
            provenance.get('explanation_review') == 'source_faithfulness_checked', 'trial_not_real_reviewed_material')
    require(provenance.get('corpus_sha256') == REFERENCE_SHA, 'trial_corpus_differs_from_source_package')
    work = directory / 'work' / job_id
    usage = inputs.json(work / 'model-requests.json')
    records = usage.get('requests')
    require(usage.get('schema') == 'lexicon.ielts.model-usage.v1' and isinstance(records, list) and 3 <= len(records) <= 16,
            'trial_model_usage_invalid')
    require(all(isinstance(row, dict) and row.get('real_provider_request') is True and row.get('phase') == 'completed'
                and type(row.get('http_status')) is int and row['http_status'] == 200 for row in records), 'trial_request_unconfirmed')
    real_count = len(records)
    require(usage.get('reserved_calls') == real_count and usage.get('real_provider_requests') == real_count and
            usage.get('provider_completion_unconfirmed') == 0 and provenance.get('real_provider_request_count') == real_count, 'trial_model_count_mismatch')
    # run_job adds model_request_ordinals after writing the native receipt. The
    # durable stage_receipts table contains that parent-owned binding; never
    # fabricate it from session text or infer it from a count of model messages.
    database = directory / 'jobs.sqlite'
    wal = Path(str(database) + '-wal')
    require(not wal.exists() or wal.stat().st_size == 0, 'trial_database_not_checkpointed')
    inputs.empty_wals.add(wal)
    inputs.bytes(database, 64_000_000)
    connection = sqlite3.connect(database.resolve(strict=True).as_uri() + '?mode=ro&immutable=1', uri=True)
    connection.row_factory = sqlite3.Row
    try:
        stored = connection.execute('SELECT * FROM exercise_jobs WHERE id=?', (job_id,)).fetchone()
        rows = connection.execute('SELECT ordinal,receipt_json FROM stage_receipts WHERE job_id=? ORDER BY ordinal', (job_id,)).fetchall()
    finally:
        connection.close()
    require(stored is not None and all(stored[key] == value for key, value in job.items()), 'trial_database_job_mismatch')
    require(stored['state'] == 'ready' and stored['process_token'] is None and stored['process_reaped'] == 1, 'trial_database_not_terminal')
    require(strict_json(stored['output_json'].encode()) == artifact, 'trial_database_output_mismatch')
    envelope = strict_json(stored['input_json'].encode())
    require(envelope['request'] == request and canonical_sha256(envelope) == job['input_hash'], 'trial_database_input_mismatch')
    require(envelope.get('corpus_hash') == REFERENCE_SHA, 'trial_input_corpus_mismatch')
    verify_body(request, envelope['body'])
    require(len(rows) in (3, 5, 6) and [row['ordinal'] for row in rows] == list(range(len(rows))), 'trial_database_stage_count')
    receipts = [strict_json(row['receipt_json'].encode()) for row in rows]
    rejected, work, receipts, profiles, recoveries = runtime_quality_revision(inputs, work, request, receipts, records, snapshot)
    for mode, receipt in zip(('generation', 'review', 'explanation_review'), receipts):
        native = inputs.json(work / mode / 'state/runtime-receipt.json')
        require({key: value for key, value in receipt.items() if key != 'model_request_ordinals'} == native, 'trial_native_receipt_mismatch')
        profiles.append(runtime_profile(inputs, work / mode, receipt))
        correction = runtime_recovery(inputs, work / mode, receipt, records)
        if correction is not None:
            recoveries.append(correction)
    generated = inputs.json(work / 'generation/state/validated-candidate.json')
    require(generated == package, 'generation_candidate_differs_from_package')
    review = inputs.json(work / 'review/state/validated-candidate.json')
    explanation = inputs.json(work / 'explanation_review/state/validated-candidate.json')
    blind_input_path = work / 'explanation_review/inputs/blindReview.json'
    require(inputs.json(blind_input_path) == review, 'explanation_input_is_not_exact_blind_review')
    require(receipts[2].get('explanation_review_module_sha256') == snapshot['tools/ielts_explanation_review.py'] and
            receipts[2].get('blind_review_input_sha256') == sha(blind_input_path), 'explanation_module_or_input_binding_missing')
    for receipt in receipts:
        for field, filename in [('plugin_sha256', 'tools/harness_ielts_plugin.mjs'),
                                ('schema_module_sha256', 'tools/ielts_json_schema.py'),
                                ('candidate_module_sha256', 'tools/ielts_candidate.py')]:
            require(receipt.get(field) == snapshot[filename], 'trial_runtime_source_changed')
    for mode, receipt in zip(('generation', 'review', 'explanation_review'), receipts):
        plugin = inputs.json(work / mode / 'state/plugin-receipt.json')
        require(receipt.get('tool_receipt') == plugin, 'runtime_plugin_receipt_mismatch')
        exact(plugin, {'mode': mode, 'pluginReady': True, 'globalGuard': True, 'registrationIdentityVerified': True,
            'candidateSchemaStrict': True, 'candidateCompilerReady': True, 'deniedCount': 0, 'identityFailures': 0}, 'runtime_plugin_gate_failed')
        # Required execution comes only from the fixed host helper. All three
        # tools stay registered; neither model output nor manifest selects policy.
        required_tools = list(required_stage_tools(mode))
        require(receipt.get('required_tools') == required_tools, 'native_required_tools_mismatch')
        require(plugin.get('toolNames') == list(HARNESS_TOOL_NAMES) and
                plugin.get('requiredTools') == required_tools, 'plugin_tool_policy_mismatch')
        validate_stage_tool_receipt(mode, plugin)
        manifest_path = work / mode / 'inputs/manifest.json'
        manifest = inputs.json(manifest_path)
        require(sha(manifest_path) == receipt.get('manifest_sha256') == plugin.get('manifestSha256'), 'runtime_manifest_binding')
        exact(manifest, {'schema': 'lexicon.ielts.harness.manifest.v1', 'mode': mode}, 'runtime_manifest_mode')
        runtime_delivery(inputs, work / mode, receipt, manifest)
        runtime_sources = {'contract': 'tools/ielts_contract.py', 'schemaModule': 'tools/ielts_json_schema.py',
            'candidateModule': 'tools/ielts_candidate.py', 'corpusModule': 'tools/ielts_corpus.py', 'corpus': REFERENCE}
        if 'writing_task1_request' in request or 'visualModule' in manifest['files']:
            runtime_sources['visualModule'] = 'tools/ielts_visual.py'
            require(receipt.get('visual_module_sha256') == snapshot['tools/ielts_visual.py'] and
                    plugin.get('visualModuleSha256') == receipt.get('visual_module_sha256'), 'visual_runtime_binding')
        if mode != 'generation':
            runtime_sources['reviewModule'] = 'tools/ielts_review.py'
        if mode == 'explanation_review':
            runtime_sources['explanationReviewModule'] = 'tools/ielts_explanation_review.py'
        for key, source_name in runtime_sources.items():
            frozen = work / mode / 'inputs' / PurePosixPath(source_name).name
            inputs.bytes(frozen)
            require(sha(frozen) == snapshot[source_name] == manifest['files'][key]['sha256'], 'runtime_frozen_source_changed')
        frozen_plugin = work / mode / 'inputs/harness_ielts_plugin.mjs'
        inputs.bytes(frozen_plugin)
        require(sha(frozen_plugin) == receipt['plugin_sha256'], 'runtime_frozen_plugin_changed')
        for key, expected in [('request', request), *([('package', package)] if mode != 'generation' else []),
                              *([('blindReview', review)] if mode == 'explanation_review' else [])]:
            frozen = work / mode / 'inputs' / (key + '.json')
            require(inputs.json(frozen) == expected and sha(frozen) == manifest['files'][key]['sha256'], 'runtime_frozen_input_changed')
        candidate = inputs.json(work / mode / 'state/validated-candidate.json')
        require(canonical_sha256(candidate) == receipt.get('candidate_sha256') == plugin.get('candidateSha256'), 'runtime_candidate_changed')
        exact(plugin, {'schemaModuleSha256': snapshot['tools/ielts_json_schema.py'],
                       'candidateModuleSha256': snapshot['tools/ielts_candidate.py']}, 'runtime_plugin_source_changed')
        require(receipt.get('network_boundary') == 'macos_seatbelt_loopback_model_route', 'runtime_network_boundary_missing')
        if mode == 'generation':
            require('reviewId' not in manifest, 'generation_review_identity_present')
        else:
            require(manifest.get('reviewId') == (review if mode == 'review' else explanation)['review_id'], 'runtime_review_identity_changed')
        if mode == 'explanation_review':
            require(plugin.get('explanationReviewModuleSha256') == snapshot['tools/ielts_explanation_review.py'] and
                    plugin.get('blindReviewInputSha256') == sha(blind_input_path), 'explanation_plugin_binding')
    # Pure validators recheck three independent sessions, native candidate and
    # model request bindings, blind answers and source-faithful explanations.
    checked = verified_output(request, package, review, review['review_id'], receipts, records,
                              explanation_review=explanation, explanation_review_id=explanation['review_id'], rejected_round=rejected)
    require(checked['request'] == request and checked['package'] == package, 'trial_material_changed')
    expected = {key: value for key, value in checked['provenance'].items() if key != 'created_at'}
    actual = {key: value for key, value in provenance.items() if key != 'created_at'}
    require(expected == actual, 'trial_provenance_mismatch')
    require(len({provenance.get(k) for k in ('generation_session_id', 'review_session_id', 'explanation_review_session_id')}) == 3,
            'trial_sessions_not_independent')
    require(provenance['request_sha256'] == canonical_sha256(request) and provenance['package_sha256'] == canonical_sha256(package), 'trial_digest_mismatch')
    return {'job_id': job_id, 'request_sha256': provenance['request_sha256'], 'package_sha256': provenance['package_sha256'],
            'artifact_bytes_sha256': hashlib.sha256(artifact_bytes).hexdigest(), 'real_provider_calls': real_count,
            'request_id': request['request_id'], 'provenance_sha256': canonical_sha256(provenance),
            'review_sha256': provenance['review_sha256'], 'explanation_review_sha256': provenance['explanation_review_sha256'],
            'generation_session_id': provenance['generation_session_id'], 'review_session_id': provenance['review_session_id'],
            'explanation_review_session_id': provenance['explanation_review_session_id'],
            'runtime_execution_profiles': profiles,
            'runtime_corrections': recoveries,
            'quality_revision_count': int(rejected is not None),
            'reading_answers': {q['question_id']: q['answer'] for q in package['reading_questions']},
            'writing_task2_id': package['writing_task2']['task_id'],
            'provenance_scope': 'host_runtime_and_provider_receipts', 'runtime_executed_on_android': False}


def content_review(inputs, path, trial):
    receipt = inputs.json(path)
    exact(receipt, {'schema': 'lexicon.ielts.manual-content-review.v1', 'passed': True, 'content_status': 'accepted',
        'original_package_modified': False}, 'content_review_not_passed')
    require(receipt.get('blocking_items') == [], 'content_review_has_blockers')
    for field in ('job_id', 'request_sha256', 'package_sha256', 'artifact_bytes_sha256', 'provenance_sha256',
                  'review_sha256', 'explanation_review_sha256'):
        require(receipt.get(field) == trial[field], 'content_review_identity_mismatch')
    exact(receipt.get('reviewer'), {'method': 'development_code_assistant_review',
        'human_test_taker_trial': False, 'human_examiner_certification': False}, 'content_review_scope_mismatch')
    blind = receipt.get('blind_review')
    exact(blind, {'both_answer_sets_frozen_before_their_reference_review': True,
                 'reading_answer_agreement': True, 'all_four_answers_unique': True}, 'content_review_blind_boundary')
    require(blind.get('primary_answers') == trial['reading_answers'] and blind.get('peer_answers') == trial['reading_answers'],
            'content_review_blind_answers_differ')
    items = receipt.get('items')
    ids = set(trial['reading_answers']) | {trial['writing_task2_id']}
    require(isinstance(items, list) and len(items) == len(ids) and {item.get('item_id') for item in items} == ids, 'content_review_item_coverage')
    for item in items:
        require(isinstance(item.get('reason'), str) and item['reason'].strip(), 'content_review_reason_missing')
        if item['item_id'] in trial['reading_answers']:
            answer = trial['reading_answers'][item['item_id']]
            exact(item, {'answer_status': 'accepted', 'explanation_status': 'accepted', 'unique_answer': True,
                         'reference_answer': answer, 'blind_answer': answer}, 'content_review_item_rejected')
        else:
            require(item.get('status') in {'accepted', 'accepted_with_nonblocking_design_observation'}, 'content_review_writing_rejected')
    return {'passed': True, 'package_sha256': trial['package_sha256'], 'receipt_sha256': sha(path),
            'method': 'development_code_assistant_review', 'human_examiner_certification': False}


def receipt_path(location):
    path = location.resolve(strict=True)
    return path / 'receipt.json' if path.is_dir() else path


def validate_pictures(inputs, path, pictures, minimum):
    require(isinstance(pictures, list) and minimum <= len(pictures) <= 128, 'ui_screenshots_missing')
    names = set()
    for item in pictures:
        name = item.get('file')
        require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*\.png', name) and name not in names, 'ui_screenshot_name_invalid')
        names.add(name)
        picture = path.parent / name
        require(picture.resolve(strict=True).parent == path.parent.resolve(), 'ui_screenshot_outside_receipt_directory')
        raw = inputs.bytes(picture, 30_000_000)
        require(raw.startswith(b'\x89PNG\r\n\x1a\n') and hashlib.sha256(raw).hexdigest() == item.get('sha256'), 'ui_screenshot_digest_mismatch')
    return len(pictures)


def online_ui(inputs, location, trial):
    path = receipt_path(location)
    receipt = inputs.json(path)
    exact(receipt, {'schema': 'lexicon.ielts.online-ui-trial.v1', 'status': 'passed', 'stage': 'complete',
        'request_id': trial['request_id'], 'package_sha256': trial['package_sha256'], 'failure_type': None,
        'database_scope': 'isolated_in_memory_room',
        'tls_scope': 'explicitly_imported_personal_leaf_with_default_hostname_validation',
        'transport': 'adb_reverse_loopback', 'provider_execution': 'foreground_mac_harness_host',
        'real_file_picker': True, 'source_kind': 'self_authored_fictional',
        'answer_entry': 'arbitrary_valid_test_answers', 'human_comprehension_measured': False,
        'official_band_score_assigned': False, 'viewmodels_created': 3}, 'production_https_ui_not_passed')
    require(type(receipt.get('reuses_explicitly_completed_host_request')) is bool, 'online_request_origin_missing')
    require(isinstance(receipt.get('certificate_sha256'), str) and SHA.fullmatch(receipt['certificate_sha256']), 'online_certificate_identity_missing')
    steps = {'system_file_selected_and_public_identity_confirmed', 'production_https_health_and_keystore_restore',
        'one_original_request_completed_and_package_validated', 'answers_and_short_writing_saved_before_exit',
        'restored_draft_and_persisted_submission_before_reference', 'submitted_review_restored_without_duplicate_commit'}
    require(steps <= set(receipt.get('completed_steps', [])), 'online_ui_steps_missing')
    count = validate_pictures(inputs, path, receipt.get('screenshots'), 6)
    return {'status': 'passed', 'receipt_sha256': sha(path), 'screenshots': count,
            'scope': 'production_https_with_imported_leaf_over_adb_reverse',
            'completed_host_request_reused': receipt['reuses_explicitly_completed_host_request'],
            'request_id': trial['request_id'], 'package_sha256': trial['package_sha256'], 'human_comprehension_measured': False}


def ui_trial(inputs, location, variant, trial):
    path = location.resolve(strict=True)
    if path.is_dir():
        path = path / 'receipt.json'
    receipt = inputs.json(path)
    require(receipt.get('schema') == 'lexicon.ielts.material-ui-trial.v1' and receipt.get('status') == 'passed' and
            receipt.get('stage') == 'complete' and receipt.get('variant') == variant and receipt.get('font_scale') == 2, 'real_material_ui_not_passed')
    for field in ('request_sha256', 'package_sha256', 'artifact_bytes_sha256'):
        require(receipt.get(field) == trial[field], 'ui_material_binding_mismatch')
    require(receipt.get('provenance_kind') == 'deepseek_harness' and receipt.get('provenance_scope') == 'paired_host_receipt'
            and receipt.get('provider_requests_claimed_by_artifact') == trial['real_provider_calls'], 'ui_provenance_mismatch')
    expected = {'material_origin': 'operator_supplied_harness_artifact', 'synthetic_question_fallback_used': False,
                'provider_execution_independently_verified': False, 'runtime_executed_on_android': False,
                'network_operations_by_test': 0, 'forbidden_operation_attempts': 0,
                'database_scope': 'isolated_in_memory_room', 'viewmodels_created': 3,
                'answer_entry': 'automatic_from_artifact_reference_key', 'human_answer_accuracy_measured': False,
                'writing_origin': 'self_authored_short_ui_draft', 'official_band_score_assigned': False,
                'online_generation_success_rate_measured': False,
                'artifact_review_coverage_claimed': 'blind_answers_and_explanation_faithfulness',
                'explanation_review_independently_verified_by_test': False}
    require(all(type(receipt.get(key)) is type(value) and receipt[key] == value for key, value in expected.items()), 'ui_scope_mismatch')
    require(STEPS <= set(receipt.get('completed_steps', [])), 'ui_steps_missing')
    require(type(receipt.get('writing_words')) is int and 0 < receipt['writing_words'] < 250, 'ui_short_writing_boundary_missing')
    layouts = receipt.get('text_layout_checks')
    require(isinstance(layouts, list) and layouts and all(row.get('font_scale') == 2 and row.get('complete_text_laid_out') is True and
            row.get('line_bounds_fit') is True for row in layouts), 'ui_layout_evidence_missing')
    pictures = receipt.get('screenshots')
    require(isinstance(pictures, list) and 10 <= len(pictures) <= 128, 'ui_screenshots_missing')
    names = set()
    for item in pictures:
        name = item.get('file')
        require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*\.png', name) and name not in names, 'ui_screenshot_name_invalid')
        names.add(name)
        picture = path.parent / name
        require(picture.resolve(strict=True).parent == path.parent.resolve(), 'ui_screenshot_outside_receipt_directory')
        raw = inputs.bytes(picture, 30_000_000)
        require(raw.startswith(b'\x89PNG\r\n\x1a\n') and hashlib.sha256(raw).hexdigest() == item.get('sha256'), 'ui_screenshot_digest_mismatch')
    return {'variant': variant, 'font_scale': 2, 'status': 'passed', 'screenshots': len(pictures),
            'receipt_sha256': sha(path), 'scope': 'real_generated_material_with_offline_in_memory_database'}


def secret_patterns():
    with (ROOT / 'API.txt').open('rb') as stream:
        raw = stream.read(4097)
    require(len(raw) <= 4096, 'configured_key_bound')
    key = raw.decode('utf-8-sig').strip()
    require(re.fullmatch(r'sk-[A-Za-z0-9_-]{16,200}', key) is not None, 'configured_key_format')
    return [key.encode(encoding) for encoding in ('utf-8', 'utf-16le', 'utf-16be')], hashlib.sha256(raw).hexdigest()


def scan_stream(stream, patterns):
    digest = hashlib.sha256(); tail = b''; first = b''
    overlap = max(map(len, patterns)) - 1
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        if not first:
            first = block[:16]
        require(all(pattern not in tail + block for pattern in patterns), 'credential_found_value_withheld')
        digest.update(block); tail = (tail + block)[-overlap:]
    return digest.hexdigest(), first


def scan_archive(path, patterns, *, source=False, dictionary_inputs=None, no_dictionary=False):
    dictionaries = 0
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        require(len({item.filename for item in members}) == len(members), 'duplicate_archive_member')
        require(sum(item.file_size for item in members) <= 2_000_000_000, 'archive_size_bound')
        for item in members:
            if item.is_dir():
                continue
            if source:
                validate_relative(item.filename)
            require(not stat.S_ISLNK(item.external_attr >> 16), 'archive_symlink')
            with archive.open(item) as stream:
                digest, first = scan_stream(stream, patterns)
            suffix = PurePosixPath(item.filename).suffix.lower()
            if source:
                require(first != b'SQLite format 3\x00', 'source_contains_database')
            if suffix in {'.mdx', '.mdd'}:
                require(not source and not no_dictionary, 'private_dictionary_in_source_rebuild')
                require(dictionary_inputs is not None and digest in dictionary_inputs[suffix], 'apk_dictionary_input_mismatch')
                dictionaries += 1
    return dictionaries


def run_output(command, env):
    result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    require(result.returncode == 0, 'android_tool_failed')
    return result.stdout.decode('utf-8', errors='replace')


def identity(apk, sdk, env):
    output = run_output([str(sdk / 'build-tools/36.0.0/aapt'), 'dump', 'badging', str(apk)], env)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output, re.MULTILINE)
    require(package is not None and package.groups() == ('dev.lexicon.ide', str(VERSION_CODE), VERSION), 'apk_identity_mismatch')
    require(re.search(r"^sdkVersion:'36'$", output, re.MULTILINE) and re.search(r"^targetSdkVersion:'36'$", output, re.MULTILINE), 'apk_sdk_mismatch')
    return {'application_id': 'dev.lexicon.ide', 'version': VERSION, 'version_code': VERSION_CODE, 'min_sdk': 36, 'target_sdk': 36}


def signature(apk, sdk, env):
    output = run_output([str(sdk / 'build-tools/36.0.0/apksigner'), 'verify', '--print-certs', str(apk)], env)
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', output, re.MULTILINE)
    require(len(digests) == 1, 'apk_signer_count')
    return digests[0].lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trial-dir', required=True, type=Path)
    parser.add_argument('--online-ui', required=True, type=Path)
    parser.add_argument('--release-checks', required=True, type=Path)
    parser.add_argument('--ui-night', required=True, type=Path)
    parser.add_argument('--content-review', required=True, type=Path)
    args = parser.parse_args()
    names = source_names()
    snapshot = {name: sha(ROOT / name) for name in names}
    inputs = Inputs()
    inputs.bytes(Path(__file__).resolve(), 200_000)
    trial = real_trial(inputs, args.trial_dir, snapshot)
    reviewed = content_review(inputs, args.content_review, trial)
    # Content-review matching needs answer values only in memory, never in release receipts.
    trial.pop('reading_answers'); trial.pop('writing_task2_id')
    online = online_ui(inputs, args.online_ui, trial)
    ui = [ui_trial(inputs, args.ui_night, 'night', trial)]
    checks = prerequisites(inputs, args.release_checks, snapshot, receipt_path(args.online_ui), receipt_path(args.ui_night))
    inputs.unchanged(); assert_sources(snapshot)
    patterns, key_file_sha = secret_patterns()
    for evidence_file in list(inputs.hashes):
        with evidence_file.open('rb') as stream:
            scan_stream(stream, patterns)
    dictionary_inputs = {suffix: {sha(path) for path in (ROOT / 'data/dic').rglob('*') if path.is_file() and path.suffix.lower() == suffix}
                         for suffix in ('.mdx', '.mdd')}
    require(dictionary_inputs['.mdx'], 'personal_mdx_input_missing')
    source_name = 'Lexicon-IDE-1.36.0-source.zip'
    apk_name = 'Lexicon-IDE-1.36.0-android16-debug.apk'
    final_source, final_apk = ROOT / 'dist' / source_name, ROOT / 'dist' / apk_name
    final_manifest = ROOT / 'dist/manifest-1.36.0.json'
    require(not any(path.exists() or path.is_symlink() for path in (final_source, final_apk, final_manifest)), 'release_destination_exists')
    run_id = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
    release = EVIDENCE / ('release-136-' + run_id)
    stage = ROOT / '.build-support' / ('release-136-stage-' + run_id)
    rebuild_root = ROOT / '.build-support' / ('source-136-rebuild-' + run_id)
    release.mkdir(mode=0o700, exist_ok=False)
    stage.mkdir(mode=0o700, exist_ok=False)
    rebuild_root.mkdir(mode=0o700, exist_ok=False)
    source, apk = stage / source_name, stage / apk_name
    env = os.environ.copy()
    env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'
    env['ANDROID_HOME'] = '/opt/homebrew/share/android-commandlinetools'
    env['GRADLE_USER_HOME'] = str(ROOT / '.build-support/gradle-user-home')
    sdk = Path(env['ANDROID_HOME'])
    current_apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    apk_hash = sha(current_apk)
    require(apk_hash == checks['installed_apk_sha256'], 'tested_apk_changed_before_staging')
    try:
        with zipfile.ZipFile(source, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in names:
                archive.write(ROOT / name, name)
        assert_sources(snapshot)
        shutil.copyfile(current_apk, apk)
        require(sha(apk) == apk_hash and sha(current_apk) == apk_hash, 'apk_changed_during_staging')
        require(scan_archive(source, patterns, source=True) == 0, 'source_dictionary_found')
        dictionary_count = scan_archive(apk, patterns, dictionary_inputs=dictionary_inputs)
        require(dictionary_count > 0, 'apk_private_dictionary_missing')
        apk_identity = identity(apk, sdk, env)
        signer = signature(OLD_APK, sdk, env)
        require(signature(apk, sdk, env) == signer, 'apk_signer_changed_from_135')
        write_new(release / 'source-manifest.json', {'sources': snapshot, 'source_zip_sha256': sha(source),
            'apk_sha256': apk_hash, 'evidence_files': inputs.manifest(), 'real_trial': trial, 'content_review': reviewed, 'online_ui': online, 'ui_trials': ui})
        write_new(release / 'staged-checks.json', {'status': 'staged', 'apk_identity': apk_identity,
            'key_scan': True, 'source_excludes_private_material': True, 'source_files': len(names),
            'personal_dictionary_assets_match_inputs': dictionary_count, 'same_signing_certificate_as_135': True,
            'prerequisites': checks, 'real_trial': trial, 'content_review': reviewed, 'online_ui': online, 'ui_trials': ui})
        with zipfile.ZipFile(source) as archive:
            archive.extractall(rebuild_root)  # Every member was validated against the fixed source allowlist.
        (rebuild_root / 'gradlew').chmod(0o755)
        started = time.monotonic()
        log = release / 'source-rebuild.log'
        with log.open('x') as stream:
            rebuilt = subprocess.run(['./gradlew', '--offline', ':app:assembleDebug', '--console=plain'],
                cwd=rebuild_root, env=env, stdout=stream, stderr=subprocess.STDOUT, check=False)
        with log.open('rb') as stream:
            scan_stream(stream, patterns)
        rebuilt_apk = rebuild_root / 'app/build/outputs/apk/debug/app-debug.apk'
        rebuild = {'status': 'passed' if rebuilt.returncode == 0 else 'failed', 'exit_code': rebuilt.returncode,
            'offline': True, 'clean_directory': True, 'elapsed_seconds': round(time.monotonic() - started, 2),
            'source_zip_sha256': sha(source), 'external_prerequisites': 'JDK17, Android SDK and preexisting Gradle dependency cache'}
        if rebuilt.returncode == 0:
            identity(rebuilt_apk, sdk, env)
            require(signature(rebuilt_apk, sdk, env) == signer, 'rebuilt_signer_mismatch')
            require(scan_archive(rebuilt_apk, patterns, no_dictionary=True) == 0, 'rebuilt_private_dictionary')
            rebuild.update(rebuilt_apk_sha256=sha(rebuilt_apk), no_private_dictionary=True, key_scan=True)
        write_new(release / 'source-rebuild.json', rebuild)
        require(rebuilt.returncode == 0, 'source_rebuild_failed')
        assert_sources(snapshot); inputs.unchanged()
        require(sha(ROOT / 'API.txt') == key_file_sha, 'configured_key_changed_during_packaging')
        require(sha(current_apk) == apk_hash and sha(OLD_SOURCE) == OLD_SOURCE_SHA and sha(OLD_APK) == OLD_APK_SHA, 'release_input_changed')
        with zipfile.ZipFile(source) as archive:
            require(all(hashlib.sha256(archive.read(name)).hexdigest() == digest for name, digest in snapshot.items()), 'source_zip_snapshot_mismatch')
        # Hard links publish validated bytes atomically on this workspace filesystem;
        # link() fails if a destination exists and never replaces earlier deliveries.
        for staged, destination in ((source, final_source), (apk, final_apk)):
            os.link(staged, destination)
            destination.chmod(0o444)
            require(sha(destination) == sha(staged), 'published_artifact_digest_mismatch')
        manifest = {'schema': 'lexicon.release.v1', 'status': 'published', 'version': VERSION, 'version_code': VERSION_CODE,
            'artifacts': [{'path': path.name, 'bytes': path.stat().st_size, 'sha256': sha(path)} for path in (final_apk, final_source)],
            'apk_identity': apk_identity, 'source_independent_offline_rebuild': 'passed', 'same_signing_certificate_as_135': True,
            'key_scan': True, 'source_excludes_private_material': True, 'rebuilt_without_private_dictionary': True,
            'real_model_calls': trial['real_provider_calls'], 'real_model_call_scope': 'the selected successful trial only',
            'ielts_host_real_package_verified': True, 'ielts_generation_in_android': False, 'runtime_executed_on_android': False,
            'real_https_pairing_verified': True, 'magicos_tested': False,
            'original_device_data_preservation': 'current_user0_baseline_only; former user10 unverified',
            'device_trial_scope': 'production HTTPS online UI plus exact-package offline night/font200; isolated in-memory Room',
            'human_answer_accuracy_measured': False, 'online_generation_success_rate_measured': False,
            'prerequisites': checks, 'real_trial': trial, 'content_review': reviewed, 'online_ui': online, 'ui_trials': ui,
            'checks_sha256': sha(release / 'staged-checks.json'), 'rebuild_sha256': sha(release / 'source-rebuild.json'),
            'source_manifest_sha256': sha(release / 'source-manifest.json')}
        write_new(release / 'release-checks.json', manifest)
        write_new(final_manifest, manifest)
        final_manifest.chmod(0o444)
        print(json.dumps({'status': 'published', 'version': VERSION, 'manifest': str(final_manifest),
                          'evidence': str(release), 'real_model_calls': trial['real_provider_calls']}, ensure_ascii=False))
        return 0
    except Exception as error:
        write_new(release / 'failure.json', {'status': 'failed', 'error': error.code if isinstance(error, PackageError) else type(error).__name__,
                                          'prior_deliveries_modified': False, 'model_calls_by_packaging': 0})
        raise


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({'status': 'rejected', 'error': error.code if isinstance(error, PackageError) else type(error).__name__,
                          'model_calls_by_packaging': 0}), file=sys.stderr)
        raise SystemExit(1)
