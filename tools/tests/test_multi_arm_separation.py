#!/usr/bin/env python3
"""§2 formal multi-arm: single-dimension separation (R2/R3/R4/R7/R8)."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from bench_analyze.report import analyze_paths  # noqa: E402


class MultiArmSingleDimensionSeparation(unittest.TestCase):
    """Each listed dimension alone must separate arms; identical arms must not."""

    def _base_meta(self, arm: str, **overrides) -> dict:
        meta = {
            "runId": f"run-{arm}",
            "sessionId": "s",
            "armId": arm,
            "comparable": True,
            "declaredVariables": [],
            "controlFingerprint": "ctrl-same",
            "configFingerprint": "cfg-same",
            "toolsetFingerprint": "tool-same",
            "buildId": "build-same",
            "caseBinding": {"casePackId": "none", "promptSha256": None},
            "enabledProbePacks": [],
            "isolation": {"session": "fresh", "memory": "cleared", "cache": "cold"},
        }
        meta.update(overrides)
        return meta

    def _write_arm_run(self, root: Path, arm: str, meta: dict) -> None:
        run = root / f"run-{arm}"
        run.mkdir()
        (run / "probes.jsonl").write_text("", encoding="utf-8")
        (run / "probe-completeness.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runId": f"run-{arm}",
                    "sequenceMax": -1,
                    "enqueued": 0,
                    "written": 0,
                    "dropped": 0,
                    "truncated": 0,
                    "error": 0,
                    "drainTimedOut": False,
                    "sinkIoFailed": False,
                    "completenessIoFailed": False,
                    "complete": True,
                }
            ),
            encoding="utf-8",
        )
        (run / "run-meta.json").write_text(json.dumps(meta), encoding="utf-8")

    def _assert_separated(self, root: Path, reason_substr: str) -> None:
        report = analyze_paths([root])
        reasons = report.get("crossArmSeparationReasons") or []
        self.assertTrue(reasons, f"expected separation, got empty: {report}")
        joined = " ".join(reasons)
        self.assertIn(reason_substr, joined, f"expected {reason_substr!r} in {reasons}")

    def test_identical_arms_do_not_separate(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            for arm in ("a", "b"):
                self._write_arm_run(root, arm, self._base_meta(arm))
            report = analyze_paths([root])
            self.assertFalse(report.get("crossArmSeparationReasons") or [])

    def test_isolation_session_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta(
                    "b",
                    isolation={"session": "shared", "memory": "cleared", "cache": "cold"},
                ),
            )
            self._assert_separated(root, "isolation.session")

    def test_isolation_memory_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta(
                    "b",
                    isolation={"session": "fresh", "memory": "retained", "cache": "cold"},
                ),
            )
            self._assert_separated(root, "isolation.memory")

    def test_isolation_cache_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta(
                    "b",
                    isolation={"session": "fresh", "memory": "cleared", "cache": "warm"},
                ),
            )
            self._assert_separated(root, "isolation.cache")

    def test_build_id_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(root, "b", self._base_meta("b", buildId="build-other"))
            self._assert_separated(root, "buildId")

    def test_toolset_fingerprint_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta("b", toolsetFingerprint="tool-other"),
            )
            self._assert_separated(root, "toolsetFingerprint")

    def test_control_fingerprint_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta("b", controlFingerprint="ctrl-other"),
            )
            self._assert_separated(root, "controlFingerprint")

    def test_case_pack_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._write_arm_run(root, "a", self._base_meta("a"))
            self._write_arm_run(
                root,
                "b",
                self._base_meta(
                    "b",
                    caseBinding={
                        "casePackId": "other.case.v1",
                        "promptSha256": "ab" * 32,
                    },
                ),
            )
            self._assert_separated(root, "case_mismatch")

    def test_prompt_sha_mismatch_separates(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            sha_a = "11" * 32
            sha_b = "22" * 32
            self._write_arm_run(
                root,
                "a",
                self._base_meta(
                    "a",
                    caseBinding={"casePackId": "shared.case.v1", "promptSha256": sha_a},
                ),
            )
            self._write_arm_run(
                root,
                "b",
                self._base_meta(
                    "b",
                    caseBinding={"casePackId": "shared.case.v1", "promptSha256": sha_b},
                ),
            )
            self._assert_separated(root, "case_mismatch")


if __name__ == "__main__":
    unittest.main()
