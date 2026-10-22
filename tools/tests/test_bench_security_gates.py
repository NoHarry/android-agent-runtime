#!/usr/bin/env python3
"""Security / anti-overfit / sample-independence gates (SPEC-RPB-009, A1–A8 helpers)."""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
ANDROID = TOOLS.parent
REPO = ANDROID
RUNTIME_MAIN = ANDROID / "agent-runtime" / "src" / "main" / "kotlin"


class VerticalHardcodeScan(unittest.TestCase):
    """A8: runtime main sources must not hardcode sample navigation tool gates."""

    BANNED = [
        r"\bnavi_ordered_flow\b",
        r"\bupdate_todo\b.*finish",  # sample oracle coupling patterns — soft
        r"mcp__device__",
        r"mcp__navi__",
        r"set_temperature.*gate",
    ]

    def test_no_vertical_gate_constants_in_runtime_main(self):
        if not RUNTIME_MAIN.is_dir():
            self.skipTest("runtime main missing")
        hits: list[str] = []
        for path in RUNTIME_MAIN.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            # allow comments mentioning bans only if not in probe evaluator paths — scan strictly
            for pat in (
                r"mcp__device__",
                r"mcp__navi__",
                r"navi_ordered_flow",
                r"cc\.noharry\.(?!agent\b)[a-z]+\.",
            ):
                if re.search(pat, text):
                    hits.append(f"{path.relative_to(ANDROID)}:{pat}")
        self.assertEqual([], hits, f"vertical/sample coupling in runtime: {hits}")


class SamplePackIndependence(unittest.TestCase):
    def test_core_fixtures_not_under_sample(self):
        fixtures = TOOLS / "fixtures" / "bench-cases"
        self.assertTrue(fixtures.is_dir())
        for p in fixtures.glob("*.json"):
            self.assertNotIn("/samples/", str(p.resolve()))
            data = p.read_text(encoding="utf-8")
            self.assertNotIn('"normative": true', data.replace(" ", ""))


class AnalyzerImportBoundary(unittest.TestCase):
    def test_bench_analyze_has_no_sample_imports(self):
        pkg = TOOLS / "bench_analyze"
        for path in pkg.rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("sample_host", text)
            self.assertNotIn("quickstart", text)


if __name__ == "__main__":
    unittest.main()
