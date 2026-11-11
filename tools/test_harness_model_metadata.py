"""Metadata-only diagnostics from synthetic Anthropic responses; zero provider calls."""
import json
import unittest
import urllib.error
import urllib.request

from harness_model_proxy import (MAX_RESPONSE, MAX_TOKEN_COUNT, STOP_REASONS, ModelBudget,
                                ModelRoute, ProviderCompletionUnknown, provider_termination_metadata)


FIELDS = {'format', 'metadata_status', 'stop_reason', 'input_tokens', 'output_tokens', 'message_stop_observed'}
PRIVATE = 'PRIVATE_CONTENT_THINKING_TOOL_ARGUMENT_OR_CREDENTIAL'


def encoded(value):
    return json.dumps(value, ensure_ascii=False).encode()


def event(kind, **values):
    return b'event: ' + kind.encode() + b'\ndata: ' + encoded(dict(type=kind, **values)) + b'\n\n'


def stream(reason='end_turn', *, stopped=True, incoming=20, outgoing=80):
    return (event('message_start', message={'type': 'message', 'usage': {'input_tokens': incoming, 'output_tokens': 0}}) +
            event('content_block_start', index=0, content_block={'type': 'thinking', 'thinking': PRIVATE}) +
            event('content_block_delta', index=0, delta={'type': 'thinking_delta', 'thinking': PRIVATE}) +
            event('content_block_delta', index=1, delta={'type': 'text_delta', 'text': PRIVATE}) +
            event('content_block_delta', index=2, delta={'type': 'input_json_delta', 'partial_json': PRIVATE}) +
            event('message_delta', delta={'stop_reason': reason, 'stop_sequence': PRIVATE}, usage={'output_tokens': outgoing}) +
            (event('message_stop') if stopped else b''))


class TerminationMetadataTest(unittest.TestCase):
    def assert_safe(self, value):
        self.assertEqual(FIELDS, set(value))
        self.assertNotIn(PRIVATE, json.dumps(value))
        self.assertIn(value['metadata_status'], ('observed', 'unknown'))
        self.assertIn(value['stop_reason'], STOP_REASONS | {'unknown'})
        self.assertLess(len(json.dumps(value)), 350)

    def test_json_keeps_only_enumerated_reason_and_nonnegative_token_counts(self):
        for reason in STOP_REASONS:
            with self.subTest(reason=reason):
                raw = encoded({'type': 'message', 'stop_reason': reason, 'stop_sequence': PRIVATE,
                    'usage': {'input_tokens': 0, 'output_tokens': 3, 'private': PRIVATE},
                    'content': [{'type': 'thinking', 'thinking': PRIVATE}, {'type': 'tool_use', 'input': {'x': PRIVATE}}]})
                value = provider_termination_metadata(raw, 'application/json; charset=utf-8')
                self.assert_safe(value)
                self.assertEqual({'format': 'anthropic_json', 'metadata_status': 'observed', 'stop_reason': reason,
                    'input_tokens': 0, 'output_tokens': 3, 'message_stop_observed': None}, value)

    def test_sse_observes_final_usage_and_terminal_without_collecting_content(self):
        value = provider_termination_metadata(stream('max_tokens'), 'text/event-stream')
        self.assert_safe(value)
        self.assertEqual({'format': 'anthropic_sse', 'metadata_status': 'observed', 'stop_reason': 'max_tokens',
            'input_tokens': 20, 'output_tokens': 80, 'message_stop_observed': True}, value)

    def test_missing_sse_terminal_is_explicitly_unknown_even_with_reason_and_usage(self):
        value = provider_termination_metadata(stream(stopped=False), 'text/event-stream')
        self.assert_safe(value)
        self.assertEqual('unknown', value['metadata_status'])
        self.assertEqual('end_turn', value['stop_reason'])
        self.assertEqual(80, value['output_tokens'])
        self.assertIs(value['message_stop_observed'], False)

    def test_missing_json_fields_and_unrecognized_reason_do_not_imply_completion(self):
        for message in ({'type': 'message'}, {'type': 'error', 'error': PRIVATE},
                        {'type': 'message', 'stop_reason': PRIVATE, 'usage': {'input_tokens': 1, 'output_tokens': 2}},
                        {'type': 'message', 'stop_reason': 'end_turn', 'usage': {'input_tokens': 1}}):
            with self.subTest(fields=sorted(message)):
                value = provider_termination_metadata(encoded(message), 'application/json')
                self.assert_safe(value); self.assertEqual('unknown', value['metadata_status'])

    def test_token_counts_never_accept_bool_float_string_negative_or_oversized_values(self):
        for invalid in (None, True, False, -1, 1.5, '123', MAX_TOKEN_COUNT + 1, [], {'secret': PRIVATE}):
            for field in ('input_tokens', 'output_tokens'):
                usage = {'input_tokens': 1, 'output_tokens': 2, field: invalid}
                value = provider_termination_metadata(encoded({'type': 'message', 'stop_reason': 'end_turn', 'usage': usage}), 'application/json')
                self.assert_safe(value); self.assertEqual('unknown', value['metadata_status'])
                self.assertIsNone(value[field])

    def test_bad_encoding_duplicate_fields_and_non_anthropic_responses_are_unknown(self):
        for raw, mime in ((b'\xff', 'application/json'), (b'{', 'application/json'),
                          (b'{"type":"message","type":"message"}', 'application/json'),
                          (b'[]', 'application/json'), (b'data: [DONE]\n\n', 'text/event-stream'),
                          (stream(), 'text/plain; private=' + PRIVATE), (b'x' * (MAX_RESPONSE + 1), 'text/event-stream')):
            value = provider_termination_metadata(raw, mime)
            self.assert_safe(value); self.assertEqual('unknown', value['metadata_status'])
            self.assertEqual('unknown', value['stop_reason'])
            self.assertIsNone(value['message_stop_observed'])

    def test_unterminated_or_mismatched_sse_frames_cannot_claim_a_terminal(self):
        for raw in (stream()[:-1], stream() + event('message_stop'),
                    stream().replace(b'event: message_stop', b'event: content_block_delta'),
                    stream() + b'data: {broken}\n\n', stream() + event('error', error={'message': PRIVATE})):
            value = provider_termination_metadata(raw, 'text/event-stream')
            self.assert_safe(value); self.assertEqual('unknown', value['metadata_status'])
            self.assertIsNone(value['message_stop_observed'])

    def test_final_output_count_is_cumulative_and_not_added_to_initial_zero(self):
        start = event('message_start', message={'type': 'message', 'usage': {'input_tokens': 5, 'output_tokens': 0}})
        first = event('message_delta', delta={'stop_reason': None}, usage={'output_tokens': 12})
        last = event('message_delta', delta={'stop_reason': 'tool_use'}, usage={'output_tokens': 20})
        value = provider_termination_metadata(start + first + last + event('message_stop'), 'text/event-stream')
        self.assertEqual('observed', value['metadata_status']); self.assertEqual(20, value['output_tokens'])
        backwards = provider_termination_metadata(start + last + first + event('message_stop'), 'text/event-stream')
        self.assertEqual('unknown', backwards['metadata_status']); self.assertIsNone(backwards['output_tokens'])

    def test_sse_missing_start_usage_or_conflicting_reasons_remain_unknown(self):
        for raw in (event('message_stop'), stream(incoming=True), stream(outgoing=-1),
                    stream(stopped=False) + event('message_delta', delta={'stop_reason': 'max_tokens'}, usage={'output_tokens': 80}) + event('message_stop')):
            value = provider_termination_metadata(raw, 'text/event-stream')
            self.assert_safe(value); self.assertEqual('unknown', value['metadata_status'])

    def test_old_five_argument_budget_call_records_unknown_without_changing_phase(self):
        budget = ModelBudget(1); ordinal = budget.reserve(); budget.started(ordinal, False)
        budget.record(ordinal, 200, .01, 500, False)
        row = budget.snapshot()[0]
        self.assertEqual('completed', row['phase']); self.assertEqual(200, row['http_status'])
        self.assertEqual('unknown', row['termination']['metadata_status'])
        self.assertIsNone(row['termination']['output_tokens']); self.assertFalse(budget.halted)

    def test_budget_sanitizes_metadata_and_snapshot_does_not_share_mutable_objects(self):
        budget = ModelBudget(1)
        supplied = {'format': 'anthropic_json', 'metadata_status': 'observed', 'stop_reason': 'end_turn',
                    'input_tokens': 1, 'output_tokens': 2, 'message_stop_observed': True, 'text': PRIVATE,
                    'arguments': {'key': PRIVATE}, 'reasoning': PRIVATE}
        budget.record(1, 200, .01, 500, False, supplied)
        row = budget.snapshot()[0]; self.assert_safe(row['termination'])
        self.assertIsNone(row['termination']['message_stop_observed'])
        supplied['stop_reason'] = PRIVATE
        row['termination']['stop_reason'] = PRIVATE
        self.assertEqual('end_turn', budget.snapshot()[0]['termination']['stop_reason'])


class MetadataRouteTest(unittest.TestCase):
    def send(self, route):
        payload = encoded({'model': 'deepseek-flash', 'messages': [{'role': 'user', 'content': 'synthetic'}]})
        request = urllib.request.Request(route.base_url + '/v1/messages', data=payload,
            headers={'x-api-key': route.token, 'Content-Type': 'application/json'})
        try: response = urllib.request.urlopen(request, timeout=2)
        except urllib.error.HTTPError as error: response = error
        with response: return response.status, response.read()

    def test_complete_http_response_records_metadata_and_preserves_the_relay_bytes(self):
        raw = stream('max_tokens'); budget = ModelBudget(1)
        with ModelRoute('synthetic-key', budget=budget, transport=lambda _: (200, 'text/event-stream', raw)) as route:
            self.assertEqual((200, raw), self.send(route))
        row = budget.snapshot()[0]
        self.assertEqual('completed', row['phase']); self.assertFalse(row['real_provider_request'])
        self.assertEqual('max_tokens', row['termination']['stop_reason'])
        self.assertEqual(80, row['termination']['output_tokens']); self.assertNotIn(PRIVATE, json.dumps(row))

    def test_complete_http_without_sse_terminal_stays_http_completed_with_unknown_metadata(self):
        raw = stream(stopped=False); budget = ModelBudget(1)
        with ModelRoute('synthetic-key', budget=budget, transport=lambda _: (200, 'text/event-stream', raw)) as route:
            self.assertEqual((200, raw), self.send(route))
        row = budget.snapshot()[0]
        self.assertEqual('completed', row['phase']); self.assertEqual(200, row['http_status'])
        self.assertEqual('unknown', row['termination']['metadata_status'])
        self.assertIs(row['termination']['message_stop_observed'], False)

    def test_incomplete_transport_does_not_invent_metadata_or_relax_the_halt(self):
        def timed_out(_): raise ProviderCompletionUnknown('response_deadline', 200)
        budget = ModelBudget(2)
        with ModelRoute('synthetic-key', budget=budget, transport=timed_out) as route:
            self.assertEqual(502, self.send(route)[0]); self.assertEqual(400, self.send(route)[0])
        row = budget.snapshot()[0]
        self.assertEqual('completion_unknown', row['phase']); self.assertEqual(200, row['http_status'])
        self.assertEqual('unknown', row['termination']['metadata_status'])
        self.assertIsNone(row['termination']['message_stop_observed']); self.assertTrue(budget.halted)


if __name__ == '__main__': unittest.main()
