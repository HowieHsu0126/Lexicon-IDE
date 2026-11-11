"""Owned, bounded DeepSeek Harness processes for original IELTS practice.

The Android client must call a paired host. This module runs on that host, not on
Android. One process/session per stage; no resume, silent fallback, or automatic
retry after uncertain completion. Real calls use the native DeepSeek adapter via
the credential-isolating loopback route.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import selectors
import signal
import shutil
import stat
import subprocess
import sys
import threading
import time

from harness_model_proxy import (MAX_PROVIDER_DEADLINE_SECONDS, PROVIDER_DEADLINE_SECONDS,
                                 decode_object)


MAC_DSH = Path('/Applications/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh')
RUNTIME_VERSION = '0.2.1-rc.5'
REVIEW_MODELS = {'flash': 'deepseek-flash', 'pro': 'deepseek-v4-pro'}
GENERATION_MODELS = dict(REVIEW_MODELS)
REASONING_EFFORTS = ('off', 'low', 'high', 'max')
HARNESS_TOOL_NAMES = ('lexicon_ielts_retrieve', 'lexicon_ielts_read', 'lexicon_ielts_validate')
LEGACY_DELIVERY = 'assistant_digest_confirmation'
NATIVE_DELIVERY = 'native_tool_conclusion'
DELIVERY_KINDS = (LEGACY_DELIVERY, NATIVE_DELIVERY)
MAX_STAGE_TOOL_CALLS = 24
RUNTIME_ROOT = Path('/Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh/node_modules')
MAX_PROCESS_OUTPUT = 4_000_000
TOOLS_ROOT = Path(__file__).resolve().parent
BRIDGE_PYTHON = Path('/Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework/Versions/3.9/Resources/Python.app/Contents/MacOS/Python')
TOOL_JSON_REPAIR_PROMPT = ('The previous response was fully received, but a tool argument was invalid JSON. '
    'No candidate was saved and no validation was attempted. Keep the completed read-only results in this session. '
    'Submit a new lexicon_ielts_validate call with the complete valid JSON '
    'for this same frozen review. Re-check every required field and all source evidence. Do not change the supplied '
    'passage, questions or frozen inputs. Do not merely send missing punctuation or repeat the malformed text. '
    'After validation succeeds, finish with exactly {"candidate_sha256":"<digest returned by the tool>"}.')


class HarnessError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def candidate_delivery_kind(receipt):
    kind = receipt.get('delivery_kind', LEGACY_DELIVERY)
    if kind not in DELIVERY_KINDS:
        raise HarnessError('candidate_delivery_invalid')
    return kind


def tool_json_repair_prompt(delivery_kind):
    if delivery_kind not in DELIVERY_KINDS:
        raise HarnessError('candidate_delivery_invalid')
    if delivery_kind == LEGACY_DELIVERY:
        return TOOL_JSON_REPAIR_PROMPT
    return TOOL_JSON_REPAIR_PROMPT[:TOOL_JSON_REPAIR_PROMPT.index('After validation succeeds')] + (
        'Successful validation commits the candidate and concludes this native turn. '
        'Make it your final tool call; do not request later tools in the same batch or an assistant confirmation.')


def validate_candidate_delivery_receipt(receipt):
    """Verify the declared output authority without reinterpreting old final text."""
    import re
    kind = candidate_delivery_kind(receipt)
    error = 'runtime_binding_mismatch'
    plugin = receipt.get('tool_receipt')
    if not isinstance(plugin, dict) or plugin.get('candidateDelivery', LEGACY_DELIVERY) != kind:
        raise HarnessError(error)
    if kind == LEGACY_DELIVERY:
        if (receipt.get('candidate_confirmation_verified') is not True or
                'native_tool_conclusion_verified' in receipt or plugin.get('nativeToolConclusion') is not None):
            raise HarnessError(error)
        return
    validate_stage_tool_receipt(receipt.get('stage'), plugin)
    fields = {'schema', 'sessionId', 'turnId', 'stepId', 'callId', 'callEventSeq', 'eventSeq',
              'toolName', 'allowedOrdinal', 'candidateSha256', 'candidateBytes',
              'toolsResultObserved', 'sessionEventCommitted'}
    result = plugin.get('nativeToolConclusion')
    if (receipt.get('native_tool_conclusion_verified') is not True or
            'candidate_confirmation_verified' in receipt or 'candidate_confirmation_prefix_bytes' in receipt or
            not isinstance(result, dict) or set(result) != fields or
            result['schema'] != 'lexicon.ielts.native-tool-conclusion.v1'):
        raise HarnessError(error)
    for key in ('turnId', 'stepId', 'allowedOrdinal', 'candidateBytes'):
        if type(result[key]) is not int or not 1 <= result[key] <= 2**53 - 1:
            raise HarnessError(error)
    if (type(result['callEventSeq']) is not int or type(result['eventSeq']) is not int or
            not 0 <= result['callEventSeq'] < result['eventSeq'] <= 2**53 - 1 or
            not isinstance(result['callId'], str) or re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,255}', result['callId']) is None or
            not isinstance(result['sessionId'], str) or re.fullmatch(r'session-[A-Za-z0-9._:-]{1,120}', result['sessionId']) is None or
            result['sessionId'] != receipt.get('session_id') or result['toolName'] != 'lexicon_ielts_validate' or
            type(receipt.get('tool_calls')) is not int or
            result['allowedOrdinal'] != plugin['allowedCount'] or result['allowedOrdinal'] != receipt['tool_calls'] or
            result['toolsResultObserved'] is not True or result['sessionEventCommitted'] is not True or
            not isinstance(result['candidateSha256'], str) or re.fullmatch(r'[a-f0-9]{64}', result['candidateSha256']) is None or
            result['candidateSha256'] != plugin.get('candidateSha256') or
            result['candidateSha256'] != receipt.get('candidate_sha256') or
            result['candidateSha256'] != receipt.get('response_sha256') or
            type(plugin.get('candidateBytes')) is not int or type(receipt.get('candidate_bytes')) is not int or
            not 0 < result['candidateBytes'] == plugin['candidateBytes'] == receipt['candidate_bytes'] <= 200_000 or
            any(type(receipt.get(k)) is not int or receipt[k] != v for k, v in
                (('exit_code', 0), ('errors', 0), ('malformed_frames', 0), ('turn_count', 1), ('terminal_count', 1), ('final_count', 1))) or
            receipt.get('terminal') != 'completed' or receipt.get('status') != 'completed' or
            receipt.get('process_reaped') is not True or receipt.get('input_write_failed') is not False or
            receipt.get('stop_reason') is not None):
        raise HarnessError(error)


def required_stage_tools(mode):
    """Host-owned requirement; all three tools remain available in every stage."""
    if mode == 'generation':
        return HARNESS_TOOL_NAMES
    if mode in ('review', 'explanation_review'):
        return ('lexicon_ielts_validate',)
    raise HarnessError('stage_invalid')


def validate_stage_tool_receipt(mode, receipt):
    """Check actual tool counters without inventing retrieval for frozen-input reviews."""
    required = required_stage_tools(mode)
    error = 'tool_policy_or_retrieval_incomplete'
    if (not isinstance(receipt, dict) or receipt.get('requiredTools') != list(required) or
            receipt.get('mode') != mode or receipt.get('toolNames') != list(HARNESS_TOOL_NAMES)):
        raise HarnessError(error)
    counters = []
    for field in ('perTool', 'completedPerTool'):
        values = receipt.get(field)
        if (not isinstance(values, dict) or set(values) != set(HARNESS_TOOL_NAMES) or
                any(type(values[name]) is not int or values[name] < 0 for name in HARNESS_TOOL_NAMES)):
            raise HarnessError(error)
        counters.append(values)
    attempted, completed = counters
    inherited = receipt.get('carriedCompletedPerTool', {name: 0 for name in HARNESS_TOOL_NAMES})
    if (not isinstance(inherited, dict) or set(inherited) != set(HARNESS_TOOL_NAMES) or
            any(type(value) is not int or value < 0 for value in inherited.values()) or
            inherited['lexicon_ielts_validate'] != 0):
        raise HarnessError(error)
    if 'carriedCompletedPerTool' in receipt and (mode != 'generation' or
            sum(inherited.values()) != receipt.get('carriedToolCalls') or
            not isinstance(receipt.get('continuationSha256'), str) or len(receipt['continuationSha256']) != 64):
        raise HarnessError(error)
    for field in ('allowedCount', 'deniedCount', 'completedCount', 'failedCount',
                  'registrationChecks', 'identityFailures'):
        if type(receipt.get(field)) is not int or receipt[field] < 0:
            raise HarnessError(error)
    if (receipt['allowedCount'] != sum(attempted.values()) or
            receipt['completedCount'] != sum(completed.values()) or
            receipt['failedCount'] > receipt['allowedCount'] or
            receipt['deniedCount'] != 0 or receipt['identityFailures'] != 0 or
            any(completed[name] > attempted[name] for name in HARNESS_TOOL_NAMES) or
            any(completed[name] + inherited[name] < 1 for name in required)):
        raise HarnessError(error)
    carried = receipt.get('carriedToolCalls', 0)
    if (type(carried) is not int or not 0 <= carried <= MAX_STAGE_TOOL_CALLS or
            carried + receipt['allowedCount'] > MAX_STAGE_TOOL_CALLS):
        raise HarnessError(error)


def validate_stage_model(mode, model, reasoning_effort):
    models = GENERATION_MODELS if mode == 'generation' else REVIEW_MODELS
    if mode not in ('generation', 'review', 'explanation_review') or model not in models.values() or reasoning_effort not in REASONING_EFFORTS:
        raise HarnessError('stage_model_invalid')
    if mode == 'generation' and reasoning_effort != 'off':
        raise HarnessError('generation_reasoning_fixed')


def stage_execution_limits(reasoning_effort):
    """Host policy: thinking shares the output cap and needs its own bounded wait."""
    if reasoning_effort not in REASONING_EFFORTS:
        raise HarnessError('stage_model_invalid')
    deadline = PROVIDER_DEADLINE_SECONDS if reasoning_effort == 'off' else MAX_PROVIDER_DEADLINE_SECONDS
    return {'token_limit': 8192 if reasoning_effort == 'off' else 16384,
            'provider_deadline_seconds': deadline, 'stream_idle_timeout_ms': (deadline + 10) * 1000}


def validate_stage_execution_limits(value, reasoning_effort):
    expected = stage_execution_limits(reasoning_effort)
    if any(type(value.get(key)) is not int or value[key] != setting for key, setting in expected.items()):
        raise HarnessError('runtime_budget_binding_mismatch')
    return expected


def validate_effective_model_profile(raw, *, model, reasoning_effort, limits):
    """Read only the fixed runtime's scalar YAML form; never evaluate profile data.

    Reject duplicate/ambiguous relevant rows, keys, model entries or YAML syntax
    outside the pinned shape rather than guessing at the runtime's effective cap.
    """
    import re
    validate_stage_execution_limits(limits, reasoning_effort)
    try:
        entries = re.split(r'(?m)^- id: ', raw.decode('utf-8'))[1:]
    except (UnicodeError, AttributeError):
        raise HarnessError('profile_model_limits_mismatch') from None
    def row(name):
        found = [entry for entry in entries if entry.splitlines()[0].strip() == name]
        if len(found) != 1 or re.search(r'(?m)^  disabled: true\s*$', found[0]):
            raise HarnessError('profile_model_limits_mismatch')
        # A second inline/quoted config or merge key can replace apparently
        # correct earlier scalars. The pinned dump has one block config.
        top = [line for line in found[0].splitlines()[1:] if line.startswith('  ') and not line.startswith('   ')]
        if (top.count('  config:') != 1 or
                any(not line.startswith('  name: ') and line != '  config:' for line in top)):
            raise HarnessError('profile_model_limits_mismatch')
        return found[0]
    def scalar(entry, key, expected, indent=4):
        # Quoted off is emitted by some YAML writers; do not permit aliases,
        # folded scalars, tagged values, duplicate keys or implicit coercion.
        found = re.findall(r'(?m)^' + ' ' * indent + re.escape(key) + r': ([^\r\n]+)$', entry)
        forms = {str(expected), "'" + str(expected) + "'", '"' + str(expected) + '"'} if isinstance(expected, str) else {str(expected)}
        if len(found) != 1 or found[0] not in forms:
            raise HarnessError('profile_model_limits_mismatch')
    default, adapter = row('agent-default-model'), row('llm-deepseek')
    def keys(entry, indent, expected, list_prefix=None):
        lines = [line for line in entry.splitlines() if line.startswith(' ' * indent) and
                 not line.startswith(' ' * (indent + 1))]
        actual = []
        for line in lines:
            if list_prefix is not None and line.startswith(list_prefix):
                continue
            key = re.fullmatch(r' {' + str(indent) + r'}([A-Za-z][A-Za-z0-9]*):(?: [^\r\n]+)?', line)
            if key is None:
                raise HarnessError('profile_model_limits_mismatch')
            actual.append(key.group(1))
        if len(actual) != len(expected) or set(actual) != set(expected):
            raise HarnessError('profile_model_limits_mismatch')
    keys(default, 4, ('provider', 'model', 'reasoningEffort'))
    keys(adapter, 4, ('baseURL', 'apiKeyEnv', 'reasoningEffort', 'maxTokens', 'streamIdleTimeoutMs', 'retryPolicy', 'models'))
    keys(adapter, 6, ('mode', 'maxRetries'), '      - id: ')
    keys(adapter, 8, ('inputModalities', 'contextWindow', 'maxTokens'))
    scalar(default, 'provider', 'deepseek-official')
    scalar(default, 'model', model)
    scalar(default, 'reasoningEffort', reasoning_effort)
    scalar(adapter, 'reasoningEffort', reasoning_effort)
    scalar(adapter, 'apiKeyEnv', 'LEXICON_DUMMY_KEY')
    scalar(adapter, 'maxTokens', limits['token_limit'])
    scalar(adapter, 'streamIdleTimeoutMs', limits['stream_idle_timeout_ms'])
    scalar(adapter, 'mode', 'normal', 6)
    scalar(adapter, 'maxRetries', 0, 6)
    if (len(re.findall(r'(?m)^    retryPolicy:\s*$', adapter)) != 1 or
            len(re.findall(r'(?m)^    models:\s*$', adapter)) != 1 or
            re.findall(r'(?m)^      - id: ([^\r\n]+)$', adapter) != [model]):
        raise HarnessError('profile_model_limits_mismatch')
    scalar(adapter, 'maxTokens', limits['token_limit'], 8)
    scalar(adapter, 'contextWindow', 1048576, 8)
    if (len(re.findall(r'(?m)^        inputModalities:\s*$', adapter)) != 1 or
            re.findall(r'(?m)^          (.+)$', adapter) != ['- text']):
        raise HarnessError('profile_model_limits_mismatch')


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json_new(path, value):
    path = Path(path)
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    path.chmod(0o600)


def parse_headless_events(raw):
    """Allowlist receipt metadata; never return reasoning or diagnostics."""
    result = {'session_id': None, 'terminal': None, 'turn_count': 0, 'final_count': 0, 'terminal_count': 0, 'tool_calls': 0,
              'usage': [], 'final_text': '', 'final_response_bytes': 0, 'malformed_frames': 0, 'errors': 0}
    try:
        lines = raw.decode('utf-8', errors='strict').splitlines()
    except UnicodeError:
        raise HarnessError('runtime_encoding') from None
    for line in lines:
        if not line.strip():
            continue
        try:
            event = decode_object(line)
        except ValueError:
            result['malformed_frames'] += 1
            continue
        kind = event.get('type')
        if kind == 'session':
            session = event.get('sessionId')
            if not isinstance(session, str) or not session.startswith('session-') or len(session) > 128:
                raise HarnessError('session_identity')
            if result['session_id'] not in (None, session):
                raise HarnessError('multiple_sessions')
            result['session_id'] = session
        elif kind == 'status':
            phase = event.get('phase')
            if phase == 'turn_start':
                result['turn_count'] += 1
                result['terminal'] = None
                result['final_text'] = ''
                result['final_response_bytes'] = 0
            elif phase == 'turn_end':
                result['terminal_count'] += 1
                reason = event.get('reason')
                result['terminal'] = reason.get('kind') if isinstance(reason, dict) else None
                # Preserve only a fixed adapter diagnosis, never arbitrary
                # provider diagnostics, generated text or reasoning.
                error = reason.get('error') if isinstance(reason, dict) else None
                if (result['terminal'] == 'error' and isinstance(error, dict) and
                        error.get('code') == 'MALFORMED_RESPONSE' and
                        error.get('message') == 'DeepSeek Messages stream: tool input is invalid JSON'):
                    result['runtime_error_code'] = 'MALFORMED_RESPONSE'
                    result['runtime_error_type'] = 'tool_input_invalid_json'
            elif phase == 'step_end' and isinstance(event.get('usage'), dict):
                usage = event['usage']
                result['usage'].append({key: value for key, value in usage.items()
                                        if key in ('inputTokens', 'outputTokens', 'totalTokens', 'cacheReadTokens',
                                                   'cacheWriteTokens', 'reasoningTokens') and type(value) is int and value >= 0})
        elif kind == 'tool_call':
            result['tool_calls'] += 1
        elif kind == 'final' and isinstance(event.get('text'), str):
            result['final_count'] += 1
            result['final_text'] = event['text']
            result['final_response_bytes'] = len(event['text'].encode('utf-8'))
        elif kind == 'error':
            result['errors'] += 1
    return result


def run_owned_process(command, *, cwd, env, prompt, timeout=240, cancelled=lambda: False):
    """Drain both pipes, cap output, and reap the owned process group on all exits."""
    if not 1 <= timeout <= 600 or len(prompt.encode('utf-8')) > 500_000:
        raise HarnessError('execution_bound')
    start = time.monotonic()
    stdout = bytearray()
    stderr_bytes = 0
    reason = None
    child = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ, 'stdout')
    selector.register(child.stderr, selectors.EVENT_READ, 'stderr')
    write_failed = []
    def send():
        try:
            child.stdin.write(prompt.encode('utf-8'))
            child.stdin.close()
        except (BrokenPipeError, OSError, ValueError):
            write_failed.append(True)
    writer = threading.Thread(target=send, daemon=True)
    writer.start()
    terminated_at = None
    try:
        while selector.get_map() or child.poll() is None:
            elapsed = time.monotonic() - start
            if reason is None:
                if cancelled(): reason = 'cancelled'
                elif elapsed > timeout: reason = 'timeout'
                elif len(stdout) > MAX_PROCESS_OUTPUT or stderr_bytes > MAX_PROCESS_OUTPUT: reason = 'output_bound'
            if reason and terminated_at is None:
                try: os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                terminated_at = time.monotonic()
            if terminated_at is not None and time.monotonic() - terminated_at > 2:
                try: os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            for key, _ in selector.select(.1):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                elif key.data == 'stdout':
                    if len(stdout) <= MAX_PROCESS_OUTPUT:
                        stdout.extend(chunk)
                else:
                    stderr_bytes += len(chunk)
            # A descendant keeping a pipe open must not keep a failed job alive forever.
            if terminated_at is not None and time.monotonic() - terminated_at > 5:
                break
        child.wait(timeout=3)
    finally:
        if child.poll() is None:
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            child.wait(timeout=3)
        selector.close()
        child.stdout.close(); child.stderr.close()
        try: child.stdin.close()
        except (OSError, ValueError): pass
        writer.join(timeout=1)
    return bytes(stdout), {'exit_code': child.returncode, 'process_reaped': child.poll() is not None,
                          'stop_reason': reason, 'elapsed_ms': round((time.monotonic() - start) * 1000),
                          'stderr_bytes_discarded': stderr_bytes, 'input_write_failed': bool(write_failed)}


def verify_native_version(cli=MAC_DSH):
    env = {key: os.environ[key] for key in ('PATH', 'LANG', 'TMPDIR') if key in os.environ}
    result = subprocess.run([str(cli), '--version'], capture_output=True, env=env, timeout=15)
    if result.returncode != 0 or result.stdout.decode().strip() != RUNTIME_VERSION:
        raise HarnessError('runtime_version_mismatch')
    return RUNTIME_VERSION


def _terminal_payload(raw, execution, *, require_text=True):
    events = parse_headless_events(raw)
    text = events.pop('final_text')
    if execution['stop_reason']:
        raise HarnessError(execution['stop_reason'])
    if (execution['exit_code'] != 0 or not execution['process_reaped'] or
            execution['input_write_failed'] or events['errors'] or events['malformed_frames'] or
            events['turn_count'] != 1 or events['final_count'] != 1 or events['terminal_count'] != 1 or
            events['terminal'] != 'completed' or not events['session_id'] or (require_text and not text.strip())):
        raise HarnessError('incomplete_runtime_result')
    return text, events


def validate_terminal(raw, execution):
    """Generic model JSON remains strict; no prefix recovery on this path."""
    text, events = _terminal_payload(raw, execution)
    try:
        value = decode_object(text.encode('utf-8'))
    except ValueError:
        raise HarnessError('model_json_invalid') from None
    return value, events


def _checked_confirmation(value):
    import re
    if (not isinstance(value, dict) or set(value) != {'candidate_sha256'} or
            not isinstance(value['candidate_sha256'], str) or
            re.fullmatch(r'[a-f0-9]{64}', value['candidate_sha256']) is None):
        raise HarnessError('candidate_confirmation_required')
    return value


def parse_candidate_confirmation(text):
    """Parse one bounded final confirmation, optionally after plain short prose.

    Only the first object opener can begin the confirmation, and the complete
    remainder must decode as that one exact object. Prefixes cannot contain any
    structural JSON brackets or themselves be a JSON value. No hash search,
    suffix trimming, JSON repair or generic model-response recovery occurs.
    """
    if not isinstance(text, str):
        raise HarnessError('candidate_confirmation_required')
    try:
        raw = text.encode('utf-8', errors='strict')
    except UnicodeError:
        raise HarnessError('model_json_invalid') from None
    if len(raw) > 1024:
        raise HarnessError('candidate_confirmation_bound')
    try:
        value = decode_object(raw)
    except ValueError:
        start = text.find('{')
        prefix = text[:start] if start >= 0 else ''
        if (not prefix.strip() or text[start - 1] not in ' \t\r\n' or
                any(char in prefix for char in '{}[]')):
            raise HarnessError('model_json_invalid') from None
        # A scalar JSON value followed by an object is also multiple payloads.
        try:
            json.loads(prefix)
        except ValueError:
            pass
        else:
            raise HarnessError('model_json_invalid') from None
        try:
            value = decode_object(text[start:].encode('utf-8'))
        except ValueError:
            raise HarnessError('model_json_invalid') from None
    return _checked_confirmation(value)


def validate_candidate_terminal(raw, execution):
    """Narrow final-confirmation adapter after unchanged process/terminal checks."""
    text, events = _terminal_payload(raw, execution)
    value = parse_candidate_confirmation(text)
    start = text.find('{')
    prefix_bytes = len(text[:start].encode('utf-8')) if text[:start].strip() else 0
    events['candidate_confirmation_prefix_bytes'] = prefix_bytes
    return value, events


def sandbox_policy(stage, port, python):
    """macOS host boundary: selected files, dedicated state, loopback model route."""
    stage = Path(stage).resolve()
    state = stage / 'state'
    def quoted(value):
        return json.dumps(str(value), ensure_ascii=False)
    executables = [str(MAC_DSH), '/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness',
                   '/bin/sh', '/bin/bash', '/usr/bin/dirname', '/usr/bin/readlink', str(Path(python).resolve())]
    # macOS dyld and shell variant selection read system paths outside a portable
    # library allowlist. Permit OS reads while denying user/temporary/keychain
    # file contents except this task's selected inputs and private state.
    protected = ['/Users', '/Volumes', '/private/tmp', '/tmp', '/private/var/folders', '/Library/Keychains']
    deny_reads = '\n'.join('(deny file-read-data (require-all (subpath ' + quoted(path) +
                           ') (require-not (subpath ' + quoted(stage) + '))))' for path in protected)
    return ('(version 1)\n(deny default)\n(allow process-fork)\n(allow sysctl-read)\n'
            '(allow mach-lookup)\n(allow signal (target same-sandbox))\n'
            '(allow process-exec ' + ' '.join('(literal ' + quoted(p) + ')' for p in executables) + ')\n'
            '(allow file-read*)\n' + deny_reads + '\n' +
            '(allow file-write* (subpath ' + quoted(state) + ') (literal "/dev/null"))\n'
            '(allow network-outbound (remote tcp "localhost:' + str(int(port)) + '"))\n')


def prepare_stage(directory, request, corpus_path, route, *, mode, package=None, blind_review=None, review_id=None,
                  python=BRIDGE_PYTHON, node='node', reasoning_effort='off', delivery_kind=NATIVE_DELIVERY):
    from ielts_contract import validate_package, validate_request
    from ielts_corpus import load, style_source_bundle
    if mode not in ('generation', 'review', 'explanation_review'):
        raise HarnessError('stage_invalid')
    validate_stage_model(mode, route.model, reasoning_effort)
    if delivery_kind not in DELIVERY_KINDS:
        raise HarnessError('candidate_delivery_invalid')
    limits = stage_execution_limits(reasoning_effort)
    if (type(route.token_limit) is not int or route.token_limit != limits['token_limit'] or
            type(getattr(route, 'provider_deadline_seconds', None)) is not int or
            route.provider_deadline_seconds != limits['provider_deadline_seconds']):
        raise HarnessError('stage_output_bound')
    validate_request(request)
    corpus_path = Path(corpus_path).resolve()
    corpus_sha = sha256_file(corpus_path)
    corpus = load(corpus_path, expected_sha256=corpus_sha)
    styles = style_source_bundle(corpus, [source['source_id'] for source in request['style_sources']])
    if (styles['style_sources'] != request['style_sources'] or
            styles['style_blueprint_sha256'] != request['style_blueprint_sha256']):
        raise HarnessError('unverified_style_sources')
    if mode != 'generation':
        validate_package(request, package)
        if not review_id:
            raise HarnessError('review_id_required')
    if mode == 'explanation_review':
        from ielts_review import validate_review
        if not isinstance(blind_review, dict):
            raise HarnessError('blind_review_required')
        audit = validate_review(request, package, blind_review, blind_review.get('review_id'))
        if audit['status'] != 'eligible_for_caller_publication':
            raise HarnessError('blind_review_rejected')
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    inputs, state = directory / 'inputs', directory / 'state'
    inputs.mkdir(mode=0o700); state.mkdir(mode=0o700)
    (state / 'work').mkdir(); (state / 'tmp').mkdir()
    files = {}
    for key, original in [('contract', TOOLS_ROOT / 'ielts_contract.py'),
                          ('schemaModule', TOOLS_ROOT / 'ielts_json_schema.py'),
                          ('candidateModule', TOOLS_ROOT / 'ielts_candidate.py'),
                          ('visualModule', TOOLS_ROOT / 'ielts_visual.py'),
                          ('corpusModule', TOOLS_ROOT / 'ielts_corpus.py'), ('corpus', corpus_path),
                          *([('reviewModule', TOOLS_ROOT / 'ielts_review.py')] if mode != 'generation' else []),
                          *([('explanationReviewModule', TOOLS_ROOT / 'ielts_explanation_review.py')]
                            if mode == 'explanation_review' else [])]:
        target = inputs / original.name
        shutil.copyfile(original, target)
        files[key] = {'path': str(target), 'sha256': sha256_file(target)}
    for key, value in [('request', request), *([('package', package)] if mode != 'generation' else []),
                       *([('blindReview', blind_review)] if mode == 'explanation_review' else [])]:
        path = inputs / (key + '.json'); write_json_new(path, value)
        files[key] = {'path': str(path), 'sha256': sha256_file(path)}
    plugin = inputs / 'harness_ielts_plugin.mjs'
    shutil.copyfile(TOOLS_ROOT / plugin.name, plugin)
    manifest = {'schema': 'lexicon.ielts.harness.manifest.v1', 'mode': mode, 'candidateDelivery': delivery_kind,
                'python': str(Path(python).resolve()), 'runtimeRoot': str(RUNTIME_ROOT), 'files': files,
                'receiptPath': str(state / 'plugin-receipt.json'),
                'candidatePath': str(state / 'validated-candidate.json'),
                'limits': {'maxToolCalls': MAX_STAGE_TOOL_CALLS, 'maxInputBytes': 200000, 'maxOutputBytes': 65536, 'timeoutMs': 10000}}
    manifest['continuationPath'] = str(inputs / 'continuation.json')
    if review_id: manifest['reviewId'] = review_id
    manifest_path = inputs / 'manifest.json'; write_json_new(manifest_path, manifest)
    options = {'manifestPath': str(manifest_path), 'manifestSha256': sha256_file(manifest_path),
               'modelBaseURL': route.base_url, 'model': route.model, 'maxTokens': route.token_limit,
               'reasoningEffort': reasoning_effort, 'streamIdleTimeoutMs': limits['stream_idle_timeout_ms'],
               'candidateDelivery': delivery_kind}
    # Host code is fixed. Only a non-secret JSON object is supplied over stdin.
    builder = ("const m=await import(process.argv[1]);let s='';for await(const c of process.stdin)s+=c;"
               "process.stdout.write(m.buildHeadlessPatch(JSON.parse(s)));")
    result = subprocess.run([node, '--input-type=module', '-e', builder, plugin.as_uri()],
                            input=json.dumps(options), capture_output=True, text=True, timeout=15)
    if result.returncode != 0:
        raise HarnessError('profile_build_failed')
    patch = inputs / 'profile.json'; patch.write_text(result.stdout)
    sandbox = inputs / 'sandbox.sb'
    sandbox.write_text(sandbox_policy(directory, route.server.server_port, python))
    env = {'PATH': '/usr/bin:/bin:/opt/homebrew/bin', 'LANG': 'en_US.UTF-8',
           'TMPDIR': str(state / 'tmp') + '/', 'DSH_HOME': str(state / 'dsh-home'),
           'LEXICON_DUMMY_KEY': route.token}
    command = [str(MAC_DSH), '--profile', 'headless', '--patch', str(patch)]
    config = subprocess.run(command + ['--dump-config'], cwd=state / 'work', env=env, capture_output=True, timeout=20)
    if config.returncode:
        raise HarnessError('profile_resolution_failed')
    import re
    entries = re.split(r'(?m)^- id: ', config.stdout.decode('utf-8'))[1:]
    active = {entry.splitlines()[0].strip() for entry in entries if not re.search(r'(?m)^  disabled: true\s*$', entry)}
    expected = {'llm', 'session', 'agent', 'agent-default-model', 'session-persistence-jsonl',
                'session-projection', 'tools', 'system-prompt', 'agent-loop', 'llm-deepseek',
                'headless-startup', 'headless-runner', 'lexicon-ielts', 'lexicon-session-query'}
    if active != expected:
        write_json_new(state / 'profile-rejected.json', {'active': sorted(active), 'expected': sorted(expected)})
        raise HarnessError('profile_active_rows_mismatch')
    validate_effective_model_profile(config.stdout, model=route.model, reasoning_effort=reasoning_effort, limits=limits)
    (inputs / 'effective-profile.yml').write_bytes(config.stdout)
    prepared = {'directory': directory, 'state': state, 'command': ['/usr/bin/sandbox-exec', '-f', str(sandbox),
            *command, '--json', '-'], 'cwd': state / 'work', 'env': env,
            'manifest_sha256': sha256_file(manifest_path), 'profile_sha256': sha256_file(inputs / 'effective-profile.yml'),
            'plugin_sha256': sha256_file(plugin), 'corpus_sha256': corpus_sha,
            'schema_module_sha256': files['schemaModule']['sha256'],
            'candidate_module_sha256': files['candidateModule']['sha256'],
            'visual_module_sha256': files['visualModule']['sha256'],
            'model': route.model, 'reasoning_effort': reasoning_effort, 'delivery_kind': delivery_kind, **limits}
    prepared['continuation_path'] = inputs / 'continuation.json'
    if mode == 'explanation_review':
        prepared['explanation_review_module_sha256'] = files['explanationReviewModule']['sha256']
        prepared['blind_review_input_sha256'] = files['blindReview']['sha256']
    return prepared


def read_confirmed_candidate(prepared, confirmation, plugin_receipt):
    """Accept only the exact tool-written bytes confirmed by the final digest."""
    _checked_confirmation(confirmation)
    return read_candidate_artifact(prepared, confirmation['candidate_sha256'], plugin_receipt)


def read_candidate_artifact(prepared, digest, plugin_receipt):
    """Read exact validated bytes; callers establish either output authority."""
    from ielts_contract import canonical_sha256
    _checked_confirmation({'candidate_sha256': digest})
    if plugin_receipt.get('candidateSha256') != digest:
        raise HarnessError('candidate_receipt_mismatch')
    path = prepared['state'] / 'validated-candidate.json'
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    try:
        metadata = os.fstat(fd)
        if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= 200_000:
            raise HarnessError('candidate_file_bound')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            raw = stream.read(200_001)
    finally:
        os.close(fd)
    if (len(raw) != metadata.st_size or type(plugin_receipt.get('candidateBytes')) is not int or
            plugin_receipt['candidateBytes'] != len(raw) or hashlib.sha256(raw).hexdigest() != digest):
        raise HarnessError('candidate_file_mismatch')
    candidate = decode_object(raw)
    if canonical_sha256(candidate) != digest:
        raise HarnessError('candidate_not_canonical')
    return candidate


def _run_native_attempt(prepared, prompt, *, mode, timeout=240, cancelled=lambda: False):
    validate_stage_model(mode, prepared.get('model'), prepared.get('reasoning_effort'))
    limits = validate_stage_execution_limits(prepared, prepared['reasoning_effort'])
    delivery = candidate_delivery_kind(prepared)
    version = verify_native_version()
    raw, execution = run_owned_process(prepared['command'], cwd=prepared['cwd'], env=prepared['env'],
                                       prompt=prompt, timeout=timeout, cancelled=cancelled)
    receipt = {'schema': 'lexicon.ielts.runtime-receipt.v1', 'stage': mode, 'runtime_version': version,
               'native_attempt_count': 1,
               'required_tools': list(required_stage_tools(mode)),
               'status': 'failed', 'prompt_sha256': hashlib.sha256(prompt.encode('utf-8')).hexdigest(),
               'manifest_sha256': prepared['manifest_sha256'], 'profile_sha256': prepared['profile_sha256'],
               'plugin_sha256': prepared['plugin_sha256'], 'corpus_sha256': prepared['corpus_sha256'],
               'schema_module_sha256': prepared['schema_module_sha256'],
               'candidate_module_sha256': prepared['candidate_module_sha256'],
               'model': prepared['model'], 'reasoning_effort': prepared['reasoning_effort'],
               'network_boundary': 'macos_seatbelt_loopback_model_route', **limits, **execution}
    if 'visual_module_sha256' in prepared:
        receipt['visual_module_sha256'] = prepared['visual_module_sha256']
    if 'delivery_kind' in prepared:
        receipt['delivery_kind'] = delivery
    if 'quality_revision_sha256' in prepared:
        receipt['quality_revision_sha256'] = prepared['quality_revision_sha256']
    if 'continuation_sha256' in prepared:
        receipt['continuation_sha256'] = prepared['continuation_sha256']
        receipt['carried_tool_calls'] = prepared['carried_tool_calls']
    if mode == 'explanation_review':
        receipt['explanation_review_module_sha256'] = prepared.get('explanation_review_module_sha256')
        receipt['blind_review_input_sha256'] = prepared.get('blind_review_input_sha256')
    value = None
    try:
        observable = parse_headless_events(raw)
        observable.pop('final_text', None)
        receipt.update(observable)
        if delivery == NATIVE_DELIVERY:
            _, events = _terminal_payload(raw, execution, require_text=False)
        else:
            confirmation, events = validate_candidate_terminal(raw, execution)
        receipt.update(events)
        if delivery == LEGACY_DELIVERY and events['final_response_bytes'] > 1024:
            raise HarnessError('candidate_confirmation_bound')
        plugin_receipt = decode_object((prepared['state'] / 'plugin-receipt.json').read_bytes())
        validate_stage_tool_receipt(mode, plugin_receipt)
        if (plugin_receipt.get('carriedToolCalls', 0) != prepared.get('carried_tool_calls', 0) or
                plugin_receipt.get('continuationSha256') != prepared.get('continuation_sha256')):
            raise HarnessError('continuation_binding_mismatch')
        if (plugin_receipt.get('manifestSha256') != prepared['manifest_sha256'] or
                plugin_receipt.get('mode') != mode or plugin_receipt.get('pluginReady') is not True or
                plugin_receipt.get('globalGuard') is not True or plugin_receipt.get('registrationIdentityVerified') is not True or
                plugin_receipt.get('candidateSchemaStrict') is not True or
                plugin_receipt.get('schemaModuleSha256') != prepared['schema_module_sha256'] or
                plugin_receipt.get('candidateCompilerReady') is not True or
                plugin_receipt.get('candidateModuleSha256') != prepared['candidate_module_sha256'] or
                plugin_receipt.get('visualModuleSha256') != prepared.get('visual_module_sha256')):
            raise HarnessError('tool_policy_or_retrieval_incomplete')
        if mode == 'explanation_review':
            import re
            for field, plugin_field in [('explanation_review_module_sha256', 'explanationReviewModuleSha256'),
                                        ('blind_review_input_sha256', 'blindReviewInputSha256')]:
                digest = prepared.get(field)
                if (not isinstance(digest, str) or re.fullmatch(r'[a-f0-9]{64}', digest) is None or
                        plugin_receipt.get(plugin_field) != digest):
                    raise HarnessError('explanation_review_binding_mismatch')
        receipt['tool_receipt'] = plugin_receipt
        if plugin_receipt.get('candidateDelivery', LEGACY_DELIVERY) != delivery:
            raise HarnessError('candidate_delivery_mismatch')
        if delivery == NATIVE_DELIVERY:
            digest = plugin_receipt.get('candidateSha256')
            value = read_candidate_artifact(prepared, digest, plugin_receipt)
            receipt['native_tool_conclusion_verified'] = True
        else:
            value = read_confirmed_candidate(prepared, confirmation, plugin_receipt)
            digest = confirmation['candidate_sha256']
            receipt['candidate_confirmation_verified'] = True
        receipt['candidate_sha256'] = digest
        receipt['candidate_bytes'] = plugin_receipt['candidateBytes']
        receipt['response_sha256'] = hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                                  separators=(',', ':')).encode()).hexdigest()
        receipt['status'] = 'completed'
        validate_candidate_delivery_receipt(receipt)
    except (ValueError, OSError) as error:
        receipt['status'] = 'failed'
        receipt.pop('native_tool_conclusion_verified', None)
        receipt.pop('candidate_confirmation_verified', None)
        receipt['error'] = getattr(error, 'code', 'runtime_result_unavailable')
        value = None
        if receipt.get('runtime_error_type') == 'tool_input_invalid_json':
            try:
                startup = decode_object((prepared['state'] / 'plugin-receipt.json').read_bytes())
                if (_completed_readonly_tool_receipt(receipt, startup) and
                        startup.get('carriedToolCalls', 0) == prepared.get('carried_tool_calls', 0) and
                        startup.get('continuationSha256') == prepared.get('continuation_sha256')):
                    receipt['tool_receipt'] = startup
            except (ValueError, OSError):
                pass
    receipt['candidate_exists_after_attempt'] = os.path.lexists(prepared['state'] / 'validated-candidate.json')
    receipt['execution_context_sha256'] = _execution_context_sha256(prepared)
    write_json_new(prepared['state'] / 'runtime-receipt.json', receipt)
    return value, receipt


def _execution_context_sha256(prepared):
    command = list(prepared['command'])
    if '--session-id' in command:
        index = command.index('--session-id')
        del command[index:index + 2]
    value = {'command': command, 'cwd': str(prepared['cwd']), 'dsh_home': prepared['env'].get('DSH_HOME')}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def _completed_readonly_tool_receipt(receipt, plugin):
    """Only complete, identity-bound local reads with no validation attempt."""
    mode = receipt.get('stage')
    if not isinstance(plugin, dict) or mode not in ('generation', 'review', 'explanation_review'):
        return False
    if (plugin.get('mode') != mode or plugin.get('manifestSha256') != receipt.get('manifest_sha256') or
            plugin.get('requiredTools') != list(required_stage_tools(mode)) or
            plugin.get('toolNames') != list(HARNESS_TOOL_NAMES) or
            any(plugin.get(key) is not True for key in ('pluginReady', 'globalGuard', 'registrationIdentityVerified',
                                                        'candidateSchemaStrict', 'candidateCompilerReady')) or
            plugin.get('schemaModuleSha256') != receipt.get('schema_module_sha256') or
            plugin.get('candidateModuleSha256') != receipt.get('candidate_module_sha256') or
            plugin.get('visualModuleSha256') != receipt.get('visual_module_sha256') or
            plugin.get('candidateSha256') is not None or plugin.get('nativeToolConclusion') is not None or
            plugin.get('candidateDelivery', LEGACY_DELIVERY) != receipt.get('delivery_kind', LEGACY_DELIVERY) or
            receipt.get('delivery_kind', LEGACY_DELIVERY) not in DELIVERY_KINDS):
        return False
    if mode == 'explanation_review' and (
            plugin.get('explanationReviewModuleSha256') != receipt.get('explanation_review_module_sha256') or
            plugin.get('blindReviewInputSha256') != receipt.get('blind_review_input_sha256')):
        return False
    for key in ('deniedCount', 'failedCount', 'identityFailures', 'candidateBytes'):
        if type(plugin.get(key)) is not int or plugin[key] != 0:
            return False
    for key in ('perTool', 'completedPerTool'):
        if (not isinstance(plugin.get(key), dict) or set(plugin[key]) != set(HARNESS_TOOL_NAMES) or
                any(type(value) is not int or value < 0 for value in plugin[key].values())):
            return False
    used = sum(plugin['perTool'].values())
    if (plugin['perTool'] != plugin['completedPerTool'] or plugin['perTool']['lexicon_ielts_validate'] != 0 or
            any(type(plugin.get(k)) is not int or plugin[k] != used for k in ('allowedCount', 'completedCount')) or
            type(plugin.get('registrationChecks')) is not int or plugin['registrationChecks'] != used * 2 or
            type(receipt.get('tool_calls')) is not int or receipt['tool_calls'] != used or
            not isinstance(plugin.get('limits'), dict) or
            type(plugin['limits'].get('maxToolCalls')) is not int or
            plugin['limits']['maxToolCalls'] != MAX_STAGE_TOOL_CALLS):
        return False
    if mode == 'generation' and any(plugin['completedPerTool'][name] < 1 for name in ('lexicon_ielts_retrieve', 'lexicon_ielts_read')):
        return False
    carried = plugin.get('carriedToolCalls', 0)
    if (type(carried) is not int or not 0 <= carried <= MAX_STAGE_TOOL_CALLS or
            carried + used > MAX_STAGE_TOOL_CALLS):
        return False
    return True


def _malformed_attempt_can_resume(receipt):
    zero_fields = ('errors', 'malformed_frames')
    one_fields = ('exit_code', 'turn_count', 'terminal_count', 'native_attempt_count')
    return (isinstance(receipt, dict) and
            all(type(receipt.get(k)) is int and receipt[k] == 0 for k in zero_fields) and
            all(type(receipt.get(k)) is int and receipt[k] == 1 for k in one_fields) and
            receipt.get('stage') in ('generation', 'review', 'explanation_review') and receipt.get('status') == 'failed' and
            receipt.get('runtime_error_code') == 'MALFORMED_RESPONSE' and
            receipt.get('runtime_error_type') == 'tool_input_invalid_json' and
            receipt.get('terminal') == 'error' and receipt.get('exit_code') == 1 and
            receipt.get('process_reaped') is True and receipt.get('stop_reason') is None and
            receipt.get('input_write_failed') is False and receipt.get('candidate_exists_after_attempt') is False and
            receipt.get('errors') == 0 and receipt.get('malformed_frames') == 0 and
            receipt.get('turn_count') == 1 and receipt.get('terminal_count') == 1 and
            'recovery' not in receipt and 'continuation_sha256' not in receipt and
            'carried_tool_calls' not in receipt and
            isinstance(receipt.get('session_id'), str) and receipt['session_id'].startswith('session-') and
            _completed_readonly_tool_receipt(receipt, receipt.get('tool_receipt')) and
            receipt['tool_receipt'].get('carriedToolCalls', 0) == 0 and
            receipt['tool_receipt'].get('continuationSha256') is None and
            receipt['tool_receipt']['allowedCount'] < MAX_STAGE_TOOL_CALLS)


def continuation_seed(first, runtime_sha, plugin_sha):
    """Host-only seed; its path is fixed in the original read-only manifest."""
    if not _malformed_attempt_can_resume(first):
        raise HarnessError('continuation_first_attempt_invalid')
    return {'schema': 'lexicon.ielts.continuation.v1', 'manifestSha256': first['manifest_sha256'],
            'sessionId': first['session_id'], 'runtimeReceiptSha256': runtime_sha,
            'pluginReceiptSha256': plugin_sha, 'carriedToolCalls': first['tool_receipt']['allowedCount'],
            'maxToolCalls': MAX_STAGE_TOOL_CALLS}


def _completed_requests_for_repair(records, ordinals):
    # Gate against the complete parent-owned ledger, including preceding stages.
    if (not isinstance(records, list) or not records or
            any(not isinstance(row, dict) or row.get('phase') != 'completed' or row.get('http_status') != 200 for row in records)):
        return False
    selected = [row for row in records if row.get('ordinal') in ordinals]
    if not selected or [row.get('ordinal') for row in selected] != ordinals:
        return False
    termination = selected[-1].get('termination', {})
    return (isinstance(termination, dict) and termination.get('metadata_status') == 'observed' and termination.get('stop_reason') == 'tool_use' and
            termination.get('message_stop_observed') is True)


def validate_native_recovery_receipt(receipt, model_records=None):
    """Validate a bounded same-session correction, including retained failed input."""
    if 'recovery' not in receipt:
        plugin = receipt.get('tool_receipt') or {}
        if (type(receipt.get('native_attempt_count')) is not int or receipt['native_attempt_count'] != 1 or
                'continuation_sha256' in receipt or 'carried_tool_calls' in receipt or
                plugin.get('continuationSha256') is not None or plugin.get('carriedToolCalls', 0) != 0):
            raise HarnessError('runtime_recovery_binding_mismatch')
        return
    import re
    recovery = receipt['recovery']
    error = 'runtime_recovery_binding_mismatch'
    fields = {'kind', 'original_prompt_sha256', 'repair_prompt_sha256', 'stage_timeout_ms',
              'remaining_timeout_ms', 'stage_elapsed_ms', 'attempts', 'continuation_sha256'}
    if (type(receipt.get('native_attempt_count')) is not int or receipt['native_attempt_count'] != 2 or
            not isinstance(recovery, dict) or set(recovery) != fields or recovery['kind'] != 'malformed_tool_json'):
        raise HarnessError(error)
    attempts = recovery['attempts']
    if not isinstance(attempts, list) or len(attempts) != 2:
        raise HarnessError(error)
    values, ordinals = [], []
    for attempt in attempts:
        if (not isinstance(attempt, dict) or set(attempt) != {'receipt', 'runtime_receipt_sha256',
                                                            'plugin_receipt_sha256', 'model_request_ordinals'} or
                not isinstance(attempt['receipt'], dict) or 'recovery' in attempt['receipt']):
            raise HarnessError(error)
        value, own = attempt['receipt'], attempt['model_request_ordinals']
        if (not isinstance(own, list) or not own or any(type(n) is not int or not 1 <= n <= 16 for n in own) or
                own != list(range(own[0], own[-1] + 1))):
            raise HarnessError(error)
        for field in ('runtime_receipt_sha256', 'plugin_receipt_sha256'):
            if not isinstance(attempt[field], str) or re.fullmatch(r'[a-f0-9]{64}', attempt[field]) is None:
                raise HarnessError(error)
        raw = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False).encode()
        if hashlib.sha256(raw).hexdigest() != attempt['runtime_receipt_sha256']:
            raise HarnessError(error)
        values.append(value); ordinals.extend(own)
    first, second = values
    seed = continuation_seed(first, attempts[0]['runtime_receipt_sha256'], attempts[0]['plugin_receipt_sha256'])
    seed_sha = hashlib.sha256(json.dumps(seed, ensure_ascii=False, sort_keys=True, indent=2).encode()).hexdigest()
    carried = first['tool_receipt']['allowedCount']
    second_plugin = second.get('tool_receipt')
    if (recovery['continuation_sha256'] != seed_sha or second.get('continuation_sha256') != seed_sha or
            type(second.get('carried_tool_calls')) is not int or second['carried_tool_calls'] != carried or
            not isinstance(second_plugin, dict) or second_plugin.get('continuationSha256') != seed_sha or
            type(second_plugin.get('carriedToolCalls')) is not int or second_plugin['carriedToolCalls'] != carried or
            not isinstance(second_plugin.get('limits'), dict) or
            type(second_plugin['limits'].get('maxToolCalls')) is not int or
            second_plugin['limits']['maxToolCalls'] != MAX_STAGE_TOOL_CALLS or
            type(second_plugin.get('allowedCount')) is not int or
            not 0 <= second_plugin['allowedCount'] <= MAX_STAGE_TOOL_CALLS - carried):
        raise HarnessError(error)
    if first['stage'] == 'generation' and second_plugin.get('carriedCompletedPerTool') != first['tool_receipt']['completedPerTool']:
        raise HarnessError(error)
    delivery = candidate_delivery_kind(first)
    if candidate_delivery_kind(second) != delivery or candidate_delivery_kind(receipt) != delivery:
        raise HarnessError(error)
    repair_sha = hashlib.sha256(tool_json_repair_prompt(delivery).encode()).hexdigest()
    if (not _malformed_attempt_can_resume(first) or
            type(second.get('native_attempt_count')) is not int or second['native_attempt_count'] != 1 or
            recovery['original_prompt_sha256'] != receipt.get('prompt_sha256') or
            first.get('prompt_sha256') != receipt.get('prompt_sha256') or
            recovery['repair_prompt_sha256'] != repair_sha or second.get('prompt_sha256') != repair_sha or
            ordinals != list(range(ordinals[0], ordinals[-1] + 1)) or
            ('model_request_ordinals' in receipt and receipt['model_request_ordinals'] != ordinals)):
        raise HarnessError(error)
    for key in ('session_id', 'stage', 'runtime_version', 'manifest_sha256', 'profile_sha256', 'plugin_sha256',
                'corpus_sha256', 'schema_module_sha256', 'candidate_module_sha256', 'model', 'reasoning_effort',
                'token_limit', 'provider_deadline_seconds', 'stream_idle_timeout_ms', 'execution_context_sha256'):
        if not first.get(key) or first.get(key) != second.get(key) or receipt.get(key) != second.get(key):
            raise HarnessError(error)
    visual_values = [value.get('visual_module_sha256') for value in (first, second, receipt)]
    if any(value is not None for value in visual_values) and (len(set(visual_values)) != 1 or
            re.fullmatch(r'[a-f0-9]{64}', str(visual_values[0])) is None):
        raise HarnessError(error)
    if re.fullmatch(r'[a-f0-9]{64}', str(receipt.get('execution_context_sha256'))) is None:
        raise HarnessError(error)
    for key in ('explanation_review_module_sha256', 'blind_review_input_sha256'):
        if receipt.get('stage') == 'explanation_review' and (not first.get(key) or first.get(key) != second.get(key)):
            raise HarnessError(error)
    expected = dict(second, prompt_sha256=first['prompt_sha256'], native_attempt_count=2, recovery=recovery)
    expected.pop('model_request_ordinals', None)
    actual = dict(receipt); actual.pop('model_request_ordinals', None)
    if actual != expected:
        raise HarnessError(error)
    total, remaining = recovery['stage_timeout_ms'], recovery['remaining_timeout_ms']
    elapsed = first.get('elapsed_ms')
    stage_elapsed = recovery['stage_elapsed_ms']
    if (type(total) is not int or not 1000 <= total <= 600000 or type(remaining) is not int or
            type(elapsed) is not int or elapsed < 0 or not 1000 <= remaining <= total - elapsed or
            type(stage_elapsed) is not int or type(second.get('elapsed_ms')) is not int or
            stage_elapsed < elapsed + second['elapsed_ms']):
        raise HarnessError(error)
    if model_records is not None and not _completed_requests_for_repair(model_records, attempts[0]['model_request_ordinals']):
        raise HarnessError(error)


def run_native_stage(prepared, prompt, *, mode, timeout=240, cancelled=lambda: False,
                     model_budget=None, first_request_ordinal=None):
    """Run once, with at most one native same-session malformed-tool correction."""
    started = time.monotonic()
    first_result, first = _run_native_attempt(prepared, prompt, mode=mode, timeout=timeout, cancelled=cancelled)
    if (model_budget is None or type(first_request_ordinal) is not int or first_request_ordinal < 1 or
            not _malformed_attempt_can_resume(first) or cancelled()):
        return first_result, first
    first_ordinals = list(range(first_request_ordinal, model_budget.calls + 1))
    records = model_budget.snapshot()
    remaining_ms = int(timeout * 1000 - (time.monotonic() - started) * 1000)
    delivery = candidate_delivery_kind(prepared)
    minimum_requests = 1 if delivery == NATIVE_DELIVERY else 2
    if (model_budget.halted or model_budget.max_calls - model_budget.calls < minimum_requests or remaining_ms < 1000 or
            not _completed_requests_for_repair(records, first_ordinals)):
        return first_result, first
    state = prepared['state']
    continuation = prepared.get('continuation_path')
    if (not isinstance(continuation, Path) or os.path.lexists(continuation) or
            os.path.lexists(state / 'validated-candidate.json')):
        return first_result, first
    command = list(prepared['command'])
    if command[-2:] != ['--json', '-'] or '--session-id' in command:
        return first_result, first
    first_dir = state / 'attempt-01'; first_dir.mkdir(mode=0o700)
    for name in ('runtime-receipt.json', 'plugin-receipt.json'):
        (state / name).rename(first_dir / name)
    resumed = dict(prepared)
    resumed['command'] = command[:-2] + ['--session-id', first['session_id'], '--json', '-']
    second_start = model_budget.calls + 1
    # File preservation and command preparation also consume the stage deadline.
    remaining_ms = min(remaining_ms, int(timeout * 1000 - (time.monotonic() - started) * 1000))
    cancelled_now = cancelled()
    if cancelled_now or remaining_ms < 1000:
        stopped = dict(first, error='cancelled_before_recovery' if cancelled_now else 'timeout_before_recovery')
        write_json_new(state / 'runtime-receipt.json', stopped)
        shutil.copyfile(first_dir / 'plugin-receipt.json', state / 'plugin-receipt.json')
        return None, stopped
    seed = continuation_seed(first, sha256_file(first_dir / 'runtime-receipt.json'),
                             sha256_file(first_dir / 'plugin-receipt.json'))
    try:
        write_json_new(continuation, seed)
    except OSError:
        stopped = dict(first, error='continuation_write_failed')
        write_json_new(state / 'runtime-receipt.json', stopped)
        shutil.copyfile(first_dir / 'plugin-receipt.json', state / 'plugin-receipt.json')
        return None, stopped
    resumed['continuation_sha256'] = sha256_file(continuation)
    resumed['carried_tool_calls'] = seed['carriedToolCalls']
    remaining_ms = min(remaining_ms, int(timeout * 1000 - (time.monotonic() - started) * 1000))
    if cancelled() or remaining_ms < 1000:
        stopped = dict(first, error='cancelled_or_timeout_before_recovery')
        write_json_new(state / 'runtime-receipt.json', stopped)
        shutil.copyfile(first_dir / 'plugin-receipt.json', state / 'plugin-receipt.json')
        return None, stopped
    repair_prompt = tool_json_repair_prompt(delivery)
    second_result, second = _run_native_attempt(resumed, repair_prompt, mode=mode,
                                                timeout=remaining_ms / 1000, cancelled=cancelled)
    second_dir = state / 'attempt-02'; second_dir.mkdir(mode=0o700)
    (state / 'runtime-receipt.json').rename(second_dir / 'runtime-receipt.json')
    if not (state / 'plugin-receipt.json').is_file():
        stopped = dict(second, status='failed', error='recovery_plugin_receipt_missing',
                       prompt_sha256=first['prompt_sha256'], native_attempt_count=2,
                       recovery_incomplete=True)
        write_json_new(state / 'runtime-receipt.json', stopped)
        return None, stopped
    shutil.copyfile(state / 'plugin-receipt.json', second_dir / 'plugin-receipt.json')
    attempts = []
    for directory, value, owned in ((first_dir, first, first_ordinals),
                                     (second_dir, second, list(range(second_start, model_budget.calls + 1)))):
        attempts.append({'receipt': value, 'runtime_receipt_sha256': sha256_file(directory / 'runtime-receipt.json'),
                         'plugin_receipt_sha256': sha256_file(directory / 'plugin-receipt.json'),
                         'model_request_ordinals': owned})
    result = dict(second, prompt_sha256=first['prompt_sha256'], native_attempt_count=2, recovery={'kind': 'malformed_tool_json',
        'original_prompt_sha256': first['prompt_sha256'], 'repair_prompt_sha256': hashlib.sha256(repair_prompt.encode()).hexdigest(),
        'stage_timeout_ms': int(timeout * 1000), 'remaining_timeout_ms': remaining_ms,
        'continuation_sha256': resumed['continuation_sha256'],
        'stage_elapsed_ms': round((time.monotonic() - started) * 1000), 'attempts': attempts})
    write_json_new(state / 'runtime-receipt.json', result)
    return second_result, result
