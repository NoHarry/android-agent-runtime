"""Multi-run / multi-arm report + dual-read (SPEC-RPB-007/008)."""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .case_pack import CasePack, CasePackError, load_case_pack
from .gates import GateResult, PackEvalResult, evaluate_pack
from .graph import ToolGraph, build_tool_graph
from .legacy_vfs import load_vfs_facts


@dataclass
class RunAnalysis:
    run_dir: str
    run_id: str
    arm_id: str
    completeness: dict[str, Any]
    graph: dict[str, Any]
    pack_status: str
    gates: list[dict[str, Any]]
    meta: dict[str, Any]
    sources: list[str]
    incomplete_reasons: list[str] = field(default_factory=list)
    comparable: bool = True
    timing: dict[str, Any] = field(default_factory=dict)
    vfs: dict[str, Any] = field(default_factory=dict)
    compatibility: dict[str, Any] = field(default_factory=dict)


def read_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    out: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def is_run_dir(path: Path) -> bool:
    if not path.is_dir():
        return False
    markers = [
        "probes.jsonl",
        "probe-completeness.json",
        "run-meta.json",
        "run.json",
        "result.json",
        "model-calls.jsonl",
        "session-tool-events.jsonl",
        "vfs-ops.jsonl",
    ]
    return any((path / m).exists() for m in markers)


def discover_run_dirs(paths: list[Path]) -> list[Path]:
    found: list[Path] = []
    for p in paths:
        if is_run_dir(p):
            found.append(p)
            continue
        if p.is_dir():
            found.extend(c for c in sorted(p.iterdir()) if is_run_dir(c))
    uniq = {x.resolve(): x for x in found}
    return [uniq[k] for k in sorted(uniq)]


def _legacy_model_call_events(call: dict[str, Any], seq: int) -> list[dict[str, Any]]:
    """Map one model-calls record into start/end probe-like stages."""
    return [
        {
            "sequence": seq,
            "stageId": "model.round.start",
            "probeId": "legacy.model",
            "elapsedNanos": int(call.get("startedAtEpochMillis") or 0) * 1_000_000,
            "attrs": {"roundIndex": call.get("roundIndex", 0)},
        },
        {
            "sequence": seq + 1,
            "stageId": "model.round.end",
            "probeId": "legacy.model",
            "elapsedNanos": int(call.get("finishedAtEpochMillis") or 0) * 1_000_000,
            "attrs": {
                "roundIndex": call.get("roundIndex", 0),
                "status": call.get("status") or "ok",
            },
        },
    ]


def _legacy_tool_event(te: dict[str, Any], seq: int) -> dict[str, Any]:
    """Map one session-tool-events record into a probe-like stage."""
    name = te.get("toolName") or te.get("name") or ""
    call_id = str(te.get("toolCallId") or te.get("callId") or f"legacy-{seq}")
    phase = (te.get("phase") or te.get("status") or "").lower()
    if phase in ("start", "started", "begin"):
        stage = "tool.execute.start"
        attrs = {"toolName": name, "callId": call_id, "source": te.get("source") or "BACKEND"}
        if te.get("roundIndex") is not None:
            attrs["roundIndex"] = te["roundIndex"]
    else:
        stage = "tool.execute.end"
        attrs = {
            "toolName": name,
            "callId": call_id,
            "ok": te.get("ok", True) if "ok" in te else (te.get("status") != "failed"),
            "source": te.get("source") or "BACKEND",
        }
        if te.get("roundIndex") is not None:
            attrs["roundIndex"] = te["roundIndex"]
        if isinstance(te.get("arguments"), dict):
            attrs["args"] = te["arguments"]
        elif isinstance(te.get("args"), dict):
            attrs["args"] = te["args"]
    return {
        "sequence": seq,
        "stageId": stage,
        "probeId": "legacy.tool",
        "elapsedNanos": int(te.get("epochMillis") or te.get("at") or 0) * 1_000_000,
        "attrs": attrs,
    }


def legacy_events_to_probe_like(run_dir: Path) -> list[dict[str, Any]]:
    """Map model-calls / session-tool-events into probe-like stages for dual-read."""
    events: list[dict[str, Any]] = []
    seq = 0
    for call in read_jsonl(run_dir / "model-calls.jsonl"):
        events.extend(_legacy_model_call_events(call, seq))
        seq += 2
    for te in read_jsonl(run_dir / "session-tool-events.jsonl"):
        events.append(_legacy_tool_event(te, seq))
        seq += 1
    return events


def load_run_events(run_dir: Path) -> tuple[list[dict[str, Any]], list[str]]:
    sources: list[str] = []
    probes = read_jsonl(run_dir / "probes.jsonl")
    if probes:
        sources.append("probes.jsonl")
        return probes, sources
    legacy = legacy_events_to_probe_like(run_dir)
    if legacy:
        sources.append("legacy:model-calls+session-tool-events")
    return legacy, sources


def load_completeness(run_dir: Path) -> dict[str, Any]:
    side = read_json(run_dir / "probe-completeness.json")
    if side:
        return side
    # dual-read incomplete if no side-car
    return {
        "schemaVersion": 1,
        "runId": run_dir.name,
        "sequenceMax": -1,
        "enqueued": 0,
        "written": 0,
        "dropped": 0,
        "truncated": 0,
        "error": 0,
        "drainTimedOut": False,
        "sinkIoFailed": False,
        "completenessIoFailed": False,
        "complete": False,
        "probesDisabled": False,
        "missingSideCar": True,
    }


def load_meta(run_dir: Path) -> dict[str, Any]:
    meta = read_json(run_dir / "run-meta.json")
    if meta:
        return meta
    run = read_json(run_dir / "run.json") or {}
    return {
        "runId": run.get("runId") or run_dir.name,
        "sessionId": run.get("sessionId") or "unknown",
        "armId": run.get("armId") or "default",
        "comparable": False,
        "incompleteReasons": ["missing run-meta.json"],
        "declaredVariables": [],
        "controlFingerprint": "",
        "configFingerprint": "",
        "caseBinding": {"casePackId": "none"},
        "enabledProbePacks": [],
        "isolation": {"session": "unknown", "memory": "unknown", "cache": "unknown"},
        "buildId": run.get("buildId") or "",
    }


def _pair_durations_ms(
    starts: list[dict[str, Any]], ends: list[dict[str, Any]]
) -> list[int]:
    # pair by order when callId/roundIndex unavailable
    durs: list[int] = []
    n = min(len(starts), len(ends))
    for i in range(n):
        sn = int(starts[i].get("elapsedNanos") or 0)
        en = int(ends[i].get("elapsedNanos") or 0)
        if en >= sn:
            durs.append((en - sn) // 1_000_000)
    return durs


def _duration_stats(values: list[int]) -> dict[str, Any]:
    if not values:
        return {"count": 0, "min": None, "max": None, "median": None, "sum": 0}
    s = sorted(values)
    mid = len(s) // 2
    if len(s) % 2:
        med = s[mid]
    else:
        med = (s[mid - 1] + s[mid]) // 2
    return {
        "count": len(s),
        "min": s[0],
        "max": s[-1],
        "median": med,
        "sum": sum(s),
    }


def _wall_ms_from_events(events: list[dict[str, Any]]) -> int | None:
    """Prefer non-zero epoch span; fall back to elapsedNanos when epochs collapse."""
    nanos = [int(e.get("elapsedNanos") or 0) for e in events if e.get("elapsedNanos") is not None]
    epochs = [int(e.get("epochMillis") or 0) for e in events if e.get("epochMillis") is not None]
    epoch_span = (max(epochs) - min(epochs)) if len(epochs) >= 2 else None
    nano_span_ms = (
        max(0, (max(nanos) - min(nanos)) // 1_000_000) if len(nanos) >= 2 else None
    )
    if epoch_span is not None and epoch_span > 0:
        return epoch_span
    if nano_span_ms is not None:
        return nano_span_ms
    return epoch_span


def _ttfb_ms_from_events(
    run_starts: list[dict[str, Any]],
    model_starts: list[dict[str, Any]],
) -> int | None:
    first_model = model_starts[0] if model_starts else None
    if not run_starts or first_model is None:
        return None
    rs = int(run_starts[0].get("elapsedNanos") or 0)
    fm = int(first_model.get("elapsedNanos") or 0)
    if fm >= rs:
        return (fm - rs) // 1_000_000
    if run_starts[0].get("epochMillis") is not None and first_model.get("epochMillis") is not None:
        return int(first_model["epochMillis"]) - int(run_starts[0]["epochMillis"])
    return None


def _round_count(events: list[dict[str, Any]], model_starts: list[dict[str, Any]]) -> int:
    round_indexes = set()
    for e in events:
        attrs = e.get("attrs") or {}
        if attrs.get("roundIndex") is not None:
            round_indexes.add(int(attrs["roundIndex"]))
    return len(round_indexes) if round_indexes else max(len(model_starts), 0)


def compute_timing(events: list[dict[str, Any]], meta: dict[str, Any]) -> dict[str, Any]:
    """Derive wall / rounds / model-tool timing / TTFB / median-min-max from probe events.

    Prefer wall-clock epochs when they actually span more than 0ms. Fixed-epoch golden
    clocks (all events share one epochMillis) must fall back to elapsedNanos so wall
    time is not silently reported as 0.
    """
    model_starts = [e for e in events if e.get("stageId") == "model.round.start"]
    model_ends = [e for e in events if e.get("stageId") == "model.round.end"]
    tool_starts = [e for e in events if e.get("stageId") == "tool.execute.start"]
    tool_ends = [e for e in events if e.get("stageId") == "tool.execute.end"]
    run_starts = [e for e in events if e.get("stageId") == "run.start"]
    return {
        "wallMs": _wall_ms_from_events(events),
        "rounds": _round_count(events, model_starts),
        "ttfbMs": _ttfb_ms_from_events(run_starts, model_starts),
        "model": _duration_stats(_pair_durations_ms(model_starts, model_ends)),
        "tool": _duration_stats(_pair_durations_ms(tool_starts, tool_ends)),
        "eventCount": len(events),
        "startedAtEpochMillis": meta.get("startedAtEpochMillis"),
    }


_PROMPT_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _normalize_case_pack_id(raw: Any) -> str:
    if isinstance(raw, str):
        return raw.strip() or "none"
    if raw is None:
        return "none"
    return "none"


def _validate_run_prompt_hex(run_prompt: Any) -> tuple[str | None, list[str]]:
    errors: list[str] = []
    if isinstance(run_prompt, str) and run_prompt.strip():
        run_hex = run_prompt.strip().lower()
        if not _PROMPT_SHA256_RE.fullmatch(run_hex):
            errors.append("case_binding_invalid_promptSha256")
            return None, errors
        return run_hex, errors
    errors.append("case_binding_missing_promptSha256")
    return None, errors


def _validate_pack_prompt_hex(pack: CasePack, run_hex: str | None) -> list[str]:
    errors: list[str] = []
    pack_prompt = pack.prompt or {}
    pack_hex = pack_prompt.get("sha256") if isinstance(pack_prompt, dict) else None
    if not isinstance(pack_hex, str) or not pack_hex.strip():
        errors.append("case_binding_pack_missing_prompt_sha256")
        return errors
    pack_hex = pack_hex.strip().lower()
    if not _PROMPT_SHA256_RE.fullmatch(pack_hex):
        errors.append("case_binding_pack_invalid_prompt_sha256")
    elif run_hex is not None and run_hex != pack_hex:
        errors.append(f"case_binding_prompt_mismatch: run={run_hex} pack={pack_hex}")
    return errors


def validate_case_binding(meta: dict[str, Any], pack: CasePack | None) -> list[str]:
    """Require run-meta caseBinding to match the attached Case Pack (no silent rebinding).

    detail-design §prompt hash: when binding, run.caseBinding.promptSha256 must equal
    pack prompt sha256 (case-insensitive after lowercasing). Presence alone is not enough.
    """
    if pack is None:
        return []
    cb = meta.get("caseBinding") or {}
    pack_id = _normalize_case_pack_id(
        cb.get("casePackId") if cb.get("casePackId") is not None else "none"
    )
    errors: list[str] = []
    expected = pack.case_pack_id
    if pack_id == "none":
        errors.append(
            f"case_binding_unbound: analyzer pack={expected} but run-meta casePackId=none"
        )
        return errors
    if pack_id != expected:
        errors.append(
            f"case_binding_mismatch: analyzer pack={expected} run-meta casePackId={pack_id}"
        )
    run_hex, run_errors = _validate_run_prompt_hex(cb.get("promptSha256"))
    errors.extend(run_errors)
    errors.extend(_validate_pack_prompt_hex(pack, run_hex))
    return errors


def _pack_eval_for_run(
    pack: CasePack | None,
    graph: Any,
    completeness: dict[str, Any],
    meta: dict[str, Any],
    binding_errors: list[str],
    final_text: str | None,
) -> PackEvalResult:
    if binding_errors:
        return PackEvalResult(
            status="ERROR",
            gates=[
                GateResult(
                    gate_id="CASE_BINDING",
                    gate_type="case_binding",
                    status="ERROR",
                    evidence={"errors": binding_errors},
                )
            ],
            requires_status="ERROR",
            reasons=list(binding_errors),
        )
    enabled = list(meta.get("enabledProbePacks") or [])
    return evaluate_pack(
        pack,
        graph,
        completeness_complete=bool(completeness.get("complete")),
        enabled_probe_packs=enabled,
        final_text=final_text,
        evidence_complete=bool(completeness.get("complete")) and graph.complete,
    )


def _incomplete_reasons(
    run_dir: Path,
    sources: list[str],
    completeness: dict[str, Any],
    graph: Any,
    binding_errors: list[str],
    vfs_reasons: list[str],
) -> list[str]:
    reasons = list(completeness.get("incompleteReasons") or [])
    if completeness.get("missingSideCar"):
        reasons.append("missing probe-completeness.json")
    if not completeness.get("complete"):
        reasons.append("completeness incomplete")
    if not (run_dir / "probes.jsonl").is_file() and sources:
        if "missing probes.jsonl" not in reasons:
            reasons.append("missing probes.jsonl")
    if not graph.complete:
        reasons.extend(graph.diagnostics)
    reasons.extend(binding_errors)
    reasons.extend(vfs_reasons)
    return reasons


def analyze_run(
    run_dir: Path,
    pack: CasePack | None = None,
    final_text: str | None = None,
) -> RunAnalysis:
    events, sources = load_run_events(run_dir)
    completeness = load_completeness(run_dir)
    meta = load_meta(run_dir)
    graph = build_tool_graph(events)
    binding_errors = validate_case_binding(meta, pack)
    pack_eval = _pack_eval_for_run(pack, graph, completeness, meta, binding_errors, final_text)

    vfs_facts = load_vfs_facts(run_dir)
    if vfs_facts.source:
        sources = list(sources) + [vfs_facts.source]
    reasons = _incomplete_reasons(
        run_dir, sources, completeness, graph, binding_errors, vfs_facts.incomplete_reasons
    )
    timing = compute_timing(events, meta)
    comparable = (
        bool(meta.get("comparable", False))
        and bool(completeness.get("complete"))
        and not completeness.get("missingSideCar")
    )
    compatibility = {
        "legacyDualRead": any(s.startswith("legacy:") for s in sources),
        "hasProbes": (run_dir / "probes.jsonl").is_file(),
        "hasCompletenessSideCar": (run_dir / "probe-completeness.json").is_file(),
        "hasVfsOps": (run_dir / "vfs-ops.jsonl").is_file(),
        "incomplete": bool(reasons) or not completeness.get("complete"),
    }
    return RunAnalysis(
        run_dir=str(run_dir),
        run_id=str(meta.get("runId") or run_dir.name),
        arm_id=str(meta.get("armId") or "default"),
        completeness=completeness,
        graph={
            "nodeCount": len(graph.nodes),
            "complete": graph.complete,
            "diagnostics": graph.diagnostics,
            "nodes": [node_to_dict(n) for n in graph.nodes],
        },
        pack_status=pack_eval.status,
        gates=[
            {"id": g.gate_id, "type": g.gate_type, "status": g.status, "evidence": g.evidence}
            for g in pack_eval.gates
        ],
        meta=meta,
        sources=sources,
        incomplete_reasons=reasons,
        comparable=comparable,
        timing=timing,
        vfs=vfs_facts.to_dict(),
        compatibility=compatibility,
    )



def node_to_dict(n: Any) -> dict[str, Any]:
    return {
        "toolCallId": n.tool_call_id,
        "toolName": n.tool_name,
        "source": n.source,
        "roundIndex": n.round_index,
        "appearanceSeq": n.appearance_seq,
        "completionSeq": n.completion_seq,
        "startSeq": n.start_seq,
        "endSeq": n.end_seq,
        "ok": n.ok,
        "status": n.status,
        "argsObject": n.args_object,
    }


def compare_metas(metas: list[dict[str, Any]]) -> list[str]:
    if not metas:
        return ["empty"]
    reasons: list[str] = []
    first = metas[0]
    vars0 = set(first.get("declaredVariables") or [])
    for m in metas:
        if not m.get("comparable", False):
            reasons.append(f"R1:{m.get('runId')}:not_comparable")
    for m in metas[1:]:
        if set(m.get("declaredVariables") or []) != vars0:
            reasons.append(f"R5:{m.get('runId')}:declaredVariables_mismatch")
        if m.get("controlFingerprint") != first.get("controlFingerprint"):
            reasons.append(f"R4:{m.get('runId')}:controlFingerprint_mismatch")
        cb0 = first.get("caseBinding") or {}
        cb1 = m.get("caseBinding") or {}
        if "casePackId" not in vars0 and "promptSha256" not in vars0:
            if cb0.get("casePackId") != cb1.get("casePackId") or cb0.get("promptSha256") != cb1.get(
                "promptSha256"
            ):
                reasons.append(f"R2:{m.get('runId')}:case_mismatch")
        iso0 = first.get("isolation") or {}
        iso1 = m.get("isolation") or {}
        for leaf in ("session", "memory", "cache"):
            vid = f"isolation.{leaf}"
            if "isolation" not in vars0 and vid not in vars0 and iso0.get(leaf) != iso1.get(leaf):
                reasons.append(f"R3:{m.get('runId')}:isolation.{leaf}")
        if "toolsetFingerprint" not in vars0 and m.get("toolsetFingerprint") != first.get(
            "toolsetFingerprint"
        ):
            reasons.append(f"R7:{m.get('runId')}:toolsetFingerprint")
        if "buildId" not in vars0 and m.get("buildId") != first.get("buildId"):
            reasons.append(f"R8:{m.get('runId')}:buildId")
    return sorted(set(reasons))


def analyze_paths(
    paths: list[Path],
    case_packs: list[Path] | None = None,
    *,
    require_gates: bool = False,
    require_comparable: bool = False,
    final_text: str | None = None,
    allow_any_case_root: bool = False,
) -> dict[str, Any]:
    packs: list[CasePack] = []
    pack_errors: list[str] = []
    for p in case_packs or []:
        try:
            packs.append(load_case_pack(p, allow_any_root=allow_any_case_root))
        except CasePackError as exc:
            pack_errors.append(f"{p}: {exc}")

    # P0: use first pack if multiple
    pack = packs[0] if packs else None
    runs = [analyze_run(d, pack=pack, final_text=final_text) for d in discover_run_dirs(paths)]

    by_arm: dict[str, list[RunAnalysis]] = {}
    for r in runs:
        by_arm.setdefault(r.arm_id, []).append(r)

    arm_reports: list[dict[str, Any]] = []
    for arm_id, arm_runs in sorted(by_arm.items()):
        metas = [r.meta for r in arm_runs]
        sep = compare_metas(metas) if len(metas) > 1 else []
        # also compare across arms later
        arm_reports.append(
            {
                "armId": arm_id,
                "runCount": len(arm_runs),
                "comparableWithinArm": not sep,
                "separationReasons": sep,
                "runs": [run_to_dict(r) for r in arm_runs],
            }
        )

    cross_arm_reasons: list[str] = []
    if len(by_arm) > 1:
        # one meta per arm (first run)
        cross_arm_reasons = compare_metas([arm_runs[0].meta for arm_runs in by_arm.values()])

    overall_pack = "SKIPPED_NO_CASE"
    for r in runs:
        overall_pack = _worst(overall_pack, r.pack_status)

    report = {
        "schemaVersion": 1,
        "runCount": len(runs),
        "arms": arm_reports,
        "crossArmSeparationReasons": cross_arm_reasons,
        "packErrors": pack_errors,
        "overallPackStatus": overall_pack,
        "businessPassInvented": False,
        "notes": [],
    }

    # never invent business PASS without case
    if pack is None:
        report["notes"].append("no case pack: generic timing/graph only; no business PASS")
        report["overallPackStatus"] = "SKIPPED_NO_CASE"

    # flag incomplete if any completeness incomplete
    if any(not r.completeness.get("complete") for r in runs):
        report["notes"].append("one or more runs INCOMPLETE completeness")

    if require_comparable and (cross_arm_reasons or any(a["separationReasons"] for a in arm_reports)):
        report["notes"].append("require-comparable failed")

    if require_gates and overall_pack in ("FAIL", "INCOMPLETE", "ERROR"):
        report["notes"].append(f"require-gates: {overall_pack}")

    return report


def run_to_dict(r: RunAnalysis) -> dict[str, Any]:
    return {
        "runDir": r.run_dir,
        "runId": r.run_id,
        "armId": r.arm_id,
        "completeness": r.completeness,
        "graph": r.graph,
        "packStatus": r.pack_status,
        "gates": r.gates,
        "meta": r.meta,
        "sources": r.sources,
        "incompleteReasons": r.incomplete_reasons,
        "comparable": r.comparable,
        "timing": r.timing,
        "vfs": r.vfs,
        "compatibility": r.compatibility,
    }


def _worst(a: str, b: str) -> str:
    order = {
        "ERROR": 5,
        "INCOMPLETE": 4,
        "FAIL": 3,
        "PASS": 2,
        "SKIPPED_NO_CASE": 1,
    }
    return a if order.get(a, 0) >= order.get(b, 0) else b


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Probe Bench Report",
        "",
        f"- runs: {report.get('runCount')}",
        f"- overall pack status: {report.get('overallPackStatus')}",
        f"- cross-arm separation: {report.get('crossArmSeparationReasons') or 'none'}",
        "",
    ]
    for note in report.get("notes") or []:
        lines.append(f"- note: {note}")
    lines.append("")
    for arm in report.get("arms") or []:
        lines.append(f"## Arm `{arm['armId']}` ({arm['runCount']} runs)")
        if arm.get("separationReasons"):
            lines.append(f"- SEPARATED: {arm['separationReasons']}")
        for run in arm.get("runs") or []:
            lines.append(f"### Run `{run['runId']}`")
            c = run.get("completeness") or {}
            lines.append(
                f"- completeness complete={c.get('complete')} enqueued={c.get('enqueued')} written={c.get('written')} dropped={c.get('dropped')}"
            )
            g = run.get("graph") or {}
            lines.append(f"- tool graph nodes={g.get('nodeCount')} complete={g.get('complete')}")
            lines.append(f"- pack status: {run.get('packStatus')}")
            lines.append(f"- sources: {', '.join(run.get('sources') or [])}")
            if run.get("gates"):
                lines.append("- gates:")
                for gate in run["gates"]:
                    lines.append(f"  - {gate['id']} ({gate['type']}): {gate['status']}")
            lines.append("")
    return "\n".join(lines)
