"""ConfigCanonical + SchemaCanonical (B6) — exact decimal only, no float."""

from __future__ import annotations

import hashlib
import json
import re
from decimal import Decimal, InvalidOperation
from typing import Any


_NUMBER_TOKEN = re.compile(
    r"-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?"
)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def parse_exact_decimal_token(token: str) -> Decimal:
    if not token or re.search(r"[iInN]", token):
        raise ValueError(f"invalid number token: {token!r}")
    try:
        return Decimal(token)
    except InvalidOperation as exc:
        raise ValueError(f"invalid number token: {token!r}") from exc


def emit_exact_decimal(d: Decimal) -> str:
    """B6.2b step 3: plain decimal, no exponent, no trailing frac zeros."""
    # Normalize via quantize-free path: use Decimal normalization
    n = d.normalize()
    # Expand exponent into plain form
    s = format(n, "f")
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    # format(n,'f') for integer-ish may still have .0 stripped above
    if s.startswith("."):
        s = "0" + s
    if s.startswith("-."):
        s = "-0" + s[1:]
    return s


def config_canonical(value: Any) -> str:
    """ConfigCanonical: sorted keys, no floats."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, Decimal):
        if value != value.to_integral_value():
            raise ValueError("ConfigCanonical forbids non-integer numbers")
        return emit_exact_decimal(value)
    if isinstance(value, float):
        raise ValueError("ConfigCanonical forbids floating point")
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=lambda k: k.encode("utf-8"))
        parts = [f"{json.dumps(k, ensure_ascii=False, separators=(',', ':'))}:{config_canonical(value[k])}" for k in keys]
        return "{" + ",".join(parts) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(config_canonical(v) for v in value) + "]"
    raise TypeError(f"unsupported ConfigCanonical type: {type(value)}")


def schema_canonical(value: Any) -> str:
    """SchemaCanonical: numbers via exact Decimal only."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, Decimal):
        return emit_exact_decimal(value)
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        raise ValueError("SchemaCanonical forbids float intermediate")
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=lambda k: k.encode("utf-8"))
        parts = [
            f"{json.dumps(k, ensure_ascii=False, separators=(',', ':'))}:{schema_canonical(value[k])}"
            for k in keys
        ]
        return "{" + ",".join(parts) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(schema_canonical(v) for v in value) + "]"
    raise TypeError(f"unsupported SchemaCanonical type: {type(value)}")


def parse_json_decimal_preserving(text: str) -> Any:
    """
    Parse JSON with number tokens as Decimal (never float).
    Uses a lightweight transform: replace numbers with quoted tokens then restore.
    """
    # Standard library json.loads converts to float — avoid that for numbers.
    # Use object_hook path with parse_float=Decimal and parse_int=int.
    return json.loads(text, parse_float=Decimal, parse_int=int, parse_constant=_reject_const)


def _reject_const(name: str) -> None:
    raise ValueError(f"disallowed JSON constant: {name}")


def schema_digest_from_json_text(schema_json: str) -> str:
    tree = parse_json_decimal_preserving(schema_json)
    return sha256_hex(schema_canonical(tree))
