"""Runs the real app under uvicorn on a free port, so tests exercise the same
HTTP + WebSocket stack the phones use on stage."""
from __future__ import annotations

import json
import socket
import sys
import threading
import time
from pathlib import Path
from typing import Any, Optional

import httpx
import pytest
import uvicorn
from websockets.sync.client import ClientConnection, connect

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from backup_server.app import create_app  # noqa: E402
from backup_server.config import ServerConfig  # noqa: E402


class FakeClock:
    """Real time plus a manual offset, so codes can expire without waiting 10 minutes."""

    def __init__(self) -> None:
        self.offset = 0.0

    def __call__(self) -> float:
        return time.time() + self.offset

    def advance(self, seconds: float) -> None:
        self.offset += seconds


class LiveServer:
    def __init__(self, port: int, clock: FakeClock, config: ServerConfig):
        self.base = f"http://127.0.0.1:{port}"
        self.ws_base = f"ws://127.0.0.1:{port}"
        self.clock = clock
        self.config = config
        self.http = httpx.Client(base_url=self.base, timeout=5)

    def get(self, path: str, token: Optional[str] = None, **kw: Any) -> httpx.Response:
        return self.http.get(path, headers=_auth(token), **kw)

    def post(self, path: str, token: Optional[str] = None, json_body: Any = None) -> httpx.Response:
        return self.http.post(path, headers=_auth(token), json=json_body)

    def patch(self, path: str, token: Optional[str] = None, json_body: Any = None) -> httpx.Response:
        return self.http.patch(path, headers=_auth(token), json=json_body)

    def delete(self, path: str, token: Optional[str] = None) -> httpx.Response:
        return self.http.delete(path, headers=_auth(token))

    def ws(self, token: str) -> ClientConnection:
        return connect(f"{self.ws_base}/ws?token={token}", open_timeout=5)

    # -- scenario helpers ------------------------------------------------
    def register_member(self) -> tuple[str, str]:
        r = self.post("/api/member/register", json_body={"app_version": "test"})
        assert r.status_code == 200, r.text
        return r.json()["member_id"], r.json()["token"]

    def register_guardian(self, name: str = "Rohan") -> tuple[str, str]:
        r = self.post("/api/guardian/register", json_body={"name": name})
        assert r.status_code == 200, r.text
        return r.json()["guardian_id"], r.json()["token"]

    def issue_code(self, member_token: str) -> str:
        r = self.post("/api/member/code", member_token)
        assert r.status_code == 200, r.text
        return r.json()["code"]

    def pair(self, guardian_token: str, member_token: str) -> str:
        code = self.issue_code(member_token)
        r = self.post("/api/guardian/pair", guardian_token, {"code": code})
        assert r.status_code == 200, r.text
        return r.json()["member"]["member_id"]

    def send_event(self, member_token: str, event_type: str, payload: dict[str, Any],
                   client_event_id: Optional[str] = None) -> httpx.Response:
        now = int(time.time() * 1000)
        return self.post(
            "/api/member/events",
            member_token,
            {
                "client_event_id": client_event_id or f"{event_type}-{time.time_ns()}",
                "type": event_type,
                "occurred_at": now,
                "sent_at": now,
                "payload": payload,
            },
        )


def _auth(token: Optional[str]) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"} if token else {}


def recv_type(ws: ClientConnection, wanted: str, timeout: float = 5.0) -> dict[str, Any]:
    """Read messages until one of the wanted type arrives (skipping pongs and the like).
    Like the real apps, it answers the server's ping_request while waiting."""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AssertionError(f"no '{wanted}' message within {timeout} s")
        message = json.loads(ws.recv(timeout=remaining))
        if message.get("type") == wanted:
            return message
        if message.get("type") == "ping_request":
            ws.send(json.dumps({"type": "ping"}))


def assert_silent(ws: ClientConnection, wanted: str, seconds: float) -> None:
    deadline = time.monotonic() + seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        try:
            message = json.loads(ws.recv(timeout=remaining))
        except TimeoutError:
            return
        assert message.get("type") != wanted, f"unexpected {wanted}: {message}"


CALL = {"call_id": "c1", "masked_number": "••••3210", "elapsed_seconds": 0}
ALERT = {
    "call_id": "c1",
    "level": "high",
    "app_package": "com.phonepe.app",
    "app_label": "PhonePe",
    "call_duration_seconds": 312,
    "call_active": True,
    "masked_number": "••••3210",
}


@pytest.fixture
def server(tmp_path: Path):
    clock = FakeClock()
    config = ServerConfig(
        db_path=tmp_path / "test.db",
        dist_dir=tmp_path / "dist",
        clock=clock,
        log_events=False,
        offline_after_seconds=3.0,
        ping_request_after_seconds=1.5,
        presence_check_interval=0.2,
    )
    app = create_app(config)
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    uv = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="on"))
    thread = threading.Thread(target=uv.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not uv.started:
        if time.monotonic() > deadline or not thread.is_alive():
            raise RuntimeError("test server did not start")
        time.sleep(0.05)
    live = LiveServer(port, clock, config)
    yield live
    live.http.close()
    uv.should_exit = True
    thread.join(timeout=10)
