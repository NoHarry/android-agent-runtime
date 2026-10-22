#!/usr/bin/env python3
"""CLI for runtime probe bench analysis (SPEC-RPB-007).

Extends / coexists with analyze_phase3_bench.py. Prefer this for probes + Case Pack.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Allow running as script from tools/
_TOOLS = Path(__file__).resolve().parent
if str(_TOOLS) not in sys.path:
    sys.path.insert(0, str(_TOOLS))

from bench_analyze.report import analyze_paths, render_markdown  # noqa: E402


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="Run evidence directories or parents containing runs",
    )
    parser.add_argument(
        "--case-pack",
        action="append",
        type=Path,
        default=[],
        help="Local Case Pack path (repeatable). Path must be under allowed roots unless --allow-any-case-root.",
    )
    parser.add_argument("--require-gates", action="store_true")
    parser.add_argument("--require-comparable", action="store_true")
    parser.add_argument("--final-text", type=str, default=None)
    parser.add_argument("--allow-any-case-root", action="store_true", help="Test-only: skip realpath root check")
    parser.add_argument("--output", type=Path, help="Markdown report path")
    parser.add_argument("--json-output", type=Path, help="JSON report path")
    return parser


def _emit_reports(report: dict, md: str, args: argparse.Namespace) -> None:
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(md, encoding="utf-8")
        print(f"[OK] Wrote {args.output}")
    else:
        print(md)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"[OK] Wrote {args.json_output}")


def _exit_code(report: dict, args: argparse.Namespace) -> int:
    if args.require_gates and report.get("overallPackStatus") in ("FAIL", "INCOMPLETE", "ERROR"):
        return 2
    if args.require_comparable and (
        report.get("crossArmSeparationReasons")
        or any(a.get("separationReasons") for a in report.get("arms") or [])
    ):
        return 3
    if report.get("packErrors"):
        return 4
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    report = analyze_paths(
        args.paths,
        case_packs=args.case_pack,
        require_gates=args.require_gates,
        require_comparable=args.require_comparable,
        final_text=args.final_text,
        allow_any_case_root=args.allow_any_case_root,
    )
    md = render_markdown(report)
    _emit_reports(report, md, args)
    return _exit_code(report, args)


if __name__ == "__main__":
    raise SystemExit(main())
