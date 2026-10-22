"""Legacy vs new evidence parity (SPEC-RPB-008 migration window).

Compares only fields both sides actually possess. Missing probe side-car on
legacy-only runs is not "parity PASS"; analyze_run already marks INCOMPLETE.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .graph import build_tool_graph
from .legacy_vfs import load_vfs_facts, vfs_common_metrics
from .report import load_completeness, load_run_events, compute_timing, load_meta


@dataclass
class ParityResult:
    ok: bool
    mismatches: list[str] = field(default_factory=list)
    compared: list[str] = field(default_factory=list)
    not_comparable: list[str] = field(default_factory=list)
    legacy_metrics: dict[str, Any] = field(default_factory=dict)
    new_metrics: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "mismatches": list(self.mismatches),
            "compared": list(self.compared),
            "notComparable": list(self.not_comparable),
            "legacy": self.legacy_metrics,
            "new": self.new_metrics,
        }


def _tool_metrics_from_events(events: list[dict[str, Any]]) -> dict[str, Any]:
    ends = [e for e in events if e.get("stageId") == "tool.execute.end"]
    starts = [e for e in events if e.get("stageId") == "tool.execute.start"]
    names: list[str] = []
    outcomes: list[str] = []
    call_ids: list[str] = []
    for e in ends:
        attrs = e.get("attrs") or {}
        names.append(str(attrs.get("toolName") or ""))
        ok = attrs.get("ok")
        if ok is True:
            outcomes.append("ok")
        elif ok is False:
            outcomes.append("failed")
        else:
            outcomes.append("unknown")
        call_ids.append(str(attrs.get("callId") or ""))
    return {
        "toolEndCount": len(ends),
        "toolStartCount": len(starts),
        "toolNameOrder": names,
        "toolOutcomeOrder": outcomes,
        "toolCallIds": call_ids,
        "toolGraphNodeCount": len(build_tool_graph(events).nodes),
    }


def extract_run_metrics(run_dir: Path) -> dict[str, Any]:
    events, sources = load_run_events(run_dir)
    completeness = load_completeness(run_dir)
    meta = load_meta(run_dir)
    timing = compute_timing(events, meta)
    vfs = load_vfs_facts(run_dir)
    tools = _tool_metrics_from_events(events)
    return {
        "sources": list(sources),
        "completenessComplete": bool(completeness.get("complete")),
        "missingProbeSideCar": bool(completeness.get("missingSideCar")),
        "eventCount": len(events),
        "wallMs": timing.get("wallMs"),
        "rounds": timing.get("rounds"),
        "tool": tools,
        "vfs": vfs_common_metrics(vfs),
        "vfsIncompleteReasons": list(vfs.incomplete_reasons),
        "vfsErrors": list(vfs.errors),
    }


def _both_present(a: Any, b: Any) -> bool:
    return a is not None and b is not None


def _compare_maps(
    result: ParityResult,
    prefix: str,
    left: dict[str, Any],
    right: dict[str, Any],
    keys: tuple[str, ...],
) -> None:
    for key in keys:
        result.compared.append(f"{prefix}.{key}")
        if left.get(key) != right.get(key):
            result.ok = False
            result.mismatches.append(
                f"{prefix}.{key}: legacy={left.get(key)!r} new={right.get(key)!r}"
            )


def _compare_tool_fields(result: ParityResult, legacy: dict[str, Any], new: dict[str, Any]) -> None:
    _compare_maps(
        result,
        "tool",
        legacy["tool"],
        new["tool"],
        ("toolEndCount", "toolNameOrder", "toolOutcomeOrder"),
    )


def _compare_vfs_fields(
    result: ParityResult,
    legacy_dir: Path,
    new_dir: Path,
    legacy: dict[str, Any],
    new: dict[str, Any],
) -> None:
    lv, nv = legacy["vfs"], new["vfs"]
    legacy_has = legacy_dir.joinpath("vfs-ops.jsonl").is_file()
    new_has = new_dir.joinpath("vfs-ops.jsonl").is_file()
    if legacy_has and new_has:
        _compare_maps(
            result,
            "vfs",
            lv,
            nv,
            ("vfsOpCount", "vfsOpOrder", "vfsOutcomeOrder"),
        )
        _compare_vfs_timing_sums(result, lv, nv)
        return
    if legacy_has and not new_has:
        result.not_comparable.append(
            "vfs: new run missing vfs-ops.jsonl (not dual-written); VFS not compared"
        )
    elif new_has and not legacy_has:
        result.not_comparable.append(
            "vfs: legacy run missing vfs-ops.jsonl; VFS not compared"
        )
    else:
        result.not_comparable.append("vfs: neither side has vfs-ops.jsonl")


def _compare_vfs_timing_sums(
    result: ParityResult,
    lv: dict[str, Any],
    nv: dict[str, Any],
) -> None:
    lt_sums = lv.get("vfsTimingSums") or {}
    nt_sums = nv.get("vfsTimingSums") or {}
    common_keys = sorted(set(lt_sums) & set(nt_sums))
    if not common_keys:
        result.not_comparable.append("vfs.timingSums: no shared timing keys on both sides")
        return
    result.compared.append("vfs.timingSums.common")
    for k in common_keys:
        if lt_sums.get(k) != nt_sums.get(k):
            result.ok = False
            result.mismatches.append(
                f"vfs.timingSums.{k}: legacy={lt_sums.get(k)} new={nt_sums.get(k)}"
            )


def _compare_wall_ms(result: ParityResult, legacy: dict[str, Any], new: dict[str, Any]) -> None:
    # Only same evidence class (both probes or both legacy).
    legacy_probes = "probes.jsonl" in (legacy.get("sources") or [])
    new_probes = "probes.jsonl" in (new.get("sources") or [])
    if legacy_probes == new_probes and _both_present(legacy.get("wallMs"), new.get("wallMs")):
        result.compared.append("timing.wallMs")
        if legacy.get("wallMs") != new.get("wallMs"):
            result.ok = False
            result.mismatches.append(
                f"timing.wallMs: legacy={legacy.get('wallMs')} new={new.get('wallMs')}"
            )
        return
    result.not_comparable.append(
        "timing.wallMs: not compared across mixed legacy/probes sources "
        f"(legacy_sources={legacy.get('sources')}, new_sources={new.get('sources')})"
    )


def _corruption_incomplete_reasons(reasons: list[str]) -> list[str]:
    """Incomplete reasons that mean evidence is damaged (not mere file absence)."""
    out: list[str] = []
    for r in reasons:
        if r == "missing vfs-ops.jsonl":
            continue
        out.append(r)
    return out


def _compare_vfs_parse_health(
    result: ParityResult,
    legacy: dict[str, Any],
    new: dict[str, Any],
) -> None:
    """Any parse error / corruption incomplete on either side blocks parity PASS.

    Identical malformed evidence on both sides is still FAIL (corrupt ≠ valid).
    """
    le = list(legacy.get("vfsErrors") or [])
    ne = list(new.get("vfsErrors") or [])
    li = _corruption_incomplete_reasons(list(legacy.get("vfsIncompleteReasons") or []))
    ni = _corruption_incomplete_reasons(list(new.get("vfsIncompleteReasons") or []))
    if not (le or ne or li or ni):
        return
    result.compared.append("vfs.parseHealth")
    result.ok = False
    result.mismatches.append(
        "vfs.parseHealth: corrupt or incomplete VFS evidence blocks parity PASS; "
        f"legacyErrors={le} newErrors={ne} "
        f"legacyIncomplete={li} newIncomplete={ni}"
    )


def compare_parity(legacy_dir: Path, new_dir: Path) -> ParityResult:
    """Mechanical parity on shared legacy/new fields."""
    legacy = extract_run_metrics(legacy_dir)
    new = extract_run_metrics(new_dir)
    result = ParityResult(ok=True, legacy_metrics=legacy, new_metrics=new)
    _compare_tool_fields(result, legacy, new)
    _compare_vfs_fields(result, legacy_dir, new_dir, legacy, new)
    _compare_wall_ms(result, legacy, new)
    _compare_vfs_parse_health(result, legacy, new)
    return result
