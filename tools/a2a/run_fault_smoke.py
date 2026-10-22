#!/usr/bin/env python3
"""Run the deterministic A2A cancel-race integration."""

from __future__ import annotations

import argparse
from pathlib import Path

from reference_runner import emit_result, failed_result, run_fault_smoke


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    try:
        result = run_fault_smoke()
    except BaseException as error:
        result = failed_result("deterministic-fault-server", error)
    return emit_result(result, args.result_file)


if __name__ == "__main__":
    raise SystemExit(main())
