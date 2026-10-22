"""Matcher + 8 gate operators (SPEC-RPB-006 / B4 / B5)."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from .canonical import emit_exact_decimal, schema_canonical
from .case_pack import CasePack
from .graph import ToolGraph, ToolGraphNode

STATUS_ORDER = {
    "ERROR": 5,
    "INCOMPLETE": 4,
    "FAIL": 3,
    "PASS": 2,
    "SKIPPED_NO_CASE": 1,
}


@dataclass
class GateResult:
    gate_id: str
    gate_type: str
    status: str
    evidence: dict[str, Any] = field(default_factory=dict)


@dataclass
class PackEvalResult:
    status: str
    gates: list[GateResult]
    requires_status: str = "PASS"
    reasons: list[str] = field(default_factory=list)


def worst(a: str, b: str) -> str:
    return a if STATUS_ORDER.get(a, 0) >= STATUS_ORDER.get(b, 0) else b


def fullmatch_regex(pattern: str, text: str) -> bool:
    # fullmatch semantics even if anchors omitted
    if not pattern.startswith("^"):
        pattern = "^" + pattern
    if not pattern.endswith("$"):
        pattern = pattern + "$"
    return re.match(pattern, text) is not None


def match_nodes(graph: ToolGraph, pack: CasePack, matcher_id: str) -> list[ToolGraphNode]:
    mdef = pack.tool_matchers.get(matcher_id)
    if not mdef:
        return []
    out: list[ToolGraphNode] = []
    for node in graph.nodes:
        for clause in mdef.get("anyOf", []):
            if _clause_matches(node, clause):
                out.append(node)
                break
    return out


def _clause_matches(node: ToolGraphNode, clause: dict[str, Any]) -> bool:
    rx = clause.get("toolNameRegex")
    if rx is not None and not fullmatch_regex(str(rx), node.tool_name):
        return False
    sources = clause.get("sourceIn")
    if sources is not None and node.source not in sources:
        return False
    if "ok" in clause:
        if clause["ok"] is True and node.ok is not True:
            return False
        if clause["ok"] is False and node.ok is not False:
            return False
    return True


def evaluate_pack(
    pack: CasePack | None,
    graph: ToolGraph,
    *,
    completeness_complete: bool = True,
    enabled_probe_packs: list[str] | None = None,
    final_text: str | None = None,
    evidence_complete: bool | None = None,
) -> PackEvalResult:
    if pack is None:
        return PackEvalResult(status="SKIPPED_NO_CASE", gates=[], requires_status="SKIPPED_NO_CASE")

    if evidence_complete is None:
        evidence_complete = completeness_complete and graph.complete

    enabled = set(enabled_probe_packs or [])
    requires_status = "PASS"
    reasons: list[str] = []

    req = pack.requires or {}
    for pid in req.get("probePacks") or []:
        if pid not in enabled:
            requires_status = worst(requires_status, "INCOMPLETE")
            reasons.append(f"requires.probePacks missing {pid}")

    completed = [n for n in graph.nodes if n.end_seq is not None]
    n_tools = len(completed)
    if "minToolCalls" in req and n_tools < int(req["minToolCalls"]):
        requires_status = worst(requires_status, "FAIL" if evidence_complete else "INCOMPLETE")
        reasons.append("requires.minToolCalls")
    if "maxToolCalls" in req and n_tools > int(req["maxToolCalls"]):
        requires_status = worst(requires_status, "FAIL" if evidence_complete else "INCOMPLETE")
        reasons.append("requires.maxToolCalls")

    rounds = {n.round_index for n in graph.nodes if n.round_index is not None}
    n_rounds = len(rounds) if rounds else (1 if graph.nodes else 0)
    if "minRounds" in req and n_rounds < int(req["minRounds"]):
        requires_status = worst(requires_status, "FAIL" if evidence_complete else "INCOMPLETE")
    if "maxRounds" in req and n_rounds > int(req["maxRounds"]):
        requires_status = worst(requires_status, "FAIL" if evidence_complete else "INCOMPLETE")

    gate_results: list[GateResult] = []
    overall = requires_status
    for g in pack.gates:
        gr = _eval_gate(g, pack, graph, final_text=final_text, evidence_complete=evidence_complete)
        gate_results.append(gr)
        overall = worst(overall, gr.status)

    # Hard ban: incomplete / flush-failed / truncated evidence must never yield business PASS
    # (goal-contract §5). Elevate any PASS → INCOMPLETE when evidence is not complete.
    if not evidence_complete:
        reasons.append("evidence incomplete: cannot business PASS")
        elevated: list[GateResult] = []
        for gr in gate_results:
            if gr.status == "PASS":
                elevated.append(
                    GateResult(
                        gr.gate_id,
                        gr.gate_type,
                        "INCOMPLETE",
                        {**gr.evidence, "elevatedFrom": "PASS", "reason": "evidence incomplete"},
                    )
                )
            else:
                elevated.append(gr)
        gate_results = elevated
        if overall == "PASS":
            overall = "INCOMPLETE"
        overall = worst(overall, "INCOMPLETE")

    return PackEvalResult(
        status=overall,
        gates=gate_results,
        requires_status=requires_status,
        reasons=reasons,
    )


def _eval_gate(
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    *,
    final_text: str | None,
    evidence_complete: bool,
) -> GateResult:
    gtype = gate["type"]
    gid = str(gate.get("id") or gtype)
    try:
        if gtype == "exists":
            return _exists(gid, gate, pack, graph, evidence_complete)
        if gtype == "order":
            return _order(gid, gate, pack, graph, evidence_complete)
        if gtype == "forbids_before":
            return _forbids_before(gid, gate, pack, graph, evidence_complete)
        if gtype == "forbid":
            return _forbid(gid, gate, pack, graph, evidence_complete)
        if gtype == "args_match":
            return _args_match(gid, gate, pack, graph, evidence_complete)
        if gtype == "round_width":
            return _round_width(gid, gate, graph, evidence_complete)
        if gtype == "count_ratio":
            return _count_ratio(gid, gate, pack, graph, evidence_complete)
        if gtype == "final_response":
            return _final_response(gid, gate, final_text, evidence_complete)
        return GateResult(gid, gtype, "ERROR", {"reason": "unknown type"})
    except Exception as exc:  # noqa: BLE001 — gate ERROR isolation
        return GateResult(gid, gtype, "ERROR", {"reason": str(exc)})


def _success_nodes(nodes: list[ToolGraphNode], count_failed: bool = False) -> list[ToolGraphNode]:
    if count_failed:
        return [n for n in nodes if n.appearance_seq is not None]
    return [n for n in nodes if n.completion_seq is not None]


def _exists(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    matched = match_nodes(graph, pack, gate["matcher"])
    counted = _success_nodes(matched, count_failed=bool(gate.get("countFailed")))
    n = len(counted)
    min_c = int(gate.get("minCount", 1))
    max_c = gate.get("maxCount")
    if not evidence_complete and n < min_c:
        return GateResult(gid, "exists", "INCOMPLETE", {"count": n, "minCount": min_c})
    if n < min_c:
        return GateResult(gid, "exists", "FAIL", {"count": n, "minCount": min_c})
    if max_c is not None and n > int(max_c):
        return GateResult(gid, "exists", "FAIL", {"count": n, "maxCount": max_c})
    return GateResult(gid, "exists", "PASS", {"count": n})


def _order(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    before_spec = gate.get("before") or {}
    after_spec = gate.get("after") or {}
    before_req_ok = before_spec.get("requireOk", True)
    after_req_ok = after_spec.get("requireOk", False)
    before_nodes = match_nodes(graph, pack, before_spec["matcher"])
    after_nodes = match_nodes(graph, pack, after_spec["matcher"])

    if before_req_ok:
        before_comp = [n for n in before_nodes if n.completion_seq is not None]
        before_key = lambda n: n.completion_seq  # noqa: E731
    else:
        before_comp = [n for n in before_nodes if n.end_seq is not None]
        before_key = lambda n: n.end_seq  # noqa: E731

    if not before_comp:
        st = "FAIL" if evidence_complete else "INCOMPLETE"
        return GateResult(gid, "order", st, {"reason": "no before completion"})

    before_complete_seq = min(before_key(n) for n in before_comp)

    if after_req_ok:
        after_cand = [n for n in after_nodes if n.completion_seq is not None]
    else:
        after_cand = [n for n in after_nodes if n.appearance_seq is not None]
    if not after_cand:
        st = "FAIL" if evidence_complete else "INCOMPLETE"
        return GateResult(gid, "order", st, {"reason": "no after appearance"})

    after_first = min(n.appearance_seq for n in after_cand if n.appearance_seq is not None)
    status = "PASS" if after_first >= before_complete_seq else "FAIL"
    return GateResult(
        gid,
        "order",
        status,
        {
            "beforeCompleteSeq": before_complete_seq,
            "afterAppearanceSeq": after_first,
        },
    )


def _forbids_before(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    before_spec = gate.get("before") or {}
    after_spec = gate.get("after") or {}
    after_nodes = match_nodes(graph, pack, after_spec["matcher"])
    after_comp = [n for n in after_nodes if n.completion_seq is not None]
    if not after_comp:
        return GateResult(
            gid,
            "forbids_before",
            "INCOMPLETE" if not evidence_complete else "INCOMPLETE",
            {"reason": "no after completion"},
        )
    after_seq = min(n.completion_seq for n in after_comp if n.completion_seq is not None)
    before_nodes = match_nodes(graph, pack, before_spec["matcher"])
    before_app = [n.appearance_seq for n in before_nodes if n.appearance_seq is not None]
    if not before_app:
        return GateResult(gid, "forbids_before", "PASS", {"beforeAppearances": 0})
    if min(before_app) < after_seq:
        return GateResult(
            gid,
            "forbids_before",
            "FAIL",
            {"minBeforeAppearance": min(before_app), "afterCompleteSeq": after_seq},
        )
    return GateResult(gid, "forbids_before", "PASS", {})


def _forbid(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    matched = match_nodes(graph, pack, gate["matcher"])
    n = len([n for n in matched if n.appearance_seq is not None])
    if n > 0:
        return GateResult(gid, "forbid", "FAIL", {"count": n})
    # Zero hits only prove absence when evidence is complete; otherwise INCOMPLETE.
    if not evidence_complete:
        return GateResult(
            gid,
            "forbid",
            "INCOMPLETE",
            {"count": 0, "reason": "cannot prove absence without complete evidence"},
        )
    return GateResult(gid, "forbid", "PASS", {"count": n})


def _jsonpath(obj: Any, path: str) -> tuple[str, Any]:
    """Return (ABSENT|PRESENT|UNAVAILABLE, value). Only $.a.b[0] subset."""
    if obj is None:
        return "UNAVAILABLE", None
    if not path.startswith("$"):
        raise ValueError("path must start with $")
    cur: Any = obj
    i = 1
    while i < len(path):
        if path[i] == ".":
            i += 1
            start = i
            while i < len(path) and path[i] not in ".[":
                i += 1
            key = path[start:i]
            if not isinstance(cur, dict) or key not in cur:
                return "ABSENT", None
            cur = cur[key]
        elif path[i] == "[":
            j = path.index("]", i)
            idx = int(path[i + 1 : j])
            if not isinstance(cur, list) or idx < 0 or idx >= len(cur):
                return "ABSENT", None
            cur = cur[idx]
            i = j + 1
        else:
            raise ValueError(f"bad path at {path[i:]}")
    return "PRESENT", cur


def _node_args_contrib(node: ToolGraphNode, path: str, op: str, expected: Any = None) -> str:
    if node.args_object is None:
        return "UNKNOWN"
    state, value = _jsonpath(node.args_object, path)
    if state == "ABSENT":
        return "NO"
    if state == "UNAVAILABLE":
        return "UNKNOWN"
    # PRESENT
    if op == "exists":
        return "YES"
    if op == "eq":
        return "YES" if _values_eq(value, expected) else "NO"
    if op == "contains":
        if isinstance(value, str) and expected is not None and str(expected) in value:
            return "YES"
        return "NO"
    raise ValueError(f"unknown op {op}")


def _values_eq(a: Any, b: Any) -> bool:
    if type(a) is not type(b) and not (isinstance(a, (int, Decimal)) and isinstance(b, (int, Decimal))):
        # allow int/Decimal compare via canonical
        pass
    if isinstance(a, (int, Decimal)) or isinstance(b, (int, Decimal)):
        try:
            da = a if isinstance(a, Decimal) else Decimal(str(a))
            db = b if isinstance(b, Decimal) else Decimal(str(b))
            return emit_exact_decimal(da) == emit_exact_decimal(db)
        except Exception:  # noqa: BLE001
            return False
    if isinstance(a, dict) and isinstance(b, dict):
        return schema_canonical(a) == schema_canonical(b)
    if isinstance(a, list) and isinstance(b, list):
        return schema_canonical(a) == schema_canonical(b)
    return a == b


def _args_match(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    matched = match_nodes(graph, pack, gate["matcher"])
    # default exclude failed
    candidates = [n for n in matched if n.ok is not False]
    path = gate.get("path") or "$"
    op = gate.get("op") or "exists"
    mode = gate.get("mode") or "any"
    expected = gate.get("value")
    contribs = [_node_args_contrib(n, path, op, expected) for n in candidates]
    status = _merge_any_all(contribs, mode, evidence_complete=evidence_complete)
    return GateResult(gid, "args_match", status, {"contribs": contribs, "mode": mode})


def _merge_any_all(contribs: list[str], mode: str, *, evidence_complete: bool) -> str:
    if mode == "all":
        if not contribs:
            return "PASS"  # vacuous
        if any(c == "NO" for c in contribs):
            return "FAIL"
        if any(c == "UNKNOWN" for c in contribs):
            return "INCOMPLETE"
        return "PASS"
    # any (default)
    if any(c == "YES" for c in contribs):
        return "PASS"
    if any(c == "UNKNOWN" for c in contribs):
        return "INCOMPLETE"
    if not contribs:
        return "FAIL" if evidence_complete else "INCOMPLETE"
    return "FAIL"  # all NO


def _round_width(
    gid: str,
    gate: dict[str, Any],
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    by_round: dict[int, int] = {}
    missing = False
    for n in graph.nodes:
        if n.round_index is None:
            missing = True
            continue
        if n.end_seq is None:
            continue
        by_round[n.round_index] = by_round.get(n.round_index, 0) + 1
    if missing and not by_round:
        return GateResult(gid, "round_width", "INCOMPLETE" if not evidence_complete else "INCOMPLETE", {})
    width = max(by_round.values()) if by_round else 0
    mn = gate.get("min")
    mx = gate.get("max")
    if mn is not None and width < int(mn):
        return GateResult(gid, "round_width", "FAIL", {"width": width})
    if mx is not None and width > int(mx):
        return GateResult(gid, "round_width", "FAIL", {"width": width})
    return GateResult(gid, "round_width", "PASS", {"width": width})


def _count_ratio(
    gid: str,
    gate: dict[str, Any],
    pack: CasePack,
    graph: ToolGraph,
    evidence_complete: bool,
) -> GateResult:
    num_nodes = _success_nodes(match_nodes(graph, pack, gate["numeratorMatcher"]))
    denom_spec = gate.get("denominator", "tool_calls")
    if denom_spec == "tool_calls":
        denom = len([n for n in graph.nodes if n.end_seq is not None])
    else:
        denom = len(_success_nodes(match_nodes(graph, pack, str(denom_spec))))
    if denom == 0:
        return GateResult(gid, "count_ratio", "INCOMPLETE", {"denom": 0})
    ratio = len(num_nodes) / denom
    if "min" in gate and ratio < float(gate["min"]):
        return GateResult(gid, "count_ratio", "FAIL", {"ratio": ratio})
    if "max" in gate and ratio > float(gate["max"]):
        return GateResult(gid, "count_ratio", "FAIL", {"ratio": ratio})
    return GateResult(gid, "count_ratio", "PASS", {"ratio": ratio})


def _final_response(
    gid: str,
    gate: dict[str, Any],
    final_text: str | None,
    evidence_complete: bool,
) -> GateResult:
    if final_text is None:
        return GateResult(gid, "final_response", "INCOMPLETE", {"reason": "no text"})
    if not evidence_complete:
        return GateResult(
            gid,
            "final_response",
            "INCOMPLETE",
            {"reason": "evidence incomplete"},
        )
    op = gate.get("op") or "contains"
    value = str(gate.get("value") or "")
    if op == "contains":
        ok = value in final_text
        return GateResult(gid, "final_response", "PASS" if ok else "FAIL", {})
    if op == "regex":
        pattern = value
        ok = re.search(pattern, final_text) is not None
        return GateResult(gid, "final_response", "PASS" if ok else "FAIL", {})
    return GateResult(gid, "final_response", "ERROR", {"reason": f"bad op {op}"})


# Expose for unit tests
merge_any_all = _merge_any_all
