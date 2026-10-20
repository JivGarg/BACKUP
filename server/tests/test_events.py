from __future__ import annotations

import time

from conftest import ALERT, CALL, assert_silent, recv_type


def test_event_idempotency(server):
    _, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    first = server.send_event(member_token, "call_started", CALL, client_event_id="evt-1")
    assert first.status_code == 200, first.text
    assert first.json()["duplicate"] is False
    again = server.send_event(member_token, "call_started", CALL, client_event_id="evt-1")
    assert again.json() == {"event_id": first.json()["event_id"], "duplicate": True}

    events = server.get("/api/guardian/events", guardian_token).json()["events"]
    assert [e["client_event_id"] for e in events] == ["evt-1"]


def test_event_validation(server):
    _, member_token = server.register_member()
    r = server.send_event(member_token, "sms_read", {})
    assert r.status_code == 400 and r.json()["error"] == "unknown_event_type"
    r = server.send_event(member_token, "risk_alert", {**ALERT, "level": "extreme"})
    assert r.status_code == 400 and r.json()["error"] == "invalid_payload"
    r = server.send_event(member_token, "call_ended", {"call_id": "c1"})
    assert r.status_code == 400
    assert server.send_event("bad-token", "call_ended", {"call_id": "c1", "duration_seconds": 3}).status_code == 401


def test_guardian_websocket_receives_member_event_in_real_time(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    with server.ws(guardian_token) as guardian_ws:
        sent = time.monotonic()
        assert server.send_event(member_token, "call_started", CALL).status_code == 200
        message = recv_type(guardian_ws, "event", timeout=3)
        assert time.monotonic() - sent < 1.0
        event = message["event"]
        assert event["member_id"] == member_id
        assert event["type"] == "call_started"
        assert event["payload"]["masked_number"] == "••••3210"
        assert event["resolved"] is False


def test_unlinked_guardian_gets_nothing(server):
    _, member_token = server.register_member()
    _, stranger = server.register_guardian("Stranger")
    with server.ws(stranger) as ws:
        server.send_event(member_token, "call_started", CALL)
        assert_silent(ws, "event", 1.0)
    assert server.get("/api/guardian/events", stranger).json()["events"] == []


def test_missed_events_are_fetched_by_after_id(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    ids = []
    for kind, payload in (
        ("call_started", CALL),
        ("long_unknown_call", {"call_id": "c1", "elapsed_seconds": 300, "threshold_seconds": 300}),
        ("risk_alert", ALERT),
        ("call_ended", {"call_id": "c1", "duration_seconds": 400}),
    ):
        ids.append(server.send_event(member_token, kind, payload).json()["event_id"])

    missed = server.get("/api/guardian/events", guardian_token, params={"after_id": ids[1]}).json()["events"]
    assert [e["id"] for e in missed] == ids[2:]
    assert [e["type"] for e in missed] == ["risk_alert", "call_ended"]
    only = server.get("/api/guardian/events", guardian_token, params={"member_id": member_id, "after_id": 0}).json()
    assert len(only["events"]) == 4 and "server_time" in only


def test_resolve_broadcasts_to_all_linked_guardians(server):
    member_id, member_token = server.register_member()
    _, rohan = server.register_guardian("Rohan")
    _, priya = server.register_guardian("Priya")
    server.pair(rohan, member_token)
    server.pair(priya, member_token)

    alert_id = server.send_event(member_token, "risk_alert", ALERT).json()["event_id"]
    call_id = server.send_event(member_token, "call_started", {**CALL, "call_id": "c2"}).json()["event_id"]

    with server.ws(rohan) as rohan_ws, server.ws(priya) as priya_ws:
        r = server.post(f"/api/guardian/events/{alert_id}/resolve", rohan, {"resolution": "safe"})
        assert r.status_code == 200, r.text
        assert r.json()["resolved_by_name"] == "Rohan"
        for ws in (rohan_ws, priya_ws):
            message = recv_type(ws, "event_resolved")
            assert message["event_id"] == alert_id
            assert message["resolution"] == "safe"
            assert message["resolver_name"] == "Rohan"

        # Second tap (from the other guardian) keeps the first resolution, no new broadcast.
        r = server.post(f"/api/guardian/events/{alert_id}/resolve", priya, {"resolution": "called"})
        assert r.json()["resolution"] == "safe"
        assert_silent(rohan_ws, "event_resolved", 0.5)

    events = {e["id"]: e for e in server.get("/api/guardian/events", priya).json()["events"]}
    assert events[alert_id]["resolved"] is True
    assert events[alert_id]["resolved_by_name"] == "Rohan"
    assert server.post(f"/api/guardian/events/{call_id}/resolve", rohan, {"resolution": "safe"}).status_code == 400
    assert server.post(f"/api/guardian/events/{alert_id}/resolve", rohan, {"resolution": "maybe"}).status_code == 400


def test_dashboard_shows_active_call_and_open_alerts(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)

    server.send_event(member_token, "call_started", {**CALL, "elapsed_seconds": 42})
    member = server.get("/api/guardian/members", guardian_token).json()["members"][0]
    assert member["active_call"]["masked_number"] == "••••3210"
    assert 42 <= member["active_call"]["elapsed_seconds"] <= 44
    assert member["open_alerts"] == 0

    alert_id = server.send_event(member_token, "risk_alert", ALERT).json()["event_id"]
    assert server.get("/api/guardian/members", guardian_token).json()["members"][0]["open_alerts"] == 1

    server.send_event(member_token, "call_ended", {"call_id": "c1", "duration_seconds": 400})
    server.post(f"/api/guardian/events/{alert_id}/resolve", guardian_token, {"resolution": "called"})
    member = server.get("/api/guardian/members", guardian_token).json()["members"][0]
    assert member["active_call"] is None
    assert member["open_alerts"] == 0


def test_member_clock_skew_is_corrected(server):
    _, member_token = server.register_member()
    _, guardian_token = server.register_guardian()
    server.pair(guardian_token, member_token)
    skew = 3_600_000  # member phone is an hour fast
    now = int(time.time() * 1000)
    r = server.post(
        "/api/member/events",
        member_token,
        {
            "client_event_id": "skewed",
            "type": "call_ended",
            "occurred_at": now + skew - 5000,  # happened 5 s before it was sent
            "sent_at": now + skew,
            "payload": {"call_id": "c1", "duration_seconds": 30},
        },
    )
    assert r.status_code == 200
    event = server.get("/api/guardian/events", guardian_token).json()["events"][0]
    assert abs((event["received_at"] - event["occurred_at"]) - 5000) < 1500
