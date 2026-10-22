"""Read canonical SessionEventLog v2; report timings without inferring task success."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re


def read_event(line, segment):
    value = json.loads(line)
    if value.get("encoding") != "blob":
        return value
    digest = value.get("sha256", "")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid canonical blob digest")
    stream = segment.parent.parent if segment.parent.name == "segments" else segment.parent
    blob = stream / "blobs" / digest
    if not blob.resolve().is_relative_to(stream.resolve()):
        raise ValueError("Canonical blob escapes stream directory")
    raw = blob.read_bytes()
    if len(raw) != value.get("byteSize") or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("Canonical blob size or checksum mismatch")
    return json.loads(raw)


def load_events(paths):
    events = []
    for path in map(Path, paths):
        if path.is_dir() and (path / "segments").is_dir():
            path = path / "segments"
        files = sorted(path.glob("[0-9]*.jsonl")) if path.is_dir() else [path]
        for file in files:
            events.extend(read_event(line, file) for line in file.read_text(encoding="utf-8-sig").splitlines() if line.strip())
    if not events:
        raise ValueError("No canonical events found")
    identities = [(e["sessionId"], e["runId"], e["sequence"]) for e in events]
    if len(set(identities)) != len(identities):
        raise ValueError("Duplicate canonical sequence; do not mix original and copied segments")
    if any(e.get("schemaVersion") != 2 for e in events):
        raise ValueError("Expected SessionEventLog schemaVersion 2")
    return sorted(events, key=lambda e: (e["sessionId"], e["runId"], e["sequence"]))


def unwrap_result(value):
    """Recover typed business status from the historical escaped error wrapper."""
    for _ in range(3):
        if not isinstance(value, dict):
            return {}
        if value.get("code") != "BACKEND_CALL_FAILED":
            return value
        try:
            nested = json.loads(value.get("message", ""))
        except (ValueError, TypeError):
            return value
        if not isinstance(nested, dict):
            return value
        value = nested
    return value if isinstance(value, dict) else {}


def classify_tool_result(payload):
    result = unwrap_result(payload.get("result"))
    if result.get("code") == "SCHEMA_VALIDATION_FAILED":
        return "client_validation"
    message = str(result.get("message", ""))
    if result.get("code") == "BACKEND_CALL_FAILED" and ("already failed" in message or "times in a row" in message):
        return "local_circuit_breaker"
    if result.get("error") is not None:
        return "protocol_error"
    inner = result.get("result", result)
    inner = inner if isinstance(inner, dict) else {}
    structured = inner.get("structuredContent", {})
    status = structured.get("status", {}) if isinstance(structured, dict) else {}
    name = status.get("name") if isinstance(status, dict) else None
    if name:
        if inner.get("isError") is True or result.get("isError") is True or result.get("ok") is False:
            return "business_refusal"
        return "untyped_failure" if payload.get("ok") is False else "business_ok"
    if result.get("code") == "MCP_TRANSPORT_ERROR":
        return "transport_failure"
    if inner.get("isError") is True or result.get("isError") is True or result.get("ok") is False or payload.get("ok") is False:
        return "untyped_failure"
    return "unclassified" if not result else "reported_ok"


def union_duration(intervals):
    total, end = 0, None
    for left, right in sorted(intervals):
        if right < left:
            raise ValueError("Negative interval")
        total += max(0, right - max(left, end if end is not None else left))
        end = max(right, end if end is not None else right)
    return total


def tool_intervals(events):
    starts, spans = {}, []
    for event in events:
        payload = event["payload"]
        key = payload.get("toolCallId")
        if event["type"] == "TOOL_STARTED":
            if key in starts:
                raise ValueError("Duplicate tool-call ID")
            starts[key] = event["recordedAtEpochMillis"]
        elif event["type"] == "TOOL_RESULT" and key in starts:
            spans.append((starts.pop(key), event["recordedAtEpochMillis"]))
    return spans, len(starts)


def model_windows(events):
    windows, current = [], None
    for event in events:
        if event["type"] == "MODEL_REQUEST":
            if current is not None:
                windows.append(current)
            current = {"request": event, "events": [], "end": None}
        elif current is not None:
            current["events"].append(event)
            if event["type"] == "MODEL_CALL_FINISHED" and event["payload"].get("source", "round") in ("round", "compaction"):
                current["end"] = event
                windows.append(current)
                current = None
    if current is not None:
        windows.append(current)
    return windows


def timing_metadata(events):
    timings = {}
    for event in events:
        fields = event["payload"].get("fields", {})
        if isinstance(fields, dict) and fields.get("kind") == "runtime_timing":
            timings[fields["callId"]] = fields.get("timings", {})
    return timings


def analyze_window(window, spans, timings):
    request, end, events = window["request"], window["end"], window["events"]
    started = request["recordedAtEpochMillis"]
    first = next((e for e in events if e["type"] in ("ASSISTANT_CHUNK", "MODEL_TOOL_CALL")
                  and (e["type"] == "MODEL_TOOL_CALL" or e["payload"].get("text"))), None)
    usage = [e["payload"] for e in events if e["type"] == "USAGE"]
    finished = end["recordedAtEpochMillis"] if end else None
    overlaps = [(max(started, a), min(finished, b)) for a, b in spans
                if finished is not None and a < finished and b > started]
    payload = end["payload"] if end else {}
    return {
        "callId": payload.get("callId"), "status": payload.get("status", "MISSING_TERMINAL"),
        "source": payload.get("source", "round") if end else None,
        "modelRef": request["payload"].get("modelRef", ""),
        "firstContentMs": first["recordedAtEpochMillis"] - started if first else None,
        "observedWindowMs": finished - started if finished is not None else None,
        "recordedCallMs": payload.get("durationMs"),
        "overlappingToolMs": union_duration(overlaps) if end else None,
        "inputTokens": sum(u.get("inputTokens", 0) for u in usage) if usage else None,
        "outputTokens": sum(u.get("outputTokens", 0) for u in usage) if usage else None,
        "timings": timings.get(payload.get("callId")),
    }


def health_status(events, health):
    sequences = [e["sequence"] for e in events]
    contiguous = sequences == list(range(sequences[0], sequences[-1] + 1))
    if not health:
        return "UNVERIFIED", contiguous
    identity = events[0]
    matches = all(health.get(key) == identity[key] for key in ("sessionId", "runId"))
    complete = matches and contiguous and sequences[0] == 1 and health.get("status") == "COMPLETE"
    complete = complete and health.get("failedCount") == 0
    complete = complete and health.get("lastAcceptedSequence") == health.get("lastPersistedSequence") == sequences[-1]
    return ("COMPLETE" if complete else "INCOMPLETE"), contiguous


def percentile(values, quantile):
    return sorted(values)[max(0, math.ceil(len(values) * quantile) - 1)] if values else None


def analyze_run(events, health=None):
    spans, pending = tool_intervals(events)
    metadata = timing_metadata(events)
    windows = model_windows(events)
    calls = [analyze_window(w, spans, metadata) for w in windows]
    complete, contiguous = health_status(events, health)
    backend = [e["payload"] for e in events if e["type"] == "TOOL_RESULT"
               and e["payload"].get("name", "").startswith(("mcp__", "command_"))]
    terminal = next((e["payload"] for e in reversed(events) if e["type"] == "RUN_FINISHED"), {})
    durations = [c["observedWindowMs"] for c in calls if c["observedWindowMs"] is not None]
    return {
        "sessionId": events[0]["sessionId"], "runId": events[0]["runId"],
        "eventHealth": complete, "sequenceContiguous": contiguous,
        "lifecycleStatus": terminal.get("status", "MISSING_TERMINAL"), "taskAcceptance": "NOT_EVALUATED",
        "eventCount": len(events), "modelCalls": calls,
        "preModelMs": windows[0]["request"]["recordedAtEpochMillis"] - events[0]["recordedAtEpochMillis"] if windows else None,
        "toolUnionMs": union_duration(spans), "unfinishedToolCalls": pending,
        "backendResultCounts": dict(Counter(map(classify_tool_result, backend))),
        "withinRunModelWindowP50Ms": percentile(durations, 0.5),
        "withinRunModelWindowP95Ms": percentile(durations, 0.95),
        "note": "Model windows may contain tools and local overhead; do not add them to tool time or label them pure inference. Missing usage is unknown. Single-run model percentiles are not repeated-task latency or task success.",
    }


def analyze(events, health=None):
    groups = defaultdict(list)
    for event in events:
        groups[(event["sessionId"], event["runId"])].append(event)
    return {"runs": [analyze_run(sorted(rows, key=lambda e: e["sequence"]), health) for rows in groups.values()]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="Canonical numbered-segment directory or JSONL files")
    parser.add_argument("--health", type=Path, help="Original per-run health JSON; missing evidence stays UNVERIFIED")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    health = json.loads(args.health.read_text(encoding="utf-8-sig")) if args.health else None
    rendered = json.dumps(analyze(load_events(args.paths), health), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)


if __name__ == "__main__":
    main()
