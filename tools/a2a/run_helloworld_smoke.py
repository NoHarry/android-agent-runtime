#!/usr/bin/env python3
"""Run the pinned official Python HelloWorld integration."""

from __future__ import annotations

import argparse
from pathlib import Path

from reference_runner import emit_result, failed_result, run_helloworld_smoke


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    try:
        result = run_helloworld_smoke()
    except BaseException as error:
        result = failed_result("official-python-helloworld", error)
    return emit_result(result, args.result_file)


if __name__ == "__main__":
    raise SystemExit(main())
