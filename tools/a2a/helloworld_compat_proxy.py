#!/usr/bin/env python3
"""Translate the project's Phase-0 direct wire into official A2A 1.0 JSON-RPC."""

from __future__ import annotations

import argparse
import json
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterable


class ProxyServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], upstream: str) -> None:
        super().__init__(address, ProxyHandler)
        self.upstream = upstream.rstrip("/") + "/"


class ProxyHandler(BaseHTTPRequestHandler):
    server: ProxyServer

    def do_GET(self) -> None:
        if self.path != "/.well-known/agent-card.json":
            self.send_error(404)
            return
        card = self._upstream_json(".well-known/agent-card.json")
        interface = card["supportedInterfaces"][0]
        self._json_response(
            {
                "name": card["name"],
                "description": card.get("description"),
                "url": self._origin() + "/rpc",
                "protocolVersion": interface.get("protocolVersion"),
                "capabilities": {"streaming": bool(card.get("capabilities", {}).get("streaming"))},
            }
        )

    def do_POST(self) -> None:
        if self.path != "/rpc":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        direct = json.loads(self.rfile.read(length))
        official = self._official_request(direct)
        streaming = direct.get("method") == "message/stream"
        request = urllib.request.Request(
            self.server.upstream,
            data=json.dumps(official).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "text/event-stream" if streaming else "application/json",
                "A2A-Version": "1.0",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            if streaming:
                self._stream_response(response, direct["id"])
            else:
                envelope = json.load(response)
                self._json_response(self._direct_envelope(envelope, direct["id"]))

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _official_request(self, direct: dict[str, Any]) -> dict[str, Any]:
        methods = {"message/send": "SendMessage", "message/stream": "SendStreamingMessage"}
        method = methods.get(direct.get("method"))
        if method is None:
            raise ValueError("unsupported direct method")
        message = dict(direct["params"]["message"])
        message["role"] = "ROLE_" + str(message.get("role", "user")).upper()
        message["parts"] = [self._official_part(part) for part in message.get("parts", [])]
        return {"jsonrpc": "2.0", "id": direct["id"], "method": method, "params": {"message": message}}

    def _stream_response(self, response: Any, request_id: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for data in self._sse_data(response):
            envelope = self._direct_envelope(json.loads(data), request_id)
            result = envelope.get("result")
            if result is None:
                continue
            frame = f"data: {json.dumps(envelope, separators=(',', ':'))}\n\n".encode("utf-8")
            self.wfile.write(frame)
            self.wfile.flush()

    def _direct_envelope(self, envelope: dict[str, Any], request_id: str) -> dict[str, Any]:
        if "error" in envelope:
            return {"jsonrpc": "2.0", "id": request_id, "error": envelope["error"]}
        result = envelope.get("result", {})
        if "task" in result:
            payload = self._direct_task(result["task"])
        elif "statusUpdate" in result:
            update = result["statusUpdate"]
            payload = {
                "taskId": update["taskId"],
                "contextId": update.get("contextId"),
                "status": self._direct_status(update["status"]),
                "final": self._is_terminal(update["status"].get("state")),
            }
        else:
            return {"jsonrpc": "2.0", "id": request_id}
        return {"jsonrpc": "2.0", "id": request_id, "result": payload}

    def _direct_task(self, task: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": task["id"],
            "contextId": task.get("contextId"),
            "status": self._direct_status(task["status"]),
        }

    def _direct_status(self, status: dict[str, Any]) -> dict[str, Any]:
        value: dict[str, Any] = {"state": self._state_name(status.get("state"))}
        if "message" in status:
            value["message"] = self._direct_message(status["message"])
        return value

    def _direct_message(self, message: dict[str, Any]) -> dict[str, Any]:
        value = dict(message)
        value["role"] = str(value.get("role", "ROLE_USER")).removeprefix("ROLE_").lower()
        value["parts"] = [self._direct_part(part) for part in value.get("parts", [])]
        return value

    def _official_part(self, part: dict[str, Any]) -> dict[str, Any]:
        if part.get("kind") == "data":
            return {"data": part.get("data")}
        return {"text": part.get("text", "")}

    def _direct_part(self, part: dict[str, Any]) -> dict[str, Any]:
        if "data" in part:
            return {"kind": "data", "data": part["data"]}
        return {"kind": "text", "text": part.get("text", "")}

    def _sse_data(self, response: Any) -> Iterable[str]:
        for raw_line in response:
            line = raw_line.decode("utf-8").rstrip("\r\n")
            if line.startswith("data:"):
                yield line.removeprefix("data:").lstrip()

    def _state_name(self, value: object) -> str:
        return str(value or "TASK_STATE_UNSPECIFIED").removeprefix("TASK_STATE_").lower()

    def _is_terminal(self, value: object) -> bool:
        return self._state_name(value) in {"completed", "canceled", "failed", "rejected"}

    def _origin(self) -> str:
        host, port = self.server.server_address
        return f"http://{host}:{port}"

    def _upstream_json(self, path: str) -> dict[str, Any]:
        with urllib.request.urlopen(self.server.upstream + path, timeout=10) as response:
            return json.load(response)

    def _json_response(self, value: object) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--ready-file", type=Path, required=True)
    args = parser.parse_args()
    server = ProxyServer((args.host, args.port), args.upstream)
    host, port = server.server_address
    args.ready_file.write_text(json.dumps({"host": host, "port": port}), encoding="utf-8")
    try:
        server.serve_forever(poll_interval=0.05)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
