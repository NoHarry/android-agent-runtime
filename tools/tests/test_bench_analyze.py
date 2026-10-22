#!/usr/bin/env python3
"""Unit tests for bench_analyze: graph, gates, B4 any/all, B6 canonical, e2e reports."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from bench_analyze.canonical import (  # noqa: E402
    config_canonical,
    emit_exact_decimal,
    parse_exact_decimal_token,
    schema_canonical,
    schema_digest_from_json_text,
    sha256_hex,
)
from bench_analyze.case_pack import CasePackError, load_case_pack, parse_case_pack  # noqa: E402
from bench_analyze.gates import evaluate_pack, merge_any_all  # noqa: E402
from bench_analyze.graph import build_tool_graph  # noqa: E402
from bench_analyze.report import analyze_paths, analyze_run  # noqa: E402

FIXTURES = TOOLS / "fixtures" / "bench-cases"
import os  # noqa: E402

# A host app's debug case pack, when one is at hand (HOST_DEBUG_CASE_PACK=path/to/sample.debug.v1.json).
SAMPLE_DEBUG_CASE_PACK = Path(os.environ.get("HOST_DEBUG_CASE_PACK", "/nonexistent/sample.debug.v1.json"))


def _sample_debug_case_pack_path() -> Path:
    return SAMPLE_DEBUG_CASE_PACK


def _case_binding_meta(case_pack_id: str, prompt_sha256: str) -> dict:
    return {
        "caseBinding": {
            "casePackId": case_pack_id,
            "promptSha256": prompt_sha256,
        },
        "enabledProbePacks": ["bench_core"],
    }


def _tool_event(seq, stage, tool, call_id, nanos=None, **attrs):
    """Build one probe-shaped tool event; nanos=None omits elapsedNanos."""
    event = {
        "sequence": seq,
        "stageId": stage,
        "attrs": {"toolName": tool, "callId": call_id, "source": "BACKEND", **attrs},
    }
    if nanos is not None:
        event["elapsedNanos"] = nanos
    return event


def _search_pair_events(**start_attrs):
    """search start/end pair with optional extra start attrs."""
    return [
        _tool_event(0, "tool.execute.start", "mcp__x__search", "a1", **start_attrs),
        _tool_event(1, "tool.execute.end", "mcp__x__search", "a1", ok=True),
    ]


def synthetic_dual_events() -> list[dict]:
    """search then route success; appearance/completion ordered correctly."""
    return [
        _tool_event(
            0, "tool.execute.start", "mcp__x__search", "a1", 10,
            roundIndex=0, args={"q": "weather"},
        ),
        _tool_event(1, "tool.execute.end", "mcp__x__search", "a1", 20, ok=True, roundIndex=0),
        _tool_event(2, "tool.execute.start", "mcp__x__route", "b1", 30, roundIndex=0),
        _tool_event(3, "tool.execute.end", "mcp__x__route", "b1", 40, ok=True, roundIndex=0),
    ]


class CanonicalTests(unittest.TestCase):
    def test_schema_0_5_and_large_int(self):
        text = schema_canonical(
            {
                "maximum": parse_exact_decimal_token("32"),
                "minimum": parse_exact_decimal_token("0.5"),
            }
        )
        self.assertIn('"minimum":0.5', text)
        self.assertIn('"maximum":32', text)
        # keys sorted: maximum before minimum
        self.assertLess(text.index("maximum"), text.index("minimum"))

        token = "9007199254740993"
        self.assertEqual(token, emit_exact_decimal(parse_exact_decimal_token(token)))

    def test_exp_trailing(self):
        self.assertEqual("0.15", emit_exact_decimal(parse_exact_decimal_token("1.5e-1")))
        self.assertEqual("32", emit_exact_decimal(parse_exact_decimal_token("32.0")))

    def test_config_rejects_float(self):
        with self.assertRaises(ValueError):
            config_canonical({"x": 0.5})

    def test_schema_digest_stable_for_tools_json_fragment(self):
        frag = '{"type":"object","properties":{"t":{"type":"number","minimum":0.5}}}'
        d1 = schema_digest_from_json_text(frag)
        d2 = schema_digest_from_json_text(frag)
        self.assertEqual(d1, d2)
        self.assertEqual(64, len(d1))


class B4TruthTableTests(unittest.TestCase):
    def test_any_all_table(self):
        self.assertEqual("PASS", merge_any_all(["YES", "NO"], "any", evidence_complete=True))
        self.assertEqual("INCOMPLETE", merge_any_all(["NO", "UNKNOWN"], "any", evidence_complete=True))
        self.assertEqual("FAIL", merge_any_all(["NO", "NO"], "any", evidence_complete=True))
        self.assertEqual("FAIL", merge_any_all(["YES", "NO"], "all", evidence_complete=True))
        self.assertEqual("INCOMPLETE", merge_any_all(["YES", "UNKNOWN"], "all", evidence_complete=True))
        self.assertEqual("PASS", merge_any_all(["YES", "YES"], "all", evidence_complete=True))
        self.assertEqual("PASS", merge_any_all([], "all", evidence_complete=True))  # vacuous


class GraphAndOrderTests(unittest.TestCase):
    def test_order_fails_when_after_appears_before_before_completes(self):
        # after starts at seq1 ends 10; before starts 2 ends 3 ok — classic FAIL
        events = [
            {
                "sequence": 1,
                "stageId": "tool.execute.start",
                "attrs": {"toolName": "mcp__x__route", "callId": "after", "source": "BACKEND"},
            },
            {
                "sequence": 2,
                "stageId": "tool.execute.start",
                "attrs": {"toolName": "mcp__x__search", "callId": "before", "source": "BACKEND"},
            },
            {
                "sequence": 3,
                "stageId": "tool.execute.end",
                "attrs": {
                    "toolName": "mcp__x__search",
                    "callId": "before",
                    "ok": True,
                    "source": "BACKEND",
                },
            },
            {
                "sequence": 10,
                "stageId": "tool.execute.end",
                "attrs": {
                    "toolName": "mcp__x__route",
                    "callId": "after",
                    "ok": True,
                    "source": "BACKEND",
                },
            },
        ]
        graph = build_tool_graph(events)
        pack = load_case_pack(FIXTURES / "synthetic.dual_step.v1.json")
        result = evaluate_pack(
            pack,
            graph,
            completeness_complete=True,
            enabled_probe_packs=["bench_core"],
            final_text="ok",
        )
        order = next(g for g in result.gates if g.gate_id == "G_B_AFTER_A")
        self.assertEqual("FAIL", order.status)

    def test_dual_step_pass_path(self):
        graph = build_tool_graph(synthetic_dual_events())
        pack = load_case_pack(FIXTURES / "synthetic.dual_step.v1.json")
        result = evaluate_pack(
            pack,
            graph,
            completeness_complete=True,
            enabled_probe_packs=["bench_core"],
            final_text="all ok",
        )
        self.assertEqual("PASS", result.status)
        types = {g.gate_type for g in result.gates}
        self.assertEqual(
            {
                "exists",
                "order",
                "forbids_before",
                "forbid",
                "args_match",
                "round_width",
                "count_ratio",
                "final_response",
            },
            types,
        )


class GateOperatorFixtures(unittest.TestCase):
    """Each operator: positive, negative, missing-evidence."""

    def setUp(self):
        self.graph = build_tool_graph(synthetic_dual_events())
        self.pack = load_case_pack(FIXTURES / "synthetic.dual_step.v1.json")

    def _eval(self, gates, final_text="ok", complete=True, graph=None):
        data = {
            "schemaVersion": 1,
            "casePackId": "t",
            "normative": False,
            "toolMatchers": self.pack.tool_matchers,
            "gates": gates,
        }
        pack = parse_case_pack(data)
        return evaluate_pack(
            pack,
            graph or self.graph,
            completeness_complete=complete,
            enabled_probe_packs=["bench_core"],
            final_text=final_text,
            evidence_complete=complete,
        )

    def test_exists_pos_neg_missing(self):
        pos = self._eval([{"id": "e", "type": "exists", "matcher": "step_a", "minCount": 1}])
        self.assertEqual("PASS", pos.gates[0].status)
        neg = self._eval([{"id": "e", "type": "exists", "matcher": "step_a", "minCount": 9}])
        self.assertEqual("FAIL", neg.gates[0].status)
        miss = self._eval(
            [{"id": "e", "type": "exists", "matcher": "step_a", "minCount": 9}],
            complete=False,
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)

    def test_forbid_pos_neg_missing(self):
        pos = self._eval([{"id": "f", "type": "forbid", "matcher": "bad_network"}])
        self.assertEqual("PASS", pos.gates[0].status)
        events = synthetic_dual_events() + [
            {
                "sequence": 4,
                "stageId": "tool.execute.start",
                "attrs": {"toolName": "mcp__net__x", "callId": "n1", "source": "NETWORK"},
            }
        ]
        neg = self._eval(
            [{"id": "f", "type": "forbid", "matcher": "bad_network"}],
            graph=build_tool_graph(events),
        )
        self.assertEqual("FAIL", neg.gates[0].status)
        # Zero hits cannot prove absence without complete evidence (goal-contract §5).
        miss = self._eval(
            [{"id": "f", "type": "forbid", "matcher": "bad_network"}],
            complete=False,
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)
        self.assertEqual("INCOMPLETE", miss.status)

    def test_incomplete_evidence_never_business_pass_forbid_only_pack(self):
        """Legal forbid-only Case Pack + incomplete evidence must not overall PASS."""
        miss = self._eval(
            [{"id": "f", "type": "forbid", "matcher": "bad_network"}],
            complete=False,
        )
        self.assertNotEqual("PASS", miss.status)
        self.assertEqual("INCOMPLETE", miss.status)
        for g in miss.gates:
            self.assertNotEqual("PASS", g.status)

    def test_final_response_pos_neg_missing(self):
        pos = self._eval(
            [{"id": "fr", "type": "final_response", "op": "contains", "value": "ok"}],
            final_text="all ok",
        )
        self.assertEqual("PASS", pos.gates[0].status)
        neg = self._eval(
            [{"id": "fr", "type": "final_response", "op": "contains", "value": "ok"}],
            final_text="nope",
        )
        self.assertEqual("FAIL", neg.gates[0].status)
        miss = self._eval(
            [{"id": "fr", "type": "final_response", "op": "contains", "value": "ok"}],
            final_text=None,
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)
        miss_ev = self._eval(
            [{"id": "fr", "type": "final_response", "op": "contains", "value": "ok"}],
            final_text="all ok",
            complete=False,
        )
        self.assertEqual("INCOMPLETE", miss_ev.gates[0].status)

    def _order_gate(self):
        return {
            "id": "o",
            "type": "order",
            "before": {"matcher": "step_a", "requireOk": True},
            "after": {"matcher": "step_b", "requireOk": True},
        }

    def test_order_pos(self):
        pos = self._eval([self._order_gate()])
        self.assertEqual("PASS", pos.gates[0].status)

    def test_order_neg_after_starts_before_before_completes(self):
        # after appears before before completes
        bad_events = [
            _tool_event(1, "tool.execute.start", "mcp__x__route", "b1"),
            _tool_event(2, "tool.execute.start", "mcp__x__search", "a1", args={"q": "x"}),
            _tool_event(3, "tool.execute.end", "mcp__x__search", "a1", ok=True),
            _tool_event(10, "tool.execute.end", "mcp__x__route", "b1", ok=True),
        ]
        neg = self._eval(
            [self._order_gate()],
            graph=build_tool_graph(bad_events),
        )
        self.assertEqual("FAIL", neg.gates[0].status)

    def test_order_missing_evidence_incomplete(self):
        miss = self._eval(
            [self._order_gate()],
            complete=False,
            graph=build_tool_graph([]),
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)

    def _forbids_before_gate(self):
        return {
            "id": "fb",
            "type": "forbids_before",
            "before": {"matcher": "bad_network"},
            "after": {"matcher": "step_b", "requireOk": True},
        }

    def test_forbids_before_pos(self):
        pos = self._eval([self._forbids_before_gate()])
        self.assertEqual("PASS", pos.gates[0].status)

    def test_forbids_before_neg(self):
        # re-sequence: network appears at 0 before step_b completes
        reseq = [
            _tool_event(0, "tool.execute.start", "mcp__net__x", "n0", source="NETWORK"),
            *[{**e, "sequence": i + 1} for i, e in enumerate(synthetic_dual_events())],
        ]
        neg = self._eval(
            [self._forbids_before_gate()],
            graph=build_tool_graph(reseq),
        )
        self.assertEqual("FAIL", neg.gates[0].status)

    def test_forbids_before_missing_evidence_incomplete(self):
        miss = self._eval(
            [self._forbids_before_gate()],
            complete=False,
            graph=build_tool_graph([]),
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)

    def _args_exists_any_gate(self):
        return {
            "id": "a",
            "type": "args_match",
            "matcher": "step_a",
            "path": "$.q",
            "op": "exists",
            "mode": "any",
        }

    def test_args_match_pos(self):
        pos = self._eval([self._args_exists_any_gate()])
        self.assertEqual("PASS", pos.gates[0].status)

    def test_args_match_neg_wrong_value(self):
        # wrong arg value
        events = _search_pair_events(args={"q": "weather"})
        neg = self._eval(
            [
                {
                    "id": "a",
                    "type": "args_match",
                    "matcher": "step_a",
                    "path": "$.q",
                    "op": "eq",
                    "value": "nope",
                    "mode": "any",
                }
            ],
            graph=build_tool_graph(events),
        )
        self.assertEqual("FAIL", neg.gates[0].status)

    def test_args_match_missing_evidence_incomplete(self):
        miss = self._eval(
            [self._args_exists_any_gate()],
            complete=False,
            graph=build_tool_graph([]),
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)

    def test_round_width_pos_neg_missing(self):
        pos = self._eval([{"id": "rw", "type": "round_width", "min": 1, "max": 8}])
        self.assertEqual("PASS", pos.gates[0].status)
        neg = self._eval([{"id": "rw", "type": "round_width", "min": 99, "max": 100}])
        self.assertEqual("FAIL", neg.gates[0].status)
        # elevation: would-be PASS under incomplete evidence
        miss = self._eval(
            [{"id": "rw", "type": "round_width", "min": 1, "max": 8}],
            complete=False,
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)
        self.assertEqual("INCOMPLETE", miss.status)

    def test_count_ratio_pos_neg_missing(self):
        pos = self._eval(
            [
                {
                    "id": "cr",
                    "type": "count_ratio",
                    "numeratorMatcher": "step_a",
                    "denominator": "tool_calls",
                    "max": 1.0,
                }
            ]
        )
        self.assertEqual("PASS", pos.gates[0].status)
        neg = self._eval(
            [
                {
                    "id": "cr",
                    "type": "count_ratio",
                    "numeratorMatcher": "step_a",
                    "denominator": "tool_calls",
                    "min": 0.99,
                }
            ]
        )
        # step_a is 1/2 = 0.5 < 0.99
        self.assertEqual("FAIL", neg.gates[0].status)
        miss = self._eval(
            [
                {
                    "id": "cr",
                    "type": "count_ratio",
                    "numeratorMatcher": "step_a",
                    "denominator": "tool_calls",
                    "max": 1.0,
                }
            ],
            complete=False,
            graph=build_tool_graph([]),
        )
        self.assertEqual("INCOMPLETE", miss.gates[0].status)

    def test_args_match_absent_fails_any(self):
        # ABSENT only -> FAIL for any
        events = _search_pair_events(args={"other": 1})
        r = self._eval([self._args_exists_any_gate()], graph=build_tool_graph(events))
        self.assertEqual("FAIL", r.gates[0].status)

    def test_args_match_unavailable_incomplete_any(self):
        # UNAVAILABLE only -> INCOMPLETE for any
        events = _search_pair_events()
        r = self._eval([self._args_exists_any_gate()], graph=build_tool_graph(events))
        self.assertEqual("INCOMPLETE", r.gates[0].status)


class E2EReportTests(unittest.TestCase):
    def _write_run_dir(self, td, case_binding=None):
        """Write a minimal complete dual-step run directory; return its path."""
        run = Path(td) / "run1"
        run.mkdir()
        (run / "probes.jsonl").write_text(
            "\n".join(json.dumps(e) for e in synthetic_dual_events()) + "\n",
            encoding="utf-8",
        )
        (run / "probe-completeness.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runId": "run1",
                    "sequenceMax": 3,
                    "enqueued": 4,
                    "written": 4,
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
        (run / "run-meta.json").write_text(
            json.dumps(
                {
                    "runId": "run1",
                    "sessionId": "s",
                    "armId": "default",
                    "comparable": True,
                    "declaredVariables": [],
                    "controlFingerprint": "c",
                    "configFingerprint": "f",
                    "toolsetFingerprint": "t",
                    "buildId": "b",
                    "caseBinding": case_binding or {"casePackId": "none"},
                    "enabledProbePacks": ["bench_core"],
                    "isolation": {"session": "fresh", "memory": "cleared", "cache": "cold"},
                }
            ),
            encoding="utf-8",
        )
        return run

    def test_timing_metrics_from_events(self):
        events = [
            {
                "sequence": 0,
                "stageId": "run.start",
                "elapsedNanos": 0,
                "epochMillis": 1000,
                "attrs": {},
            },
            {
                "sequence": 1,
                "stageId": "model.round.start",
                "elapsedNanos": 5_000_000,
                "epochMillis": 1005,
                "attrs": {"roundIndex": 0},
            },
            {
                "sequence": 2,
                "stageId": "model.round.end",
                "elapsedNanos": 25_000_000,
                "epochMillis": 1025,
                "attrs": {"roundIndex": 0, "status": "ok"},
            },
            {
                "sequence": 3,
                "stageId": "tool.execute.start",
                "elapsedNanos": 30_000_000,
                "epochMillis": 1030,
                "attrs": {"toolName": "t", "callId": "c1"},
            },
            {
                "sequence": 4,
                "stageId": "tool.execute.end",
                "elapsedNanos": 50_000_000,
                "epochMillis": 1050,
                "attrs": {"toolName": "t", "callId": "c1", "ok": True},
            },
        ]
        from bench_analyze.report import compute_timing  # noqa: E402

        timing = compute_timing(events, {"startedAtEpochMillis": 1000})
        self.assertEqual(50, timing["wallMs"])
        self.assertEqual(1, timing["rounds"])
        self.assertEqual(5, timing["ttfbMs"])
        self.assertEqual(20, timing["model"]["median"])
        self.assertEqual(20, timing["tool"]["min"])
        self.assertEqual(20, timing["tool"]["max"])

    def test_timing_falls_back_to_nanos_when_epoch_collapsed(self):
        """Fixed-epoch clocks must not force wallMs=0 when elapsedNanos span is known."""
        events = [
            {
                "sequence": 0,
                "stageId": "run.start",
                "elapsedNanos": 1_000_000,
                "epochMillis": 1_700_000_000_000,
            },
            {
                "sequence": 1,
                "stageId": "model.round.start",
                "elapsedNanos": 4_000_000,
                "epochMillis": 1_700_000_000_000,
                "attrs": {"roundIndex": 0},
            },
            {
                "sequence": 2,
                "stageId": "run.finish",
                "elapsedNanos": 19_000_000,
                "epochMillis": 1_700_000_000_000,
            },
        ]
        from bench_analyze.report import compute_timing  # noqa: E402

        timing = compute_timing(events, {})
        self.assertEqual(18, timing["wallMs"])
        self.assertEqual(3, timing["ttfbMs"])

    def test_case_binding_unbound_rejects_external_pack(self):
        with tempfile.TemporaryDirectory() as td:
            report = analyze_paths(
                [self._write_run_dir(td, case_binding={"casePackId": "none"})],
                case_packs=[FIXTURES / "synthetic.dual_step.v1.json"],
                final_text="ok",
            )
            self.assertEqual("ERROR", report["overallPackStatus"])
            reasons = report["arms"][0]["runs"][0]["incompleteReasons"]
            self.assertTrue(any("case_binding" in r for r in reasons), reasons)

    def test_case_binding_prompt_hash_must_equal_pack(self):
        """Wrong run-meta promptSha256 must ERROR even when casePackId matches (detail-design §828)."""
        from bench_analyze.report import validate_case_binding  # noqa: E402
        from bench_analyze.case_pack import load_case_pack  # noqa: E402

        pack = load_case_pack(FIXTURES / "synthetic.dual_step.v1.json", allow_any_root=True)
        zeros = "0" * 64
        errors = validate_case_binding(
            {
                "caseBinding": {
                    "casePackId": "synthetic.dual_step.v1",
                    "promptSha256": zeros,
                }
            },
            pack,
        )
        self.assertTrue(any("prompt_mismatch" in e for e in errors), errors)

        good = validate_case_binding(
            {
                "caseBinding": {
                    "casePackId": "synthetic.dual_step.v1",
                    "promptSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                }
            },
            pack,
        )
        self.assertEqual([], good)

        with tempfile.TemporaryDirectory() as td:
            report = analyze_paths(
                [
                    self._write_run_dir(
                        td,
                        case_binding={
                            "casePackId": "synthetic.dual_step.v1",
                            "promptSha256": zeros,
                        },
                    )
                ],
                case_packs=[FIXTURES / "synthetic.dual_step.v1.json"],
                final_text="ok",
            )
            self.assertEqual("ERROR", report["overallPackStatus"])

    def test_sample_debug_pack_binding_pass_and_wrong_hash_fail_closed(self):
        """sample.debug.v1 binds on correct hash; wrong hash ERROR; skip if pack absent."""
        from bench_analyze.report import validate_case_binding  # noqa: E402
        from bench_analyze.case_pack import load_case_pack  # noqa: E402

        sample_pack = _sample_debug_case_pack_path()
        if not sample_pack.is_file():
            self.skipTest("needs a host app's debug case pack beside this repository's modules")
        if not sample_pack.is_file():
            self.skipTest("sample Case Pack absent (sample-removal independence window)")
        pack = load_case_pack(sample_pack, allow_any_root=True)
        good_hash = "e3ab5758c78371c5ea5615fcb582c8eddff7d32f751001d1195624a96e729531"
        self.assertEqual(
            [],
            validate_case_binding(_case_binding_meta("sample.debug.v1", good_hash), pack),
        )
        wrong = validate_case_binding(
            _case_binding_meta("sample.debug.v1", "0" * 64),
            pack,
        )
        self.assertTrue(any("prompt_mismatch" in e for e in wrong), wrong)

    def test_case_binding_pack_without_prompt_sha_errors(self):
        from bench_analyze.report import validate_case_binding  # noqa: E402
        from bench_analyze.case_pack import CasePack  # noqa: E402

        pack = CasePack(
            schema_version=1,
            case_pack_id="no_prompt.v1",
            normative=False,
            tool_matchers={"m": {"anyOf": [{"toolNameRegex": "^t$"}]}},
            gates=[],
            prompt=None,
        )
        errors = validate_case_binding(
            {
                "caseBinding": {
                    "casePackId": "no_prompt.v1",
                    "promptSha256": "a" * 64,
                }
            },
            pack,
        )
        self.assertTrue(any("pack_missing_prompt" in e for e in errors), errors)

    def test_no_case_report_path(self):
        with tempfile.TemporaryDirectory() as td:
            report = analyze_paths([self._write_run_dir(td)])
            self.assertEqual(1, report["runCount"])
            self.assertEqual("SKIPPED_NO_CASE", report["overallPackStatus"])
            self.assertFalse(report["businessPassInvented"])
            self.assertEqual(2, report["arms"][0]["runs"][0]["graph"]["nodeCount"])

    def test_case_bound_report_path(self):
        binding = {
            "casePackId": "synthetic.dual_step.v1",
            "promptSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        }
        with tempfile.TemporaryDirectory() as td:
            report = analyze_paths(
                [self._write_run_dir(td, case_binding=binding)],
                case_packs=[FIXTURES / "synthetic.dual_step.v1.json"],
                final_text="ok",
            )
            self.assertEqual("PASS", report["overallPackStatus"])
            self.assertEqual(8, len(report["arms"][0]["runs"][0]["gates"]))

    def test_legacy_dual_read(self):
        with tempfile.TemporaryDirectory() as td:
            run = Path(td) / "legacy"
            run.mkdir()
            (run / "model-calls.jsonl").write_text(
                json.dumps(
                    {
                        "roundIndex": 0,
                        "startedAtEpochMillis": 1,
                        "finishedAtEpochMillis": 2,
                        "status": "ok",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            (run / "session-tool-events.jsonl").write_text(
                "\n".join(
                    [
                        json.dumps(
                            {
                                "toolName": "mcp__x__search",
                                "toolCallId": "c1",
                                "phase": "start",
                                "roundIndex": 0,
                            }
                        ),
                        json.dumps(
                            {
                                "toolName": "mcp__x__search",
                                "toolCallId": "c1",
                                "phase": "end",
                                "ok": True,
                                "roundIndex": 0,
                            }
                        ),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            analysis = analyze_run(run)
            self.assertIn("legacy", analysis.sources[0])
            self.assertFalse(analysis.completeness.get("complete"))
            self.assertTrue(analysis.graph["nodeCount"] >= 1)

    def test_multi_arm_separation(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            for arm, provider_fp in (("a", "fp-a"), ("b", "fp-b")):
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
                (run / "run-meta.json").write_text(
                    json.dumps(
                        {
                            "runId": f"run-{arm}",
                            "sessionId": "s",
                            "armId": arm,
                            "comparable": True,
                            "declaredVariables": [],
                            "controlFingerprint": provider_fp,
                            "configFingerprint": provider_fp,
                            "toolsetFingerprint": "t",
                            "buildId": "b",
                            "caseBinding": {"casePackId": "none"},
                            "enabledProbePacks": [],
                            "isolation": {"session": "fresh", "memory": "cleared", "cache": "cold"},
                        }
                    ),
                    encoding="utf-8",
                )
            report = analyze_paths([root])
            self.assertTrue(report["crossArmSeparationReasons"])


class DualPackAntiOverfit(unittest.TestCase):
    def test_two_packs_different_outcomes(self):
        """Same graph, two packs must yield different gate outcomes (anti-overfit)."""
        graph = build_tool_graph(synthetic_dual_events())
        dual = load_case_pack(FIXTURES / "synthetic.dual_step.v1.json")
        alt = load_case_pack(FIXTURES / "synthetic.alt_matchers.v1.json")
        r1 = evaluate_pack(
            dual, graph, completeness_complete=True, enabled_probe_packs=["bench_core"], final_text="ok"
        )
        r2 = evaluate_pack(alt, graph, completeness_complete=True, enabled_probe_packs=["bench_core"])
        self.assertEqual("PASS", r1.status)
        # alt requires a tool name that is not on the dual graph → FAIL (not same PASS)
        self.assertEqual("FAIL", r2.status)
        self.assertNotEqual(r1.status, r2.status)
        self.assertEqual(1, len(r2.gates))
        self.assertEqual(8, len(r1.gates))

    def test_bad_pack_unknown_field_errors(self):
        with self.assertRaises(CasePackError):
            parse_case_pack(
                {
                    "schemaVersion": 1,
                    "casePackId": "x",
                    "normative": False,
                    "toolMatchers": {"a": {"anyOf": [{"toolNameRegex": "^x$"}]}},
                    "gates": [],
                    "evilScript": "nope",
                }
            )


if __name__ == "__main__":
    unittest.main()
