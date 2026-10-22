#!/usr/bin/env python3
"""Cross-language e2e: Python analyzer consumes REAL Kotlin probe artifacts.

Golden files under tools/fixtures/kotlin-e2e are emitted by the actual Kotlin
probe path (NativeToolLoop -> ProbeRunAttachment -> FileProbeEventSink /
FileCompletenessStore / writeRunMetaJson) and byte-locked by the agent-runtime
unit test ProbeE2eGoldenTest. This file never hand-writes probe events: when
the Kotlin emitter changes, the Kotlin golden test fails first, and the goldens
are regenerated with PROBE_GOLDEN_UPDATE=1.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from bench_analyze.report import analyze_paths  # noqa: E402

GOLDEN_RUN = TOOLS / "fixtures" / "kotlin-e2e"
CASE_PACK = TOOLS / "fixtures" / "bench-cases" / "kotlin_e2e.v1.json"
GOLDEN_FILES = ("probes.jsonl", "probe-completeness.json", "run-meta.json")
# ScriptProbeClock: each emit increments elapsed by 1ms.
# 21 events (orig 19 + case.bound at start + probe.completeness after drain) => wall 20ms.
EXPECTED_WALL_MS = 20
EXPECTED_TTFB_MS = 4  # run.start seq0 nanos 1ms -> first model.round.start after case.bound
EXPECTED_ROUNDS = 3
EXPECTED_MODEL_MEDIAN_MS = 5  # rounds pair by order: (5ms,5ms,1ms) -> median 5
EXPECTED_TOOL_MIN_MS = 1
EXPECTED_TOOL_MAX_MS = 1


class KotlinRealArtifactE2E(unittest.TestCase):
    def test_golden_artifacts_exist(self):
        for name in GOLDEN_FILES:
            self.assertTrue(
                (GOLDEN_RUN / name).is_file(),
                f"missing Kotlin golden {name}; run ProbeE2eGoldenTest with PROBE_GOLDEN_UPDATE=1",
            )

    def test_run_meta_binds_case_and_non_empty_toolset(self):
        meta = json.loads((GOLDEN_RUN / "run-meta.json").read_text(encoding="utf-8"))
        cb = meta.get("caseBinding") or {}
        self.assertEqual("kotlin_e2e.v1", cb.get("casePackId"), meta)
        self.assertTrue(cb.get("promptSha256"), "promptSha256 required when case bound")
        self.assertTrue(meta.get("toolset"), "A6: toolset must not be empty")
        names = {t.get("qualifiedName") for t in meta["toolset"]}
        self.assertIn("read_file", names)
        self.assertIn("mcp__default__search_docs", names)
        self.assertTrue(meta.get("comparable"), meta)

    def test_case_family_stages_present_in_real_kotlin_artifacts(self):
        """Production case/probe-completeness family must appear in real Kotlin emission."""
        lines = (GOLDEN_RUN / "probes.jsonl").read_text(encoding="utf-8").splitlines()
        stages = [json.loads(line)["stageId"] for line in lines if line.strip()]
        self.assertIn("case.bound", stages)
        self.assertIn("probe.completeness", stages)
        case_evt = next(json.loads(line) for line in lines if '"case.bound"' in line)
        self.assertEqual("kotlin_e2e.v1", case_evt["attrs"]["casePackId"])
        comp_evt = next(json.loads(line) for line in lines if '"probe.completeness"' in line)
        attrs = comp_evt["attrs"]
        self.assertIn("complete", attrs)
        self.assertIn("enqueued", attrs)
        self.assertIn("written", attrs)
        # Side-car remains the analyzer truth and must be complete for this golden.
        side = json.loads((GOLDEN_RUN / "probe-completeness.json").read_text(encoding="utf-8"))
        self.assertTrue(side["complete"])
        self.assertEqual(side["enqueued"], side["written"])

    def test_no_case_generic_report_from_real_kotlin_artifacts(self):
        report = analyze_paths([GOLDEN_RUN])
        self.assertEqual(1, report["runCount"])
        row = report["arms"][0]["runs"][0]
        self.assertEqual("probes.jsonl", row["sources"][0])
        self.assertTrue(row["completeness"]["complete"], row["incompleteReasons"])
        self.assertTrue(row["graph"]["complete"], row["graph"]["diagnostics"])
        tool = [n for n in row["graph"]["nodes"] if n["toolName"] == "mcp__default__search_docs"]
        self.assertEqual(1, len(tool), row["graph"]["nodes"])
        self.assertTrue(tool[0]["ok"])
        self.assertEqual("complete", tool[0]["status"])
        self.assertEqual("BACKEND", tool[0]["source"])
        timing = row["timing"]
        self.assertEqual(EXPECTED_WALL_MS, timing["wallMs"], timing)
        self.assertEqual(EXPECTED_ROUNDS, timing["rounds"], timing)
        self.assertEqual(EXPECTED_TTFB_MS, timing["ttfbMs"], timing)
        self.assertEqual(EXPECTED_MODEL_MEDIAN_MS, timing["model"]["median"], timing)
        self.assertEqual(EXPECTED_TOOL_MIN_MS, timing["tool"]["min"], timing)
        self.assertEqual(EXPECTED_TOOL_MAX_MS, timing["tool"]["max"], timing)
        self.assertEqual("SKIPPED_NO_CASE", report["overallPackStatus"])
        self.assertFalse(report["businessPassInvented"])

    def test_case_bound_gate_report_from_real_kotlin_artifacts(self):
        report = analyze_paths([GOLDEN_RUN], case_packs=[CASE_PACK], final_text="done")
        self.assertEqual([], report["packErrors"])
        row = report["arms"][0]["runs"][0]
        statuses = {g["id"]: g["status"] for g in row["gates"]}
        self.assertEqual("PASS", statuses.get("G_SEARCH_EXECUTED"), row["gates"])
        self.assertEqual("PASS", statuses.get("G_NO_NETWORK"), row["gates"])
        self.assertEqual("PASS", statuses.get("G_FINAL_TEXT"), row["gates"])
        self.assertEqual("PASS", report["overallPackStatus"], row["gates"])
        self.assertFalse(report["businessPassInvented"])
        # Binding must match run-meta (not silent rebind of none → pack).
        meta_cb = row["meta"]["caseBinding"]
        self.assertEqual("kotlin_e2e.v1", meta_cb.get("casePackId"))
        self.assertTrue(meta_cb.get("promptSha256"))

    def test_unbound_meta_rejects_attached_pack(self):
        """Regression: casePackId=none must not PASS under an external pack."""
        import tempfile
        from shutil import copytree

        with tempfile.TemporaryDirectory() as td:
            dest = Path(td) / "run"
            copytree(GOLDEN_RUN, dest)
            meta_path = dest / "run-meta.json"
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["caseBinding"] = {"casePackId": "none"}
            meta_path.write_text(json.dumps(meta, separators=(",", ":")), encoding="utf-8")
            report = analyze_paths([dest], case_packs=[CASE_PACK], final_text="done")
            self.assertEqual("ERROR", report["overallPackStatus"], report)
            row = report["arms"][0]["runs"][0]
            self.assertEqual("ERROR", row["packStatus"])
            self.assertTrue(
                any("case_binding" in r for r in row["incompleteReasons"]),
                row["incompleteReasons"],
            )

    def test_wrong_prompt_hash_rejects_case_bound_pack(self):
        """Regression: non-empty but wrong promptSha256 must not PASS (detail-design §828)."""
        import tempfile
        from shutil import copytree

        with tempfile.TemporaryDirectory() as td:
            dest = Path(td) / "run"
            copytree(GOLDEN_RUN, dest)
            meta_path = dest / "run-meta.json"
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["caseBinding"] = {
                "casePackId": "kotlin_e2e.v1",
                "promptSha256": "0" * 64,
            }
            meta_path.write_text(json.dumps(meta, separators=(",", ":")), encoding="utf-8")
            report = analyze_paths([dest], case_packs=[CASE_PACK], final_text="done")
            self.assertEqual("ERROR", report["overallPackStatus"], report)
            reasons = report["arms"][0]["runs"][0]["incompleteReasons"]
            self.assertTrue(any("prompt_mismatch" in r for r in reasons), reasons)


if __name__ == "__main__":
    unittest.main()
