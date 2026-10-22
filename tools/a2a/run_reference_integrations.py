#!/usr/bin/env python3
"""Run every required outbound A2A reference integration."""

from __future__ import annotations

import argparse
from pathlib import Path

from reference_runner import emit_result, failed_result, run_fault_smoke, run_helloworld_smoke


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    results = []
    for integration, runner in (
        ("deterministic-fault-server", run_fault_smoke),
        ("official-python-helloworld", run_helloworld_smoke),
    ):
        try:
            results.append(runner())
        except BaseException as error:
            results.append(failed_result(integration, error))
    status = "passed" if all(item["status"] == "passed" for item in results) else "failed"
    return emit_result({"status": status, "integrations": results}, args.result_file)


if __name__ == "__main__":
    raise SystemExit(main())
