#!/usr/bin/env python3
"""Summarize Android Phase 3 bench runs with median/range timing probes."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median
from typing import Any


TTFB_OUTLIER_MS = 3000
MIN_RECOMMENDED_RUNS = 3
MAX_RECOMMENDED_RUNS = 5
METRIC_KEYS = [
    "wallMs",
    "roundCalls",
    "modelMs",
    "ttfbMs",
    "ttfbComparableMs",
    "generationMs",
    "backendWallMs",
    "backendSumMs",
    "nativeReadMs",
    "vfsExecuteMs",
    "todoOnlyRounds",
    "finishOnlyRounds",
    "reasoningTokens",
    "cacheReadTokens",
]


def main() -> int:
    args = parse_args()
    run_dirs = discover_run_dirs(args.paths)
    if args.latest:
        run_dirs = run_dirs[-args.latest :]
    runs = [summarize_run(path, args.ttfb_outlier_ms) for path in run_dirs]
    anchor = summarize_run(args.anchor, args.ttfb_outlier_ms) if args.anchor else None
    report = render_report(runs, anchor, args)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"[OK] Wrote {args.output}")
    else:
        print(report)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="Run evidence directories, or parent dirs containing debug-* run dirs.",
    )
    parser.add_argument("--anchor", type=Path, help="Optional single run to compare against.")
    parser.add_argument("--latest", type=int, help="Use only the latest N discovered runs.")
    parser.add_argument("--output", type=Path, help="Optional markdown output path.")
    parser.add_argument(
        "--ttfb-outlier-ms",
        type=int,
        default=TTFB_OUTLIER_MS,
        help="Per-call TTFB above this threshold is flagged separately.",
    )
    return parser.parse_args()


def discover_run_dirs(paths: list[Path]) -> list[Path]:
    discovered: list[Path] = []
    for path in paths:
        if is_run_dir(path):
            discovered.append(path)
            continue
        if path.is_dir():
            discovered.extend(child for child in sorted(path.iterdir()) if is_run_dir(child))
    unique = {item.resolve(): item for item in discovered}
    return [unique[key] for key in sorted(unique)]


def is_run_dir(path: Path) -> bool:
    if not path.is_dir():
        return False
    evidence_names = ["run.json", "result.json", "model-calls.jsonl", "session-tool-events.jsonl"]
    return any((path / name).exists() for name in evidence_names)


def summarize_run(path: Path, outlier_ms: int) -> dict[str, Any]:
    run = read_json(path / "run.json")
    result = read_json(path / "result.json")
    calls = read_jsonl(path / "model-calls.jsonl")
    tool_events = coalesce_tool_events(read_jsonl(path / "session-tool-events.jsonl"))
    vfs_ops = read_jsonl(path / "vfs-ops.jsonl")
    assigned_tools = assign_tools_to_calls(calls, tool_events)
    timing = summarize_model_timing(calls, outlier_ms)
    tools = summarize_tools(tool_events)
    tokens = summarize_tokens(calls)
    vfs = summarize_vfs(vfs_ops)
    composition = summarize_round_composition(calls, assigned_tools)
    return {
        "path": str(path),
        "runId": result.get("runId") or run.get("runId") or path.name,
        "status": result.get("status") or run.get("status") or "",
        "wallMs": wall_ms(run),
        "roundCalls": count_round_calls(calls),
        "modelMs": timing["modelMs"],
        "ttfbMs": timing["ttfbMs"],
        "ttfbComparableMs": timing["ttfbComparableMs"],
        "generationMs": timing["generationMs"],
        "ttfbOutlierCalls": timing["ttfbOutlierCalls"],
        "backendWallMs": source_metric(tools, "BACKEND", "wallMs"),
        "backendSumMs": source_metric(tools, "BACKEND", "sumMs"),
        "nativeReadMs": source_metric(tools, "NATIVE_READ", "sumMs"),
        "nativeTodoMs": source_metric(tools, "NATIVE_TODO", "sumMs"),
        "terminalMs": source_metric(tools, "TERMINAL", "sumMs"),
        "vfsOps": len(vfs_ops),
        "vfsExecuteMs": vfs["executeMs"],
        "vfsMaterializeMs": vfs["materializeMs"],
        "vfsScanMs": vfs["scanMs"],
        "todoOnlyRounds": composition["todoOnlyRounds"],
        "finishOnlyRounds": composition["finishOnlyRounds"],
        **tokens,
    }


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def wall_ms(run: dict[str, Any]) -> int:
    started = int(run.get("createdAtEpochMillis") or 0)
    finished = int(run.get("updatedAtEpochMillis") or 0)
    return max(0, finished - started)


def count_round_calls(calls: list[dict[str, Any]]) -> int:
    return sum(1 for call in calls if str(call.get("source") or "round") == "round")


def coalesce_tool_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_call: dict[str, dict[str, Any]] = {}
    anonymous: list[dict[str, Any]] = []
    for index, event in enumerate(events):
        item = dict(event)
        item["_order"] = index
        call_id = str(item.get("callId") or "")
        if not call_id:
            anonymous.append(item)
            continue
        previous = by_call.get(call_id)
        if previous is None or should_replace_tool_event(previous, item):
            item["_order"] = previous.get("_order", index) if previous else index
            by_call[call_id] = item
    combined = list(by_call.values()) + anonymous
    return sorted(combined, key=lambda item: (int(item.get("startedAtEpochMillis") or 0), item["_order"]))


def should_replace_tool_event(previous: dict[str, Any], item: dict[str, Any]) -> bool:
    previous_end = int(previous.get("finishedAtEpochMillis") or 0)
    item_end = int(item.get("finishedAtEpochMillis") or 0)
    if item_end != previous_end:
        return item_end > previous_end
    previous_size = len(json.dumps(previous, sort_keys=True))
    return len(json.dumps(item, sort_keys=True)) >= previous_size


def assign_tools_to_calls(
    calls: list[dict[str, Any]],
    tool_events: list[dict[str, Any]],
) -> dict[str, list[dict[str, Any]]]:
    assigned: dict[str, list[dict[str, Any]]] = {}
    sorted_calls = sorted(calls, key=lambda item: int(item.get("startedAtEpochMillis") or 0))
    for index, call in enumerate(sorted_calls):
        call_id = str(call.get("callId") or f"call-{index}")
        start = int(call.get("finishedAtEpochMillis") or 0)
        end = next_call_start(sorted_calls, index)
        assigned[call_id] = [event for event in tool_events if start <= event_start(event) < end]
    return assigned


def next_call_start(calls: list[dict[str, Any]], index: int) -> int:
    if index + 1 >= len(calls):
        return 2**63 - 1
    return int(calls[index + 1].get("startedAtEpochMillis") or 0)


def event_start(event: dict[str, Any]) -> int:
    return int(event.get("startedAtEpochMillis") or 0)


def summarize_model_timing(calls: list[dict[str, Any]], outlier_ms: int) -> dict[str, int]:
    summary = {"modelMs": 0, "ttfbMs": 0, "ttfbComparableMs": 0, "generationMs": 0, "ttfbOutlierCalls": 0}
    for call in calls:
        summary["modelMs"] += int(call.get("durationMs") or 0)
        ttfb = delta(call, "startedAtEpochMillis", "firstEventAtEpochMillis")
        generation = generation_ms(call)
        summary["ttfbMs"] += ttfb
        summary["generationMs"] += generation
        if ttfb > outlier_ms:
            summary["ttfbOutlierCalls"] += 1
        else:
            summary["ttfbComparableMs"] += ttfb
    return summary


def generation_ms(call: dict[str, Any]) -> int:
    first_event = int(call.get("firstEventAtEpochMillis") or 0)
    first_tool = int(call.get("firstToolCallAtEpochMillis") or 0)
    finished = int(call.get("finishedAtEpochMillis") or 0)
    if first_event and first_tool:
        return max(0, first_tool - first_event)
    if first_event:
        return max(0, finished - first_event)
    return 0


def delta(item: dict[str, Any], start_name: str, end_name: str) -> int:
    start = int(item.get(start_name) or 0)
    end = int(item.get(end_name) or 0)
    return max(0, end - start) if start and end else 0


def summarize_tools(events: list[dict[str, Any]]) -> dict[str, dict[str, int]]:
    output: dict[str, dict[str, int]] = {}
    for source in sorted({str(event.get("source") or "") for event in events}):
        source_events = [event for event in events if str(event.get("source") or "") == source]
        output[source] = {
            "count": len(source_events),
            "sumMs": sum(event_duration(event) for event in source_events),
            "wallMs": union_ms(source_events),
        }
    return output


def event_duration(event: dict[str, Any]) -> int:
    return max(0, int(event.get("finishedAtEpochMillis") or 0) - event_start(event))


def union_ms(events: list[dict[str, Any]]) -> int:
    intervals = sorted((event_start(event), int(event.get("finishedAtEpochMillis") or 0)) for event in events)
    merged = merge_intervals([(start, end) for start, end in intervals if end > start])
    return sum(end - start for start, end in merged)


def merge_intervals(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[tuple[int, int]] = []
    for start, end in intervals:
        if not merged or start > merged[-1][1]:
            merged.append((start, end))
        else:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
    return merged


def source_metric(tools: dict[str, dict[str, int]], source: str, metric: str) -> int:
    return tools.get(source, {}).get(metric, 0)


def summarize_tokens(calls: list[dict[str, Any]]) -> dict[str, Any]:
    fields = ["inputTokens", "outputTokens", "reasoningTokens", "cacheReadTokens", "cacheWriteTokens"]
    output = {field: sum(int(call.get(field) or 0) for call in calls) for field in fields}
    input_tokens = output["inputTokens"]
    output["cacheReadInputRatio"] = 0.0 if input_tokens <= 0 else output["cacheReadTokens"] / input_tokens
    return output


def summarize_vfs(vfs_ops: list[dict[str, Any]]) -> dict[str, int]:
    fields = ["executeMs", "materializeMs", "scanMs"]
    return {field: sum(int((op.get("timings") or {}).get(field) or 0) for op in vfs_ops) for field in fields}


def summarize_round_composition(
    calls: list[dict[str, Any]],
    assigned_tools: dict[str, list[dict[str, Any]]],
) -> dict[str, int]:
    todo_only = 0
    finish_only = 0
    for index, call in enumerate(calls):
        if str(call.get("source") or "round") != "round":
            continue
        call_id = str(call.get("callId") or f"call-{index}")
        names = [str(event.get("toolName") or "") for event in assigned_tools.get(call_id, [])]
        todo_only += 1 if names == ["update_todo"] else 0
        finish_only += 1 if names == ["finish"] else 0
    return {"todoOnlyRounds": todo_only, "finishOnlyRounds": finish_only}


def render_report(runs: list[dict[str, Any]], anchor: dict[str, Any] | None, args: argparse.Namespace) -> str:
    lines = ["# Android Phase 3 Bench Median Report", ""]
    lines.extend(render_summary(runs, args))
    lines.extend(render_run_table(runs))
    lines.extend(render_aggregate_table(runs))
    if anchor:
        lines.extend(render_anchor_table(runs, anchor))
    return "\n".join(lines) + "\n"


def render_summary(runs: list[dict[str, Any]], args: argparse.Namespace) -> list[str]:
    warning = run_count_warning(len(runs))
    lines = [
        f"- Runs: `{len(runs)}`",
        f"- TTFB outlier threshold: `{args.ttfb_outlier_ms}` ms",
    ]
    if args.latest:
        lines.append(f"- Latest window: `{args.latest}`")
    if warning:
        lines.append(f"- Warning: {warning}")
    lines.extend(f"- Warning: {item}" for item in consistency_warnings(runs))
    lines.append("")
    return lines


def run_count_warning(count: int) -> str:
    if count < MIN_RECOMMENDED_RUNS:
        return f"fewer than {MIN_RECOMMENDED_RUNS} runs; median is not a Round 2 acceptance basis"
    if count > MAX_RECOMMENDED_RUNS:
        return f"more than {MAX_RECOMMENDED_RUNS} runs; use --latest N for a fixed 3-5 run window"
    return ""


def consistency_warnings(runs: list[dict[str, Any]]) -> list[str]:
    warnings: list[str] = []
    if has_mixed_presence(runs, "inputTokens"):
        warnings.append("mixed provider usage fields; cache/reasoning medians are instrumentation-only")
    if has_mixed_presence(runs, "vfsOps"):
        warnings.append("mixed VFS op evidence; VFS medians are not same-configuration acceptance evidence")
    return warnings


def has_mixed_presence(runs: list[dict[str, Any]], key: str) -> bool:
    values = {int(run.get(key) or 0) > 0 for run in runs}
    return len(values) > 1


def render_run_table(runs: list[dict[str, Any]]) -> list[str]:
    lines = [
        "## Per Run",
        "",
        "| run | wall | calls | model | ttfb | comparable ttfb | gen | backend wall/sum | native read | todo-only | finish-only | outliers | cache ratio |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for run in runs:
        lines.append(render_run_row(run))
    lines.append("")
    return lines


def render_run_row(run: dict[str, Any]) -> str:
    backend = f"{run['backendWallMs']}/{run['backendSumMs']}"
    return (
        f"| `{run['runId']}` | {run['wallMs']} | {run['roundCalls']} | {run['modelMs']} | "
        f"{run['ttfbMs']} | {run['ttfbComparableMs']} | {run['generationMs']} | {backend} | "
        f"{run['nativeReadMs']} | {run['todoOnlyRounds']} | {run['finishOnlyRounds']} | "
        f"{run['ttfbOutlierCalls']} | {run['cacheReadInputRatio']:.2f} |"
    )


def render_aggregate_table(runs: list[dict[str, Any]]) -> list[str]:
    lines = ["## Median And Range", "", "| metric | median | min | max |", "|---|---:|---:|---:|"]
    for key in METRIC_KEYS:
        values = [int(run.get(key) or 0) for run in runs]
        lines.append(f"| {key} | {format_number(median(values))} | {min(values)} | {max(values)} |")
    lines.append("")
    return lines


def render_anchor_table(runs: list[dict[str, Any]], anchor: dict[str, Any]) -> list[str]:
    lines = [
        "## Anchor Comparison",
        "",
        f"- Anchor: `{anchor['runId']}`",
        "",
        "| metric | median | anchor | delta |",
        "|---|---:|---:|---:|",
    ]
    for key in METRIC_KEYS:
        values = [int(run.get(key) or 0) for run in runs]
        med = int(median(values))
        anchor_value = int(anchor.get(key) or 0)
        lines.append(f"| {key} | {med} | {anchor_value} | {med - anchor_value:+d} |")
    lines.append("")
    return lines


def format_number(value: float | int) -> str:
    return str(int(value)) if float(value).is_integer() else f"{value:.1f}"


if __name__ == "__main__":
    raise SystemExit(main())
