"""Legacy VFS evidence dual-read (SPEC-RPB-008).

Maps historical `vfs-ops.jsonl` (phase3 / session VFS recorder shape) into a
normalized fact block for the analyzer. Missing probes/completeness still force
INCOMPLETE on the run; this module never fabricates full PASS.

Fail-closed rules:
- unknown non-empty status → outcome unknown + error/incomplete reason
- invalid timing types → error/incomplete reason (no silent drop)
- non-boolean schemaCacheHit → null + incomplete reason (no bool() cast)
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

TIMING_KEYS = (
    "executeMs",
    "materializeMs",
    "scanMs",
    "resolveMs",
    "serializeMs",
    "prepareMs",
)
KNOWN_SCHEMA_VERSIONS = {None, 1, "1"}
OK_STATUSES = frozenset({"OK", "SUCCESS", "COMPLETED", "PASS"})
FAILED_STATUSES = frozenset({"FAILED", "ERROR", "FAIL", "IO_ERROR"})


@dataclass
class NormalizedVfsOp:
    operation_id: str
    op: str
    path: str
    status: str
    outcome: str
    epoch_millis: int | None
    timings: dict[str, int] = field(default_factory=dict)
    schema_cache_hit: bool | None = None
    source_index: int = 0


@dataclass
class VfsFacts:
    ops: list[NormalizedVfsOp] = field(default_factory=list)
    source: str | None = None
    incomplete_reasons: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    op_count: int = 0
    ok_count: int = 0
    failed_count: int = 0
    timing_sums: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "opCount": self.op_count,
            "okCount": self.ok_count,
            "failedCount": self.failed_count,
            "timingSums": dict(self.timing_sums),
            "ops": [
                {
                    "operationId": o.operation_id,
                    "op": o.op,
                    "path": o.path,
                    "status": o.status,
                    "outcome": o.outcome,
                    "epochMillis": o.epoch_millis,
                    "timings": dict(o.timings),
                    "schemaCacheHit": o.schema_cache_hit,
                    "sourceIndex": o.source_index,
                }
                for o in self.ops
            ],
            "incompleteReasons": list(self.incomplete_reasons),
            "errors": list(self.errors),
        }


def _classify_status(status: str, *, line_no: int, facts: VfsFacts) -> tuple[str, str]:
    """Return (status_text, outcome). Non-empty unknown → fail-closed reason."""
    raw = status if isinstance(status, str) else str(status or "")
    stripped = raw.strip()
    if not stripped:
        return "UNKNOWN", "unknown"
    upper = stripped.upper()
    if upper in OK_STATUSES:
        return stripped, "ok"
    if upper in FAILED_STATUSES:
        return stripped, "failed"
    facts.errors.append(f"vfs_ops_unknown_status:{line_no}:{stripped!r}")
    facts.incomplete_reasons.append(f"unknown vfs status={stripped!r} at line {line_no}")
    return stripped, "unknown"


def _int_or_none(raw: Any) -> int | None:
    if raw is None:
        return None
    if isinstance(raw, bool) or not isinstance(raw, int):
        return None
    return raw


def _normalize_timings(
    raw: Any,
    *,
    line_no: int,
    facts: VfsFacts,
) -> dict[str, int]:
    """Accept only JSON integers (Python int, not bool/float/str). Fail-closed otherwise."""
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        facts.errors.append(f"vfs_ops_invalid_timings_type:{line_no}")
        facts.incomplete_reasons.append(f"invalid vfs timings type at line {line_no}")
        return {}
    out: dict[str, int] = {}
    for key in TIMING_KEYS:
        if key not in raw or raw[key] is None:
            continue
        val = raw[key]
        # bool is a subclass of int — reject explicitly; reject float/str/etc.
        if isinstance(val, bool) or not isinstance(val, int):
            facts.errors.append(f"vfs_ops_invalid_timing:{line_no}:{key}")
            facts.incomplete_reasons.append(
                f"invalid vfs timing {key} at line {line_no} (value={val!r})"
            )
            continue
        out[key] = val
    return out


def _schema_cache_hit(
    raw: Any,
    *,
    line_no: int,
    facts: VfsFacts,
) -> bool | None:
    if raw is None:
        return None
    if isinstance(raw, bool):
        return raw
    facts.errors.append(f"vfs_ops_invalid_schemaCacheHit:{line_no}")
    facts.incomplete_reasons.append(
        f"invalid schemaCacheHit at line {line_no} (value={raw!r})"
    )
    return None


def _finalize_facts(facts: VfsFacts) -> VfsFacts:
    facts.op_count = len(facts.ops)
    facts.ok_count = sum(1 for o in facts.ops if o.outcome == "ok")
    facts.failed_count = sum(1 for o in facts.ops if o.outcome == "failed")
    sums = {k: 0 for k in TIMING_KEYS}
    for o in facts.ops:
        for k, v in o.timings.items():
            sums[k] = sums.get(k, 0) + v
    facts.timing_sums = {k: v for k, v in sums.items() if v}
    if facts.errors and not facts.ops:
        facts.incomplete_reasons.append("vfs-ops unreadable")
    return facts


def _parse_one_vfs_line(
    raw: dict[str, Any],
    *,
    line_no: int,
    index: int,
    seen_ids: set[str],
    facts: VfsFacts,
) -> NormalizedVfsOp | None:
    schema_v = raw.get("schemaVersion", raw.get("schema_version"))
    if schema_v not in KNOWN_SCHEMA_VERSIONS:
        facts.errors.append(f"vfs_ops_unknown_version:{line_no}:{schema_v!r}")
        facts.incomplete_reasons.append(
            f"unknown vfs-ops schemaVersion={schema_v!r} at line {line_no}"
        )
        return None

    op_name = str(raw.get("op") or raw.get("operation") or raw.get("toolName") or "")
    path_s = str(raw.get("path") or raw.get("vfsPath") or "")
    status_raw = raw.get("status")
    status_text = "" if status_raw is None else str(status_raw)
    status, outcome = _classify_status(status_text, line_no=line_no, facts=facts)
    op_id = str(
        raw.get("operationId") or raw.get("opId") or raw.get("id") or f"vfs-anon-{index}"
    )
    if op_id in seen_ids:
        facts.errors.append(f"vfs_ops_duplicate_operation_id:{op_id}")
        facts.incomplete_reasons.append(f"duplicate vfs operationId={op_id}")
        return None
    seen_ids.add(op_id)

    if not op_name:
        facts.incomplete_reasons.append(f"vfs op missing name at line {line_no}")
    timings = _normalize_timings(raw.get("timings"), line_no=line_no, facts=facts)
    cache_hit = _schema_cache_hit(raw.get("schemaCacheHit"), line_no=line_no, facts=facts)
    return NormalizedVfsOp(
        operation_id=op_id,
        op=op_name or "unknown",
        path=path_s,
        status=status,
        outcome=outcome,
        epoch_millis=_int_or_none(
            raw.get("createdAtEpochMillis") or raw.get("epochMillis") or raw.get("at")
        ),
        timings=timings,
        schema_cache_hit=cache_hit,
        source_index=index,
    )


def parse_vfs_ops_jsonl(path: Path) -> VfsFacts:
    """Parse vfs-ops.jsonl with fail-closed diagnostics (no silent full drop)."""
    facts = VfsFacts()
    if not path.is_file():
        facts.incomplete_reasons.append("missing vfs-ops.jsonl")
        return facts

    facts.source = "legacy:vfs-ops.jsonl"
    seen_ids: set[str] = set()
    index = 0
    for line_no, line in enumerate(
        path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
    ):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            raw = json.loads(stripped)
        except json.JSONDecodeError:
            facts.errors.append(f"vfs_ops_malformed_line:{line_no}")
            facts.incomplete_reasons.append(f"malformed vfs-ops line {line_no}")
            continue
        if not isinstance(raw, dict):
            facts.errors.append(f"vfs_ops_non_object_line:{line_no}")
            facts.incomplete_reasons.append(f"non-object vfs-ops line {line_no}")
            continue
        op = _parse_one_vfs_line(
            raw, line_no=line_no, index=index, seen_ids=seen_ids, facts=facts
        )
        if op is None:
            continue
        facts.ops.append(op)
        index += 1
    return _finalize_facts(facts)


def load_vfs_facts(run_dir: Path) -> VfsFacts:
    return parse_vfs_ops_jsonl(run_dir / "vfs-ops.jsonl")


def vfs_common_metrics(facts: VfsFacts) -> dict[str, Any]:
    """Comparable projection used by legacy/new parity."""
    return {
        "vfsOpCount": facts.op_count,
        "vfsOkCount": facts.ok_count,
        "vfsFailedCount": facts.failed_count,
        "vfsOpOrder": [o.op for o in facts.ops],
        "vfsOutcomeOrder": [o.outcome for o in facts.ops],
        "vfsOperationIds": [o.operation_id for o in facts.ops],
        "vfsTimingSums": dict(facts.timing_sums),
    }
