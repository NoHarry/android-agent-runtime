#!/usr/bin/env python3
"""Shared process and Gradle orchestration for A2A reference integrations."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any


TOOLS_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = TOOLS_DIR.parent.parent
REPO_ROOT = ANDROID_ROOT
LOCK_FILE = TOOLS_DIR / "reference.lock.json"
INIT_SCRIPT = TOOLS_DIR / "reference-integration.init.gradle"
RAW_ROOT = "https://raw.githubusercontent.com/a2aproject/a2a-samples/{commit}/{sample_path}/{name}"


def run_fault_smoke() -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="a2a-fault-") as temporary:
        root = Path(temporary)
        ready = root / "ready.json"
        evidence = root / "server-evidence.json"
        server = start_process(
            [sys.executable, str(TOOLS_DIR / "fault_server.py"), "--ready-file", str(ready), "--result-file", str(evidence)],
            root / "fault-server.log",
        )
        try:
            endpoint = wait_ready_file(ready)
            run_gradle_test(
                endpoint,
                "cc.noharry.agent.a2a.integration.ReferenceIntegrationSmokeTest.deterministicCancelRaceRejectsLateBusinessResponse",
            )
            state = json.loads(evidence.read_text(encoding="utf-8"))
            expected = state.get("request_started") and state.get("response_attempted")
            if not expected:
                raise RuntimeError(f"cancel-race evidence failed: {state}")
            return {"status": "passed", "integration": "deterministic-fault-server", "evidence": state}
        finally:
            stop_process(server)


def run_helloworld_smoke() -> dict[str, Any]:
    lock = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="a2a-helloworld-") as temporary:
        root = Path(temporary)
        sample = root / "helloworld"
        sample.mkdir()
        download_sample(lock, sample)
        python = install_sample(sample, root)
        upstream = start_process([str(python), "__main__.py"], root / "helloworld.log", cwd=sample)
        proxy_ready = root / "proxy-ready.json"
        proxy = None
        try:
            wait_http(f"http://127.0.0.1:{lock['port']}/.well-known/agent-card.json")
            proxy = start_process(
                [
                    sys.executable,
                    str(TOOLS_DIR / "helloworld_compat_proxy.py"),
                    "--upstream",
                    f"http://127.0.0.1:{lock['port']}",
                    "--ready-file",
                    str(proxy_ready),
                ],
                root / "proxy.log",
            )
            endpoint = wait_ready_file(proxy_ready)
            run_gradle_test(
                endpoint,
                "cc.noharry.agent.a2a.integration.ReferenceIntegrationSmokeTest.officialHelloWorldCardSyncAndStream",
            )
            return {
                "status": "passed",
                "integration": "official-python-helloworld",
                "repository": lock["repository"],
                "commit": lock["commit"],
            }
        finally:
            if proxy is not None:
                stop_process(proxy)
            stop_process(upstream)


def emit_result(result: dict[str, Any], result_file: Path | None) -> int:
    serialized = json.dumps(result, sort_keys=True, separators=(",", ":"))
    if result_file is not None:
        result_file.parent.mkdir(parents=True, exist_ok=True)
        result_file.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0 if result.get("status") == "passed" else 1


def failed_result(integration: str, error: BaseException) -> dict[str, Any]:
    return {"status": "failed", "integration": integration, "error": type(error).__name__}


def download_sample(lock: dict[str, Any], destination: Path) -> None:
    for name in ("__main__.py", "agent_executor.py", "requirements.txt"):
        url = RAW_ROOT.format(commit=lock["commit"], sample_path=lock["sample_path"], name=name)
        with urllib.request.urlopen(url, timeout=30) as response:
            destination.joinpath(name).write_bytes(response.read())


def install_sample(sample: Path, root: Path) -> Path:
    environment = root / ".venv"
    run_checked(["uv", "venv", str(environment), "--python", sys.executable], cwd=root)
    python = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    run_checked(["uv", "pip", "install", "--python", str(python), "-r", str(sample / "requirements.txt")], cwd=root)
    return python


def run_gradle_test(base_url: str, test_name: str) -> None:
    wrapper = ANDROID_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [
        str(wrapper),
        "-I",
        str(INIT_SCRIPT),
        ":agent-a2a-client-direct:testDebugUnitTest",
        "--tests",
        test_name,
        f"-Da2a.reference.baseUrl={base_url}",
        "--no-daemon",
        "--console=plain",
    ]
    run_checked(command, cwd=ANDROID_ROOT)


def start_process(command: list[str], log_file: Path, cwd: Path | None = None) -> subprocess.Popen[bytes]:
    log = log_file.open("wb")
    process = subprocess.Popen(command, cwd=cwd or REPO_ROOT, stdout=log, stderr=subprocess.STDOUT)
    log.close()
    return process


def stop_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def wait_ready_file(path: Path) -> str:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if path.is_file():
            value = json.loads(path.read_text(encoding="utf-8"))
            return f"http://{value['host']}:{value['port']}"
        time.sleep(0.05)
    raise TimeoutError(f"server readiness file missing: {path.name}")


def wait_http(url: str) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except OSError:
            time.sleep(0.1)
    raise TimeoutError("official HelloWorld server did not become ready")


def run_checked(command: list[str], cwd: Path) -> None:
    completed = subprocess.run(command, cwd=cwd, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"command failed with exit {completed.returncode}: {command[0]}")
