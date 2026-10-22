#!/usr/bin/env python3
"""Deterministic A2A HTTP server used by the cancel-race smoke runner."""

from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


class FaultState:
    """Thread-safe observations emitted as machine-verifiable evidence."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.request_started = threading.Event()
        self.release_response = threading.Event()
        self.client_disconnected = False
        self.response_attempted = False
        self.business_response_written = False

    def record_disconnect(self) -> None:
        with self._lock:
            self.client_disconnected = True

    def record_business_response(self) -> None:
        with self._lock:
            self.business_response_written = True

    def record_response_attempt(self) -> None:
        with self._lock:
            self.response_attempted = True

    def snapshot(self) -> dict[str, bool]:
        with self._lock:
            return {
                "request_started": self.request_started.is_set(),
                "client_disconnected": self.client_disconnected,
                "response_attempted": self.response_attempted,
                "business_response_written": self.business_response_written,
            }


class FaultServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], result_file: Path | None) -> None:
        super().__init__(address, FaultHandler)
        self.state = FaultState()
        self.result_file = result_file

    def write_result(self) -> None:
        if self.result_file is not None:
            self.result_file.write_text(json.dumps(self.state.snapshot(), sort_keys=True), encoding="utf-8")


class FaultHandler(BaseHTTPRequestHandler):
    server: FaultServer

    def do_GET(self) -> None:
        if self.path == "/.well-known/agent-card.json":
            self._json_response(
                {
                    "name": "deterministic-fault-agent",
                    "description": "Project-owned A2A fault oracle",
                    "url": self._origin() + "/rpc",
                    "protocolVersion": "1.0",
                    "capabilities": {"streaming": True},
                }
            )
            return
        if self.path == "/state":
            self._json_response(self.server.state.snapshot())
            return
        if self.path == "/release":
            self.server.state.release_response.set()
            self._json_response({"released": True})
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if self.path != "/rpc":
            self.send_error(404)
            return
        content_length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(content_length))
        if request.get("method") != "message/send":
            self._rpc_error(request.get("id"), -32601)
            return
        self.server.state.request_started.set()
        self.server.write_result()
        self.server.state.release_response.wait(timeout=5)
        self.server.state.record_response_attempt()
        envelope = {
            "jsonrpc": "2.0",
            "id": request.get("id"),
            "result": {
                "kind": "message",
                "messageId": "late-response",
                "role": "agent",
                "parts": [{"kind": "text", "text": "must-not-be-accepted"}],
            },
        }
        try:
            self._json_response(envelope)
            self.server.state.record_business_response()
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            self.server.state.record_disconnect()
        finally:
            self.server.write_result()

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _origin(self) -> str:
        host, port = self.server.server_address
        return f"http://{host}:{port}"

    def _json_response(self, value: object) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def _rpc_error(self, request_id: object, code: int) -> None:
        self._json_response({"jsonrpc": "2.0", "id": request_id, "error": {"code": code}})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--ready-file", type=Path)
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    server = FaultServer((args.host, args.port), args.result_file)
    host, port = server.server_address
    if args.ready_file is not None:
        args.ready_file.write_text(json.dumps({"host": host, "port": port}), encoding="utf-8")
    try:
        server.serve_forever(poll_interval=0.05)
    except KeyboardInterrupt:
        pass
    finally:
        server.state.release_response.set()
        server.server_close()
        server.write_result()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
