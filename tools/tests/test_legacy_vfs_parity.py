#!/usr/bin/env python3
"""Legacy VFS dual-read + legacy/new parity (SPEC-RPB-008, Compatibility 1→2)."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

from bench_analyze.legacy_vfs import load_vfs_facts, parse_vfs_ops_jsonl  # noqa: E402
from bench_analyze.parity import compare_parity  # noqa: E402
from bench_analyze.report import analyze_run  # noqa: E402

FIXTURES = TOOLS / "fixtures" / "legacy-dual-read"
LEGACY_RUN = FIXTURES / "legacy-run"
NEW_RUN = FIXTURES / "new-run"


class LegacyVfsFixtureTests(unittest.TestCase):
    def test_valid_legacy_vfs_fixture_readable(self):
        facts = load_vfs_facts(LEGACY_RUN)
        self.assertEqual(facts.source, "legacy:vfs-ops.jsonl")
        self.assertEqual(facts.op_count, 3)
        self.assertEqual(facts.ok_count, 2)
        self.assertEqual(facts.failed_count, 1)
        self.assertEqual([o.op for o in facts.ops], ["read_file", "write_file", "read_file"])
        self.assertEqual([o.outcome for o in facts.ops], ["ok", "ok", "failed"])
        self.assertIn("executeMs", facts.timing_sums)
        self.assertEqual(facts.timing_sums["executeMs"], 6 + 8 + 2)
        self.assertEqual(facts.errors, [])

    def test_legacy_only_report_is_incomplete(self):
        analysis = analyze_run(LEGACY_RUN)
        self.assertTrue(any("legacy" in s for s in analysis.sources))
        self.assertIn("legacy:vfs-ops.jsonl", analysis.sources)
        self.assertFalse(analysis.completeness.get("complete"))
        self.assertTrue(analysis.completeness.get("missingSideCar"))
        reasons = " ".join(analysis.incomplete_reasons)
        self.assertIn("missing probe-completeness.json", reasons)
        self.assertIn("missing probes.jsonl", reasons)
        self.assertFalse(analysis.comparable)
        self.assertTrue(analysis.compatibility.get("incomplete"))
        self.assertTrue(analysis.compatibility.get("legacyDualRead"))
        self.assertEqual(analysis.vfs.get("opCount"), 3)
        # Must not invent pack business PASS without probes/case.
        self.assertNotEqual(analysis.pack_status, "PASS")


class ParityTests(unittest.TestCase):
    def test_legacy_new_common_field_parity_pass(self):
        result = compare_parity(LEGACY_RUN, NEW_RUN)
        self.assertTrue(
            result.ok,
            f"parity should PASS; mismatches={result.mismatches} not_comparable={result.not_comparable}",
        )
        self.assertIn("tool.toolEndCount", result.compared)
        self.assertIn("vfs.vfsOpCount", result.compared)
        self.assertEqual(result.legacy_metrics["tool"]["toolEndCount"], 2)
        self.assertEqual(result.new_metrics["tool"]["toolEndCount"], 2)
        self.assertEqual(result.legacy_metrics["vfs"]["vfsOpCount"], 3)
        self.assertEqual(result.new_metrics["vfs"]["vfsOpCount"], 3)

    def test_intentional_vfs_count_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "bad-new"
            shutil.copytree(NEW_RUN, bad)
            # Drop last VFS op → count 2 vs legacy 3
            lines = (bad / "vfs-ops.jsonl").read_text(encoding="utf-8").splitlines()
            (bad / "vfs-ops.jsonl").write_text("\n".join(lines[:2]) + "\n", encoding="utf-8")
            result = compare_parity(LEGACY_RUN, bad)
            self.assertFalse(result.ok)
            self.assertTrue(
                any("vfs.vfsOpCount" in m for m in result.mismatches),
                result.mismatches,
            )

    def test_intentional_vfs_order_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "bad-order"
            shutil.copytree(NEW_RUN, bad)
            ops = [
                json.loads(line)
                for line in (bad / "vfs-ops.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            ops[0], ops[1] = ops[1], ops[0]
            (bad / "vfs-ops.jsonl").write_text(
                "\n".join(json.dumps(o) for o in ops) + "\n",
                encoding="utf-8",
            )
            result = compare_parity(LEGACY_RUN, bad)
            self.assertFalse(result.ok)
            self.assertTrue(any("vfs.vfsOpOrder" in m for m in result.mismatches), result.mismatches)

    def test_intentional_vfs_outcome_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "bad-outcome"
            shutil.copytree(NEW_RUN, bad)
            ops = [
                json.loads(line)
                for line in (bad / "vfs-ops.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            ops[-1]["status"] = "OK"  # was FAILED
            (bad / "vfs-ops.jsonl").write_text(
                "\n".join(json.dumps(o) for o in ops) + "\n",
                encoding="utf-8",
            )
            result = compare_parity(LEGACY_RUN, bad)
            self.assertFalse(result.ok)
            self.assertTrue(
                any("vfs.vfsOutcomeOrder" in m for m in result.mismatches),
                result.mismatches,
            )

    def test_intentional_tool_order_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "bad-tools"
            shutil.copytree(NEW_RUN, bad)
            # Flip tool names on execute.end stages
            lines = []
            for line in (bad / "probes.jsonl").read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                ev = json.loads(line)
                if ev.get("stageId") == "tool.execute.end":
                    attrs = ev.setdefault("attrs", {})
                    if attrs.get("toolName") == "read_file":
                        attrs["toolName"] = "write_file"
                    elif attrs.get("toolName") == "write_file":
                        attrs["toolName"] = "read_file"
                lines.append(json.dumps(ev))
            (bad / "probes.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
            result = compare_parity(LEGACY_RUN, bad)
            self.assertFalse(result.ok)
            self.assertTrue(
                any("tool.toolNameOrder" in m for m in result.mismatches),
                result.mismatches,
            )


class FailClosedVfsParseTests(unittest.TestCase):
    def test_malformed_line_incomplete(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            path.write_text("{not-json\n", encoding="utf-8")
            facts = parse_vfs_ops_jsonl(path)
            self.assertTrue(facts.errors)
            self.assertTrue(any("malformed" in r for r in facts.incomplete_reasons))

    def test_unknown_schema_version_incomplete(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 99,
                        "operationId": "x",
                        "op": "read_file",
                        "path": "/workspace/a",
                        "status": "OK",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            facts = parse_vfs_ops_jsonl(path)
            self.assertTrue(any("unknown" in r for r in facts.incomplete_reasons))
            self.assertEqual(facts.op_count, 0)

    def test_duplicate_operation_id_incomplete(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            line = json.dumps(
                {
                    "schemaVersion": 1,
                    "operationId": "dup",
                    "op": "read_file",
                    "path": "/workspace/a",
                    "status": "OK",
                    "timings": {"executeMs": 1},
                }
            )
            path.write_text(line + "\n" + line + "\n", encoding="utf-8")
            facts = parse_vfs_ops_jsonl(path)
            self.assertEqual(facts.op_count, 1)
            self.assertTrue(any("duplicate" in r for r in facts.incomplete_reasons))

    def test_partial_line_then_valid_keeps_valid_and_flags_incomplete(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            good = json.dumps(
                {
                    "schemaVersion": 1,
                    "operationId": "ok1",
                    "op": "read_file",
                    "path": "/workspace/a",
                    "status": "OK",
                    "timings": {"executeMs": 3},
                }
            )
            path.write_text("{{bad\n" + good + "\n", encoding="utf-8")
            facts = parse_vfs_ops_jsonl(path)
            self.assertEqual(facts.op_count, 1)
            self.assertTrue(facts.incomplete_reasons)

    def test_unknown_status_incomplete_with_error(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "operationId": "bogus",
                        "op": "read_file",
                        "path": "/workspace/a",
                        "status": "BOGUS",
                        "timings": {"executeMs": 1},
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            facts = parse_vfs_ops_jsonl(path)
            self.assertEqual(facts.op_count, 1)
            self.assertEqual(facts.ops[0].outcome, "unknown")
            self.assertTrue(facts.errors)
            self.assertTrue(any("unknown vfs status" in r for r in facts.incomplete_reasons))
            analysis = analyze_run(Path(td))
            # ensure analyze_run surfaces incomplete when only vfs-ops present
            self.assertTrue(
                any("unknown vfs status" in r for r in analysis.incomplete_reasons)
                or any("unknown" in e for e in (analysis.vfs.get("errors") or [])),
            )

    def test_invalid_timing_string_float_bool_incomplete(self):
        cases = [
            ("not-a-number", "string"),
            (1.5, "float"),
            (True, "bool"),
        ]
        for bad_val, label in cases:
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as td:
                    path = Path(td) / "vfs-ops.jsonl"
                    path.write_text(
                        json.dumps(
                            {
                                "schemaVersion": 1,
                                "operationId": f"t-{label}",
                                "op": "read_file",
                                "path": "/workspace/a",
                                "status": "OK",
                                "timings": {"executeMs": bad_val},
                            }
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                    facts = parse_vfs_ops_jsonl(path)
                    self.assertTrue(facts.errors, facts.errors)
                    self.assertTrue(
                        any("invalid vfs timing" in r for r in facts.incomplete_reasons),
                        facts.incomplete_reasons,
                    )
                    self.assertNotIn("executeMs", facts.ops[0].timings)

    def test_schema_cache_hit_non_bool_not_cast(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "vfs-ops.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "operationId": "c1",
                        "op": "read_file",
                        "path": "/workspace/a",
                        "status": "OK",
                        "schemaCacheHit": "false",
                        "timings": {"executeMs": 1},
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            facts = parse_vfs_ops_jsonl(path)
            self.assertIsNone(facts.ops[0].schema_cache_hit)
            self.assertTrue(any("schemaCacheHit" in r for r in facts.incomplete_reasons))


class FailClosedParityCorruptionTests(unittest.TestCase):
    def _copy_pair(self, td: str) -> tuple[Path, Path]:
        legacy = Path(td) / "legacy"
        new = Path(td) / "new"
        shutil.copytree(LEGACY_RUN, legacy)
        shutil.copytree(NEW_RUN, new)
        return legacy, new

    def test_malformed_only_on_legacy_parity_fails(self):
        with tempfile.TemporaryDirectory() as td:
            legacy, new = self._copy_pair(td)
            with (legacy / "vfs-ops.jsonl").open("a", encoding="utf-8") as fh:
                fh.write("{not-json\n")
            result = compare_parity(legacy, new)
            self.assertFalse(result.ok)
            self.assertTrue(any("parseHealth" in m for m in result.mismatches), result.mismatches)
            self.assertTrue(result.legacy_metrics.get("vfsErrors"))

    def test_malformed_only_on_new_parity_fails(self):
        with tempfile.TemporaryDirectory() as td:
            legacy, new = self._copy_pair(td)
            with (new / "vfs-ops.jsonl").open("a", encoding="utf-8") as fh:
                fh.write("{not-json\n")
            result = compare_parity(legacy, new)
            self.assertFalse(result.ok)
            self.assertTrue(any("parseHealth" in m for m in result.mismatches), result.mismatches)
            self.assertTrue(result.new_metrics.get("vfsErrors"))

    def test_identical_malformed_both_sides_parity_fails(self):
        with tempfile.TemporaryDirectory() as td:
            legacy, new = self._copy_pair(td)
            for side in (legacy, new):
                with (side / "vfs-ops.jsonl").open("a", encoding="utf-8") as fh:
                    fh.write("{same-bad\n")
            result = compare_parity(legacy, new)
            self.assertFalse(result.ok)
            self.assertTrue(any("parseHealth" in m for m in result.mismatches), result.mismatches)

    def test_unknown_status_parity_fails(self):
        with tempfile.TemporaryDirectory() as td:
            legacy, new = self._copy_pair(td)
            ops = [
                json.loads(line)
                for line in (legacy / "vfs-ops.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            ops[0]["status"] = "BOGUS"
            (legacy / "vfs-ops.jsonl").write_text(
                "\n".join(json.dumps(o) for o in ops) + "\n",
                encoding="utf-8",
            )
            result = compare_parity(legacy, new)
            self.assertFalse(result.ok)
            self.assertTrue(
                any("parseHealth" in m or "unknown" in m for m in result.mismatches)
                or result.legacy_metrics.get("vfsErrors"),
                result.mismatches,
            )

    def test_invalid_timing_parity_fails(self):
        with tempfile.TemporaryDirectory() as td:
            legacy, new = self._copy_pair(td)
            ops = [
                json.loads(line)
                for line in (new / "vfs-ops.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            ops[0]["timings"]["executeMs"] = "not-a-number"
            (new / "vfs-ops.jsonl").write_text(
                "\n".join(json.dumps(o) for o in ops) + "\n",
                encoding="utf-8",
            )
            result = compare_parity(legacy, new)
            self.assertFalse(result.ok)
            self.assertTrue(result.new_metrics.get("vfsErrors"), result.new_metrics)
            self.assertTrue(any("parseHealth" in m for m in result.mismatches), result.mismatches)

    def test_valid_fixture_pair_still_pass(self):
        result = compare_parity(LEGACY_RUN, NEW_RUN)
        self.assertTrue(result.ok, result.mismatches)


class AnalyzerDefaultRootBoundary(unittest.TestCase):
    def test_default_allowed_roots_exclude_sample_host(self):
        from bench_analyze.case_pack import default_allowed_roots

        roots = [str(r).replace("\\", "/") for r in default_allowed_roots()]
        for r in roots:
            self.assertNotIn("/samples/", r)


if __name__ == "__main__":
    unittest.main()
