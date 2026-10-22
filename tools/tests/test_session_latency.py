"""Independent fixtures for timing accounting and historical business-error recovery."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from session_latency import analyze_run, classify_tool_result, load_events, union_duration


def event(sequence, kind, at, payload):
    return dict(schemaVersion=2, sessionId="s", runId="r", sequence=sequence,
                type=kind, recordedAtEpochMillis=at, payload=payload)


class SessionLatencyTests(unittest.TestCase):
    def test_compaction_requests_have_their_own_completed_window_and_usage(self):
        events = [event(1, "RUN_STARTED", 0, {}), event(2, "MODEL_REQUEST", 10, {}),
                  event(3, "USAGE", 15, {"inputTokens": 7, "outputTokens": 2}),
                  event(4, "MODEL_CALL_FINISHED", 20, {"source": "compaction", "status": "COMPLETED", "callId": "c"}),
                  event(5, "MODEL_REQUEST", 30, {}),
                  event(6, "MODEL_CALL_FINISHED", 40, {"source": "round", "status": "COMPLETED", "callId": "r"})]
        calls = analyze_run(events)["modelCalls"]
        self.assertEqual(["compaction", "round"], [c["source"] for c in calls])
        self.assertEqual([10, 10], [c["observedWindowMs"] for c in calls])
        self.assertEqual(7, calls[0]["inputTokens"])
        self.assertIsNone(calls[1]["inputTokens"])

    def test_inline_and_blob_events_load_without_losing_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "blobs").mkdir()
            (root / "segments").mkdir()
            first = event(1, "RUN_STARTED", 0, {})
            second = event(2, "MODEL_REQUEST", 10, {"systemPrompt": "导航" * 1000})
            raw = json.dumps(second, ensure_ascii=False).encode()
            digest = hashlib.sha256(raw).hexdigest()
            (root / "blobs" / digest).write_bytes(raw)
            envelope = dict(schemaVersion=2, encoding="blob", sha256=digest, byteSize=len(raw))
            segment = root / "segments" / "000001.jsonl"
            segment.write_text(json.dumps(first) + "\n" + json.dumps(envelope) + "\n", encoding="utf-8")
            self.assertEqual([first, second], load_events([root]))
            self.assertEqual([first, second], load_events([root / "segments"]))
            self.assertEqual([first, second], load_events([segment]))

    def test_missing_corrupt_or_unsafe_blob_fails_instead_of_claiming_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "blobs").mkdir()
            raw = json.dumps(event(1, "RUN_STARTED", 0, {})).encode()
            digest = hashlib.sha256(raw).hexdigest()
            blob = root / "blobs" / digest
            envelope = dict(schemaVersion=2, encoding="blob", sha256=digest, byteSize=len(raw))
            segment = root / "000001.jsonl"
            segment.write_text(json.dumps(envelope), encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                load_events([root])
            blob.write_bytes(raw + b" ")
            with self.assertRaisesRegex(ValueError, "checksum"):
                load_events([root])
            blob.write_bytes(b"x" * len(raw))
            with self.assertRaisesRegex(ValueError, "checksum"):
                load_events([root])
            envelope["sha256"] = "../outside"
            segment.write_text(json.dumps(envelope), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "digest"):
                load_events([root])

    def test_business_refusal_survives_legacy_escaped_wrapper(self):
        raw = {"error": None, "result": {"isError": True, "structuredContent": {
            "status": {"name": "INVALID_ARGUMENT", "details": {"reason": "too_many_items"}}}}}
        wrapped = {"ok": False, "result": {"code": "BACKEND_CALL_FAILED", "message": json.dumps(raw)}}
        self.assertEqual("business_refusal", classify_tool_result(wrapped))
        self.assertEqual("business_refusal", classify_tool_result({"ok": False, "result": raw}))

    def test_breaker_transport_and_business_are_distinct(self):
        self.assertEqual("business_ok", classify_tool_result({"result": {
            "isError": False, "structuredContent": {"status": {"name": "READY"}}}}))
        self.assertEqual("client_validation", classify_tool_result({"result": {"code": "SCHEMA_VALIDATION_FAILED"}}))
        self.assertEqual("local_circuit_breaker", classify_tool_result({"result": {
            "code": "BACKEND_CALL_FAILED", "message": "Tool via failed 4 times in a row in this run"}}))
        self.assertEqual("transport_failure", classify_tool_result({"result": {"code": "MCP_TRANSPORT_ERROR"}}))
        self.assertEqual("protocol_error", classify_tool_result({"result": {"error": {"code": -32603}}}))

    def test_overlapping_tools_are_counted_as_union_not_added_twice(self):
        self.assertEqual(30, union_duration([(10, 30), (20, 40)]))
        events = [event(1, "RUN_STARTED", 0, {}), event(2, "MODEL_REQUEST", 10, {"modelRef": "fixture"}),
                  event(3, "ASSISTANT_CHUNK", 20, {"kind": "thinking", "text": "reason"}),
                  event(4, "TOOL_STARTED", 30, {"toolCallId": "a"}),
                  event(5, "TOOL_STARTED", 40, {"toolCallId": "b"}),
                  event(6, "TOOL_RESULT", 50, {"toolCallId": "a"}),
                  event(7, "TOOL_RESULT", 60, {"toolCallId": "b"}),
                  event(8, "MODEL_CALL_FINISHED", 100, {"callId": "c", "source": "round", "durationMs": 90})]
        result = analyze_run(events)
        call = result["modelCalls"][0]
        self.assertEqual(10, call["firstContentMs"])
        self.assertEqual(90, call["observedWindowMs"])
        self.assertEqual(30, call["overlappingToolMs"])
        self.assertIsNone(call["inputTokens"])
        self.assertEqual("UNVERIFIED", result["eventHealth"])
        self.assertEqual("NOT_EVALUATED", result["taskAcceptance"])

    def test_health_must_match_identity_sequences_and_terminal_evidence(self):
        events = [event(1, "RUN_STARTED", 0, {}), event(2, "RUN_FINISHED", 10, {"status": "FAILED"})]
        health = dict(status="COMPLETE", sessionId="s", runId="r", failedCount=0,
                      lastAcceptedSequence=2, lastPersistedSequence=2)
        result = analyze_run(events, health)
        self.assertEqual("COMPLETE", result["eventHealth"])
        self.assertEqual("FAILED", result["lifecycleStatus"])
        self.assertEqual("NOT_EVALUATED", result["taskAcceptance"])
        self.assertEqual("INCOMPLETE", analyze_run(events, dict(health, runId="other"))["eventHealth"])
        self.assertEqual("INCOMPLETE", analyze_run(events, dict(health, lastPersistedSequence=1))["eventHealth"])

    def test_missing_model_terminal_and_usage_stay_unknown(self):
        events = [event(1, "RUN_STARTED", 0, {}), event(2, "MODEL_REQUEST", 10, {})]
        result = analyze_run(events)
        self.assertEqual("MISSING_TERMINAL", result["modelCalls"][0]["status"])
        self.assertIsNone(result["modelCalls"][0]["observedWindowMs"])
        self.assertIsNone(result["withinRunModelWindowP95Ms"])


if __name__ == "__main__":
    unittest.main()
