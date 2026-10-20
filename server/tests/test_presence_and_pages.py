from __future__ import annotations

import json
import time

import pytest
from websockets.exceptions import InvalidStatus

from conftest import recv_type


def test_presence_changes_broadcast_on_connect_and_disconnect(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    with server.ws(guardian_token) as guardian_ws:
        # On connect the guardian gets the current state of everyone it protects.
        status = recv_type(guardian_ws, "member_status")
        assert status["member_id"] == member_id and status["online"] is False

        with server.ws(member_token):
            status = recv_type(guardian_ws, "member_status")
            assert status["online"] is True
            assert server.get("/api/guardian/members", guardian_token).json()["members"][0]["online"] is True

        status = recv_type(guardian_ws, "member_status")
        assert status["online"] is False
        assert status["last_seen"] is not None


def test_silent_member_is_asked_to_ping_then_marked_offline(server):
    _, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    with server.ws(guardian_token) as guardian_ws:
        assert recv_type(guardian_ws, "member_status")["online"] is False
        with server.ws(member_token) as member_ws:
            assert recv_type(guardian_ws, "member_status")["online"] is True
            # Answering ping_request with a ping keeps the member online.
            recv_type(member_ws, "ping_request", timeout=3)
            member_ws.send(json.dumps({"type": "ping"}))
            assert recv_type(member_ws, "pong")["time"] > 0
            # Now the member goes silent: offline after offline_after_seconds (3 s in tests),
            # while the guardian (which keeps answering) stays connected.
            started = time.monotonic()
            status = recv_type(guardian_ws, "member_status", timeout=6)
            assert status["online"] is False
            assert time.monotonic() - started < 5


def test_permission_health_change_is_broadcast(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)
    with server.ws(guardian_token) as guardian_ws:
        recv_type(guardian_ws, "member_status")
        health = {"notifications": True, "phone": True, "call_screening": True, "usage_access": False}
        r = server.post("/api/member/status", member_token, {"permission_health": health, "app_version": "1.0"})
        assert r.status_code == 200
        status = recv_type(guardian_ws, "member_status")
        assert status["member_id"] == member_id
        assert status["permission_health"]["usage_access"] is False
        assert status["app_version"] == "1.0"
    me = server.get("/api/member/me", member_token).json()
    assert me["permission_health"]["usage_access"] is False


def test_websocket_rejects_bad_token(server):
    with pytest.raises(InvalidStatus) as info:
        with server.ws("nope"):
            pass
    assert info.value.response.status_code == 403


def test_download_page_and_apks(server):
    page = server.get("/")
    assert page.status_code == 200
    assert "Download Backup" in page.text and "Download Backup Circle" in page.text
    assert "scripts/build_apks.py" in page.text  # nothing built yet
    assert server.get("/download/member.apk").status_code == 404

    server.config.dist_dir.mkdir(parents=True, exist_ok=True)
    (server.config.dist_dir / "member.apk").write_bytes(b"PK\x03\x04fake-apk")
    r = server.get("/download/member.apk")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert "Backup.apk" in r.headers["content-disposition"]
    assert r.content == b"PK\x03\x04fake-apk"
    assert 'href="/download/member.apk"' in server.get("/").text
    assert server.get("/download/other.apk").status_code == 404
