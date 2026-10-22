"""Tool Graph projection from probe events (SPEC-RPB-004 / B5)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ToolGraphNode:
    tool_call_id: str
    tool_name: str
    source: str = "BACKEND"
    round_index: int | None = None
    batch_id: str | None = None
    prepare_seq: int | None = None
    start_seq: int | None = None
    end_seq: int | None = None
    appearance_seq: int | None = None
    completion_seq: int | None = None
    start_elapsed_nanos: int | None = None
    end_elapsed_nanos: int | None = None
    duration_nanos: int | None = None
    ok: bool | None = None
    code: str | None = None
    args_digest: str | None = None
    args_object: dict[str, Any] | None = None
    status: str = "complete"
    diagnostics: list[str] = field(default_factory=list)


@dataclass
class ToolGraph:
    nodes: list[ToolGraphNode]
    diagnostics: list[str]
    complete: bool


def _apply_event(
    node: ToolGraphNode,
    stage: str,
    attrs: dict[str, Any],
    seq: int,
    elapsed: Any,
) -> None:
    tool_name = attrs.get("toolName") or ""
    if tool_name:
        node.tool_name = str(tool_name)
    if "source" in attrs:
        node.source = str(attrs["source"])
    if "roundIndex" in attrs:
        node.round_index = int(attrs["roundIndex"])
    if stage == "tool.prepare":
        node.prepare_seq = seq
    elif stage in ("tool.execute.start", "tool.start"):
        node.start_seq = seq
        if elapsed is not None:
            node.start_elapsed_nanos = int(elapsed)
    elif stage in ("tool.execute.end", "tool.end"):
        node.end_seq = seq
        if elapsed is not None:
            node.end_elapsed_nanos = int(elapsed)
        if "ok" in attrs:
            node.ok = bool(attrs["ok"])
        if "code" in attrs:
            node.code = str(attrs["code"])
    if "args" in attrs and isinstance(attrs["args"], dict):
        node.args_object = attrs["args"]
    if "argsDigest" in attrs:
        node.args_digest = str(attrs["argsDigest"])


def _finalize_node(node: ToolGraphNode, diagnostics: list[str]) -> None:
    seqs = [s for s in (node.prepare_seq, node.start_seq, node.end_seq) if s is not None]
    if not seqs:
        node.status = "orphan_end"
        diagnostics.append(f"invalid:{node.tool_call_id}")
    else:
        node.appearance_seq = min(seqs)
    if node.end_seq is not None and node.ok is True:
        node.completion_seq = node.end_seq
    if node.start_elapsed_nanos is not None and node.end_elapsed_nanos is not None:
        node.duration_nanos = max(0, node.end_elapsed_nanos - node.start_elapsed_nanos)
    if node.start_seq is not None and node.end_seq is None:
        node.status = "missing_terminal"
        diagnostics.append(f"missing_terminal:{node.tool_call_id}")
    elif node.end_seq is not None and node.start_seq is None and node.prepare_seq is None:
        node.status = "orphan_end"
        diagnostics.append(f"orphan_end:{node.tool_call_id}")
    elif node.status == "complete" and node.end_seq is None:
        node.status = "missing_terminal"


def build_tool_graph(events: list[dict[str, Any]]) -> ToolGraph:
    """Build graph from probes.jsonl-like event dicts."""
    by_id: dict[str, ToolGraphNode] = {}
    diagnostics: list[str] = []

    for ev in events:
        attrs = ev.get("attrs") or {}
        call_id = attrs.get("callId") or attrs.get("toolCallId")
        if not call_id:
            continue
        node = by_id.get(call_id)
        if node is None:
            node = ToolGraphNode(
                tool_call_id=str(call_id),
                tool_name=str(attrs.get("toolName") or ""),
            )
            by_id[call_id] = node
        _apply_event(
            node,
            ev.get("stageId") or "",
            attrs,
            int(ev.get("sequence", -1)),
            ev.get("elapsedNanos"),
        )

    nodes: list[ToolGraphNode] = []
    for node in by_id.values():
        _finalize_node(node, diagnostics)
        nodes.append(node)

    nodes.sort(key=lambda n: (n.appearance_seq if n.appearance_seq is not None else 10**18, n.tool_call_id))
    complete = not diagnostics
    return ToolGraph(nodes=nodes, diagnostics=diagnostics, complete=complete)
