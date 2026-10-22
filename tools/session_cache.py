"""Prompt-cache and tool-discovery report for one canonical SessionEventLog session.

Reads the v2 run streams of a session (pulled from a device with --pull, or already on disk) and reports, per run and
in total: model calls, input tokens, provider cache reads, search_tools calls, and how each model request differs from
the previous one in the same session. Usage frames are summed per call: the first frame carries the input estimate and
the closing frame the cache reads; a call without a closing frame (output 0) is counted separately, because its cache
reads are unknown rather than zero.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

# The app whose logs --pull reads, and where it keeps them (AndroidAgentRuntime.builder's default); see --package.
PACKAGE = ""
LOG_ROOT = "no_backup/agent-runtime/event-log"
SEARCH_TOOL = "search_tools"
CALL_TOOL = "call_tool"
COMPACTION_INSTRUCTION_START = "You are now compacting this conversation."
NON_BUSINESS_TOOLS = {SEARCH_TOOL, "read_file", "list_dir", "grep", "update_todo", "finish", "ask", "ask_async"}


def b64(text):
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4)).decode()


def adb(serial, *args, binary=False):
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    output = subprocess.run(command, check=True, capture_output=True).stdout
    return output if binary else output.decode().replace("\r", "")


def run_as(serial, shell):
    return adb(serial, "shell", f"run-as {PACKAGE} sh -c '{shell}'")


def pull(serial, session, out):
    """Copies a session's run streams (segments and blobs) from the device into out/<run>/."""
    sessions = run_as(serial, f"ls -t {LOG_ROOT}").split()
    if session == "latest":
        # A background subagent logs under "<conversation>-sub-N"; latest means the newest conversation.
        session_dir = next(s for s in sessions if not re.search(r"-sub-\d+$", unb64(s)))
    else:
        session_dir = b64(session)
        if session_dir not in sessions:
            raise SystemExit(f"No event log for session {session}; known: {[unb64(s) for s in sessions[:10]]}")
    streams = f"{LOG_ROOT}/{session_dir}/session-events/v2/streams/{session_dir}"
    for run in run_as(serial, f"ls {streams}").split():
        for kind in ("segments", "blobs"):
            names = run_as(serial, f"ls {streams}/{run}/{kind} 2>/dev/null; true").split()
            target = Path(out) / run / kind
            for name in names:
                target.mkdir(parents=True, exist_ok=True)
                (target / name).write_bytes(adb(serial, "exec-out", "run-as", PACKAGE, "cat", f"{streams}/{run}/{kind}/{name}", binary=True))
    return unb64(session_dir)


def read_event(line, segment):
    value = json.loads(line)
    if value.get("encoding") != "blob":
        return value
    digest = value.get("sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", digest or ""):
        raise ValueError("Invalid canonical blob digest")
    raw = (segment.parent.parent / "blobs" / digest).read_bytes()
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("Canonical blob checksum mismatch")
    return json.loads(raw)


def load_runs(root):
    runs = []
    for run_dir in sorted(Path(root).iterdir()):
        segments = sorted((run_dir / "segments").glob("[0-9]*.jsonl"))
        events = [read_event(line, file) for file in segments for line in file.read_text(encoding="utf-8").splitlines() if line.strip()]
        if events:
            runs.append(sorted(events, key=lambda e: e["sequence"]))
    return sorted(runs, key=lambda events: events[0]["recordedAtEpochMillis"])


def tool_names(tools):
    return [tool.get("name") if isinstance(tool, dict) else str(tool) for tool in tools]


def first_difference(a, b):
    """Index of the first differing element (or character), or the shorter length when one extends the other."""
    for index, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return index
    return min(len(a), len(b))


def summarize(events):
    calls, current = [], None
    search_calls = business_calls = 0
    first_business_call = None
    user_text = ""
    for event in events:
        kind, payload = event["type"], event["payload"]
        if kind == "USER_MESSAGE" and not user_text:
            user_text = payload.get("text", "")
        elif kind == "MODEL_REQUEST":
            raw_messages = payload.get("messages", [])
            last = raw_messages[-1].get("content", "") if raw_messages and isinstance(raw_messages[-1], dict) else ""
            current = {
                "system": payload.get("systemPrompt", ""),
                "tools": tool_names(payload.get("tools", [])),
                "messages": [json.dumps(m, ensure_ascii=False, sort_keys=True) for m in raw_messages],
                "input": 0, "cache": 0, "output": 0,
                # The session-level summary request ends with the compaction instruction.
                "compaction": last.startswith(COMPACTION_INSTRUCTION_START),
            }
            calls.append(current)
        elif kind == "USAGE" and current is not None:
            current["input"] += payload.get("inputTokens", 0)
            current["cache"] += payload.get("cacheReadTokens", 0)
            current["output"] += payload.get("outputTokens", 0)
        elif kind == "MODEL_TOOL_CALL":
            name = payload.get("name", "")
            if name == CALL_TOOL:
                # A deferred backend tool, called through call_tool: count the tool it names.
                arguments = payload.get("arguments", payload.get("argumentsJson", {}))
                if isinstance(arguments, str):
                    try:
                        arguments = json.loads(arguments)
                    except ValueError:
                        arguments = {}
                name = (arguments or {}).get("name", "") if isinstance(arguments, dict) else ""
            if name == SEARCH_TOOL:
                search_calls += 1
            elif name.startswith("mcp__") and not name.startswith("mcp__car_ui__"):
                business_calls += 1
                if first_business_call is None:
                    first_business_call = len(calls)
    started, finished = events[0]["recordedAtEpochMillis"], events[-1]["recordedAtEpochMillis"]
    return {
        "user": user_text, "calls": calls, "search": search_calls, "business": business_calls,
        "firstBusinessCall": first_business_call, "seconds": (finished - started) / 1000,
    }


def report(runs):
    previous = None
    totals = {"input": 0, "cache": 0, "firstInput": 0, "firstCache": 0, "calls": 0, "unclosed": 0,
              "search": 0, "seconds": 0.0, "directBusiness": 0, "businessRuns": 0}
    print(f"{'run':>3} {'calls':>5} {'search':>6} {'secs':>6} {'cache%':>7} {'first%':>7}  first-request prefix vs previous request  | user")
    for index, events in enumerate(runs, 1):
        run = summarize(events)
        calls = run["calls"]
        if not calls:
            continue
        closed = [c for c in calls if c["output"] > 0]
        totals["unclosed"] += len(calls) - len(closed)
        totals["input"] += sum(c["input"] for c in closed)
        totals["cache"] += sum(c["cache"] for c in closed)
        totals["calls"] += len(calls)
        first = calls[0]
        if first["output"] > 0:
            totals["firstInput"] += first["input"]
            totals["firstCache"] += first["cache"]
        totals["search"] += run["search"]
        totals["seconds"] += run["seconds"]
        if run["business"]:
            totals["businessRuns"] += 1
            if run["firstBusinessCall"] == 1:
                totals["directBusiness"] += 1
        prefix = ""
        if previous is not None:
            system_at = first_difference(previous["system"].encode(), first["system"].encode())
            tools_at = first_difference(previous["tools"], first["tools"])
            prefix = (f"system same {system_at}/{len(first['system'].encode())}B, "
                      f"tools same {tools_at}/{len(first['tools'])}")
        rate = sum(c["cache"] for c in closed) * 100 // max(sum(c["input"] for c in closed), 1)
        first_rate = first["cache"] * 100 // max(first["input"], 1) if first["output"] > 0 else -1
        print(f"{index:>3} {len(calls):>5} {run['search']:>6} {run['seconds']:>6.1f} {rate:>6}% {first_rate:>6}%  {prefix:<46} | {run['user'][:40]}")
        previous = calls[-1]
    count = len(runs)
    print()
    print(f"runs {count}, model calls {totals['calls']} ({totals['calls'] / max(count, 1):.1f}/run), "
          f"calls without closing usage {totals['unclosed']}")
    print(f"cache read {totals['cache']}/{totals['input']} input tokens = {totals['cache'] * 100 / max(totals['input'], 1):.1f}%; "
          f"first request of each run {totals['firstCache'] * 100 / max(totals['firstInput'], 1):.1f}%; "
          f"uncached input {totals['input'] - totals['cache']}")
    print(f"uncached input by call: {miss_breakdown(runs)}")
    print(f"search_tools {totals['search']} ({totals['search'] / max(count, 1):.2f}/run); "
          f"runs whose first model call already called a business tool {totals['directBusiness']}/{totals['businessRuns']}; "
          f"time {totals['seconds']:.1f}s ({totals['seconds'] / max(count, 1):.1f}s/run)")


def miss_breakdown(runs):
    """Uncached input by call type: a run's first call, a call whose tool list changed, and the rest."""
    buckets = {"first call": [0, 0], "tools changed": [0, 0], "tools unchanged": [0, 0]}
    for events in runs:
        previous = None
        for index, call in enumerate(summarize(events)["calls"]):
            if call["output"] > 0:
                kind = "first call" if index == 0 else ("tools changed" if call["tools"] != previous["tools"] else "tools unchanged")
                buckets[kind][0] += call["input"] - call["cache"]
                buckets[kind][1] += 1
            previous = call
    total = max(sum(value[0] for value in buckets.values()), 1)
    return ", ".join(f"{kind} {value[0]} ({value[0] * 100 // total}%, {value[1]} calls)" for kind, value in buckets.items())


def compaction_report(runs):
    """Each run's request sizes, marking session compaction calls, to show where the context was cut back."""
    lines = []
    for index, events in enumerate(runs, 1):
        calls = summarize(events)["calls"]
        sizes = " ".join(f"[compact {c['input']}→]" if c["compaction"] else str(c["input"]) for c in calls)
        lines.append(f"{index:>3} {sizes}")
    return "\n".join(lines)


def children_report(serial, session, root):
    """The conversation's background subagents from its subagents.jsonl; each child's log is pulled under root/children."""
    records = run_as(serial, f"cat {LOG_ROOT}/{b64(session)}/subagents.jsonl 2>/dev/null; true").splitlines()
    lines = [f"subagents: {len(records)}"]
    for record in (json.loads(line) for line in records if line.strip()):
        seconds = (record["finishedAt"] - record["startedAt"]) / 1000
        cache = f"{record['cachedTokens'] * 100 / record['inputTokens']:.0f}%" if record["inputTokens"] else "-"
        lines.append(
            f"  {record['id']} 「{record['label']}」 {record['state']} {seconds:.1f}s, tools {record['toolCalls']}, "
            f"model calls {record['modelCalls']}, input {record['inputTokens']} (cache {cache}), output {record['outputTokens']}"
        )
        if record.get("error"):
            lines.append(f"     error: {record['error']}")
        child_root = Path(root) / "children" / record["id"]
        pull(serial, record["sessionId"], child_root)
        for events in load_runs(child_root):
            names = [e["payload"].get("name") for e in events if e["type"] == "MODEL_TOOL_CALL"]
            lines.append(f"     run tools: {', '.join(n for n in names if n) or '-'}")
    return "\n".join(lines)


def main():
    global PACKAGE, LOG_ROOT
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", help="Directory holding <run>/segments (filled by --pull when given)")
    parser.add_argument("--pull", metavar="SESSION", help="Pull this session id from the device first, or 'latest'")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--package", help="With --pull: the app's package (it must be debuggable for run-as)")
    parser.add_argument("--log-root", default=LOG_ROOT, help="With --pull: the event log root inside the app's data dir")
    parser.add_argument("--sizes", action="store_true", help="Also list every request's input tokens, marking compaction")
    parser.add_argument("--children", action="store_true", help="With --pull: also report the session's background subagents")
    args = parser.parse_args()
    PACKAGE, LOG_ROOT = args.package or "", args.log_root
    if args.pull and not PACKAGE:
        parser.error("--pull needs --package")
    session = None
    if args.pull:
        session = pull(args.serial, args.pull, args.root)
        print(f"session {session}")
    runs = load_runs(args.root)
    report(runs)
    if args.sizes:
        print()
        print(compaction_report(runs))
    if args.children and session:
        print()
        print(children_report(args.serial, session, args.root))


if __name__ == "__main__":
    main()
