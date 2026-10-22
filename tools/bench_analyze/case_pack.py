"""Case Pack v1 loader with path safety (SPEC-RPB-005 / B4 / B6.3)."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

MAX_PACK_BYTES = 1 * 1024 * 1024
MAX_REGEX_LEN = 256
ALLOWED_GATE_TYPES = {
    "exists",
    "order",
    "forbids_before",
    "forbid",
    "args_match",
    "round_width",
    "count_ratio",
    "final_response",
}
ALLOWED_REQUIRES_KEYS = {
    "minToolCalls",
    "maxToolCalls",
    "probePacks",
    "minRounds",
    "maxRounds",
}
# limited regex: reject named groups / lookaround / recursion-ish
_BANNED_REGEX = re.compile(r"\(\?P<|>\?|\\g|\\1|\(\?\=|\(\?\!|\(\?\<")


@dataclass
class CasePack:
    schema_version: int
    case_pack_id: str
    normative: bool
    tool_matchers: dict[str, Any]
    gates: list[dict[str, Any]]
    prompt: dict[str, Any] | None = None
    requires: dict[str, Any] = field(default_factory=dict)
    final_response: dict[str, Any] | None = None
    source_path: str | None = None


class CasePackError(Exception):
    pass


def default_allowed_roots(repo_root: Path | None = None) -> list[Path]:
    roots: list[Path] = []
    if repo_root is None:
        # tools/bench_analyze -> tools -> repo
        here = Path(__file__).resolve()
        repo_root = here.parents[2]
    candidates = [
        repo_root / "tools" / "fixtures" / "bench-cases",
        repo_root / "agent-runtime" / "src" / "test" / "resources" / "fixtures" / "bench-cases",
    ]
    for c in candidates:
        if c.exists():
            roots.append(c.resolve())
    # always allow the fixtures dir even if missing (tests create under temp with override)
    return roots


def resolve_under_roots(path: Path, allowed_roots: list[Path]) -> Path:
    try:
        real = path.resolve()
    except OSError as exc:
        raise CasePackError(f"path resolve failed: {path}") from exc
    for root in allowed_roots:
        try:
            root_real = root.resolve()
            real.relative_to(root_real)
            return real
        except ValueError:
            continue
    raise CasePackError(f"path escapes allowed roots: {path}")


def load_case_pack(
    path: Path,
    allowed_roots: list[Path] | None = None,
    *,
    allow_any_root: bool = False,
) -> CasePack:
    if allowed_roots is None:
        allowed_roots = default_allowed_roots()
    if not allow_any_root:
        path = resolve_under_roots(path, allowed_roots)
    else:
        path = path.resolve()
    if not path.is_file():
        raise CasePackError(f"not a file: {path}")
    size = path.stat().st_size
    if size > MAX_PACK_BYTES:
        raise CasePackError(f"pack too large: {size}")
    text = path.read_text(encoding="utf-8")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise CasePackError(f"invalid JSON: {exc}") from exc
    return parse_case_pack(data, source_path=str(path))


def _check_pack_header(data: dict[str, Any]) -> str:
    if not isinstance(data, dict):
        raise CasePackError("pack must be object")
    known_top = {
        "schemaVersion",
        "casePackId",
        "normative",
        "toolMatchers",
        "gates",
        "prompt",
        "requires",
        "finalResponse",
    }
    unknown = set(data.keys()) - known_top
    if unknown:
        raise CasePackError(f"unknown top-level fields: {sorted(unknown)}")
    if data.get("schemaVersion") != 1:
        raise CasePackError("schemaVersion must be 1")
    case_id = data.get("casePackId")
    if not isinstance(case_id, str) or not case_id:
        raise CasePackError("casePackId required")
    if not isinstance(data.get("normative"), bool):
        raise CasePackError("normative must be bool")
    return case_id


def _validate_matchers(matchers: Any) -> None:
    if not isinstance(matchers, dict):
        raise CasePackError("toolMatchers required object")
    for mid, mdef in matchers.items():
        if not isinstance(mdef, dict) or not isinstance(mdef.get("anyOf"), list) or not mdef["anyOf"]:
            raise CasePackError(f"matcher {mid} anyOf must be non-empty array")
        for clause in mdef["anyOf"]:
            if not isinstance(clause, dict):
                raise CasePackError(f"matcher {mid} clause must be object")
            rx = clause.get("toolNameRegex")
            if rx is not None:
                _validate_regex(rx)


def _validate_gates(gates: Any, matchers: dict[str, Any]) -> None:
    if not isinstance(gates, list):
        raise CasePackError("gates must be array")
    for g in gates:
        if not isinstance(g, dict):
            raise CasePackError("gate must be object")
        gtype = g.get("type")
        if gtype not in ALLOWED_GATE_TYPES:
            raise CasePackError(f"unknown gate type: {gtype}")
        for ref_key in ("matcher", "numeratorMatcher"):
            if ref_key in g and g[ref_key] not in matchers:
                raise CasePackError(f"gate {g.get('id')} refs missing matcher {g[ref_key]}")
        for side in ("before", "after"):
            if side in g and isinstance(g[side], dict):
                mid = g[side].get("matcher")
                if mid and mid not in matchers:
                    raise CasePackError(f"gate {g.get('id')} {side} missing matcher {mid}")


def _validate_requires(requires: Any) -> None:
    if not isinstance(requires, dict):
        raise CasePackError("requires must be object")
    bad_req = set(requires.keys()) - ALLOWED_REQUIRES_KEYS
    if bad_req:
        raise CasePackError(f"unknown requires fields: {sorted(bad_req)}")


def _normalize_prompt(prompt: Any) -> dict[str, Any] | None:
    if prompt is None:
        return None
    if not isinstance(prompt, dict):
        raise CasePackError("prompt must be object")
    if "sha256" in prompt:
        sha = prompt["sha256"]
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha.lower()):
            raise CasePackError("prompt.sha256 must be 64 hex")
        prompt = {**prompt, "sha256": sha.lower()}
    if "text" in prompt:
        import hashlib

        computed = hashlib.sha256(str(prompt["text"]).encode("utf-8")).hexdigest()
        if "sha256" in prompt and prompt["sha256"] != computed:
            raise CasePackError("prompt.sha256 mismatch")
        prompt = {**prompt, "sha256": computed}
    return prompt


def parse_case_pack(data: dict[str, Any], source_path: str | None = None) -> CasePack:
    case_id = _check_pack_header(data)
    matchers = data.get("toolMatchers")
    _validate_matchers(matchers)
    gates = data.get("gates")
    if gates is None:
        gates = []
    _validate_gates(gates, matchers)
    requires = data.get("requires") or {}
    _validate_requires(requires)
    prompt = _normalize_prompt(data.get("prompt"))
    return CasePack(
        schema_version=1,
        case_pack_id=case_id,
        normative=bool(data["normative"]),
        tool_matchers=matchers,
        gates=gates,
        prompt=prompt,
        requires=requires,
        final_response=data.get("finalResponse"),
        source_path=source_path,
    )


def _validate_regex(pattern: str) -> None:
    if not isinstance(pattern, str) or len(pattern) > MAX_REGEX_LEN:
        raise CasePackError("toolNameRegex invalid length")
    if _BANNED_REGEX.search(pattern):
        raise CasePackError("toolNameRegex uses forbidden features")
    try:
        re.compile(pattern)
    except re.error as exc:
        raise CasePackError(f"toolNameRegex compile failed: {exc}") from exc
