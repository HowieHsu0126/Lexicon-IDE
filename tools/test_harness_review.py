"""Local protocol tests; no model or API request is made."""
import importlib.util
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('harness_review', Path(__file__).with_name('harness_review.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HarnessEventsTest(unittest.TestCase):
    def test_success_requires_an_independent_terminal_and_final_text(self):
        result = module.parse_events('\n'.join(json.dumps(e) for e in [
            {'type': 'session', 'sessionId': 'session-local'},
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'completed'}},
            {'type': 'final', 'text': 'Candidate finding'},
        ]))
        self.assertEqual('completed', result['terminal_kind'])
        self.assertEqual('Candidate finding', result['final_text'])
        self.assertEqual(0, result['tool_calls'])

    def test_partial_timeout_retains_session_and_phase_without_pretending_success(self):
        result = module.parse_events('{"type":"session","sessionId":"session-local"}\n'
                                     '{"type":"status","phase":"step_start"}\n{"type":')
        self.assertEqual('session-local', result['session_id'])
        self.assertEqual('step_start', result['last_phase'])
        self.assertIsNone(result['terminal_kind'])
        self.assertEqual('', result['final_text'])

    def test_reasoning_arguments_errors_and_untrusted_usage_are_discarded(self):
        raw = '\n'.join(json.dumps(e) for e in [
            {'type': 'thinking', 'text': 'PRIVATE'},
            {'type': 'tool_call', 'tool': 'shell', 'input': 'PRIVATE'},
            {'type': 'error', 'message': 'PRIVATE'},
            {'type': 'status', 'phase': 'step_end', 'usage': {'inputTokens': 42, 'outputTokens': -9, 'secret': 'PRIVATE', 'totalTokens': True}},
        ])
        result = module.parse_events(raw)
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertEqual([{'inputTokens': 42}], result['usage'])
        self.assertEqual(1, result['tool_calls'])

    def test_new_turn_cannot_reuse_an_older_successful_terminal_or_final(self):
        result = module.parse_events('\n'.join(json.dumps(e) for e in [
            {'type': 'session', 'sessionId': 'session-offline'},
            {'type': 'status', 'phase': 'turn_start'},
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'completed'}},
            {'type': 'final', 'text': 'An earlier answer'},
            {'type': 'status', 'phase': 'turn_start'},
            {'type': 'status', 'phase': 'step_start'},
        ]))
        self.assertEqual('step_start', result['last_phase'])
        self.assertIsNone(result['terminal_kind'])
        self.assertEqual('', result['final_text'])

    def test_unknown_later_terminal_does_not_inherit_completed(self):
        result = module.parse_events('\n'.join(json.dumps(e) for e in [
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'completed'}},
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'unknown'}},
        ]))
        self.assertIsNone(result['terminal_kind'])


class HarnessInvocationTest(unittest.TestCase):
    """Exercise the wrapper against a local executable that only emits fixture JSON events."""
    session = 'session-00000000-0000-4000-8000-000000000001'
    other_session = 'session-00000000-0000-4000-8000-000000000002'

    def setUp(self):
        self.temp = TemporaryDirectory(prefix='lexicon harness offline ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.task = self.root / 'question.txt'
        self.task.write_text('Review only this synthetic arithmetic helper.', encoding='utf-8')
        self.source = self.root / 'helper.py'
        self.source.write_text('def add(a, b):\n    return a + b\n', encoding='utf-8')
        self.key = self.root / 'synthetic-key.txt'
        self.key.write_text('offline-fixture-key-never-a-real-credential', encoding='utf-8')
        self.cli = self.root / 'fake-dsh'
        self.calls = self.root / 'invocations.jsonl'

    def events(self, session=None):
        return [
            {'type': 'session', 'sessionId': session or self.session},
            {'type': 'status', 'phase': 'turn_start'},
            {'type': 'status', 'phase': 'step_start'},
            {'type': 'status', 'phase': 'step_end', 'usage': {'inputTokens': 20, 'outputTokens': 4}},
            {'type': 'status', 'phase': 'turn_end', 'reason': {'kind': 'completed'}},
            {'type': 'final', 'text': 'Candidate finding from a local fixture.'},
        ]

    def fake_cli(self, events, exit_code=0, hang=False):
        # No shell, sockets, external commands, config files or real credentials are used.
        code = f'''#!{sys.executable}
import json, os, sys, time
from pathlib import Path
task = sys.stdin.read()
with Path({str(self.calls)!r}).open('a', encoding='utf-8') as log:
    log.write(json.dumps({{'cwd': os.getcwd(), 'argv': sys.argv[1:], 'task': task,
                          'key_in_environment': 'DEEPSEEK_API_KEY' in os.environ}}) + '\\n')
for event in {events!r}:
    print(json.dumps(event), flush=True)
if {hang!r}:
    time.sleep(5)
sys.exit({exit_code})
'''
        self.cli.write_text(code, encoding='utf-8')
        self.cli.chmod(0o700)

    def invoke(self, name, extra=(), events=None, exit_code=0, hang=False):
        self.fake_cli(self.events() if events is None else events, exit_code, hang)
        out = self.root / name
        argv = ['harness_review.py', '--task', str(self.task), '--source', str(self.source),
                '--key-file', str(self.key), '--out', str(out), '--timeout', '1', *extra]
        with patch.object(module, 'ROOT', self.root), patch.object(module.shutil, 'which', return_value=str(self.cli)), \
                patch.object(sys, 'argv', argv), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            code = module.main()
        receipt = json.loads((out / 'receipt.json').read_text(encoding='utf-8'))
        return code, out, receipt

    def call_records(self):
        return [json.loads(line) for line in self.calls.read_text(encoding='utf-8').splitlines()]

    def test_first_run_keeps_exact_working_directory_for_receipt_based_resume(self):
        first_code, first_out, first = self.invoke('first')
        self.assertEqual(0, first_code)
        self.assertEqual('review_returned', first['status'])
        working = Path(first['working_directory'])
        self.assertTrue(working.is_absolute())
        self.assertTrue(working.is_dir())
        self.assertTrue(working.is_relative_to((self.root / '.cache' / 'harness-workspaces').resolve()))
        self.assertFalse(first['resumed_session'])
        code, _, resumed = self.invoke('resumed', ('--resume-from', str(first_out / 'receipt.json'), '--session-id', self.session))
        self.assertEqual(0, code)
        self.assertTrue(resumed['resumed_session'])
        self.assertEqual(first['working_directory'], resumed['working_directory'])
        calls = self.call_records()
        self.assertEqual([str(working), str(working)], [call['cwd'] for call in calls])
        self.assertEqual(['headless', '--json', '-'], calls[0]['argv'])
        self.assertEqual(['headless', '--json', '--session-id', self.session, '-'], calls[1]['argv'])
        for call in calls:
            self.assertTrue(call['key_in_environment'])
            self.assertNotIn(self.key.read_text(), call['task'])
            self.assertNotIn(self.key.read_text(), ' '.join(call['argv']))

    def test_asserted_session_mismatch_is_rejected_before_launch_or_output_creation(self):
        _, first_out, _ = self.invoke('first')
        with self.assertRaises(SystemExit) as error:
            self.invoke('invalid-resume', ('--resume-from', str(first_out / 'receipt.json'), '--session-id', self.other_session))
        self.assertEqual(2, error.exception.code)
        self.assertEqual(1, len(self.call_records()))
        self.assertFalse((self.root / 'invalid-resume').exists())

    def test_bare_session_id_is_not_a_resumable_invocation(self):
        with self.assertRaises(SystemExit) as error:
            self.invoke('bare-id', ('--session-id', self.session))
        self.assertEqual(2, error.exception.code)
        self.assertFalse(self.calls.exists())
        self.assertFalse((self.root / 'bare-id').exists())

    def test_cli_returning_a_different_session_does_not_publish_an_answer(self):
        _, first_out, _ = self.invoke('first')
        code, out, receipt = self.invoke('wrong-returned-session', ('--resume-from', str(first_out / 'receipt.json')),
                                         events=self.events(self.other_session))
        self.assertEqual(1, code)
        self.assertNotEqual('review_returned', receipt['status'])
        self.assertFalse((out / 'answer.txt').exists())

    def test_final_text_without_completed_terminal_is_not_success(self):
        events = [event for event in self.events() if event.get('phase') != 'turn_end']
        code, out, receipt = self.invoke('final-only', events=events)
        self.assertEqual(1, code)
        self.assertEqual('failed', receipt['status'])
        self.assertIsNone(receipt['terminal_kind'])
        self.assertFalse((out / 'answer.txt').exists())

    def test_completed_terminal_without_final_text_and_nonzero_exit_are_not_success(self):
        for name, events, exit_code in (
                ('terminal-only', [event for event in self.events() if event['type'] != 'final'], 0),
                ('nonzero-exit', self.events(), 1)):
            with self.subTest(name=name):
                code, out, receipt = self.invoke(name, events=events, exit_code=exit_code)
                self.assertEqual(1, code)
                self.assertEqual('failed', receipt['status'])
                self.assertFalse((out / 'answer.txt').exists())

    def test_unexpected_tools_disqualify_even_a_completed_answer(self):
        events = self.events()
        events.insert(3, {'type': 'tool_call', 'name': 'shell', 'input': 'discard this synthetic argument'})
        code, out, receipt = self.invoke('unexpected-tool', events=events)
        self.assertEqual(1, code)
        self.assertEqual('unexpected_tool_use', receipt['status'])
        self.assertEqual(1, receipt['tool_calls'])
        self.assertNotIn('discard this synthetic argument', json.dumps(receipt))
        self.assertFalse((out / 'answer.txt').exists())

    def test_timeout_keeps_partial_receipt_without_saving_an_answer(self):
        events = [{'type': 'session', 'sessionId': self.session}, {'type': 'status', 'phase': 'step_start'}]
        code, out, receipt = self.invoke('timeout', events=events, hang=True)
        self.assertEqual(1, code)
        self.assertEqual('timeout', receipt['status'])
        self.assertEqual('step_start', receipt['last_phase'])
        self.assertIsNone(receipt['terminal_kind'])
        self.assertEqual(self.session, receipt['session_id'])
        self.assertEqual([], receipt['usage'])
        self.assertEqual(0, receipt['tool_calls'])
        self.assertTrue(Path(receipt['working_directory']).is_dir())
        self.assertFalse((out / 'answer.txt').exists())


if __name__ == '__main__':
    unittest.main()
