from __future__ import annotations

from conftest import recv_type


def test_register_both_roles(server):
    member_id, member_token = server.register_member()
    guardian_id, guardian_token = server.register_guardian("  Rohan  ")
    assert member_id and member_token and guardian_id and guardian_token

    me = server.get("/api/member/me", member_token).json()
    assert me["member_id"] == member_id
    assert me["guardians"] == []

    members = server.get("/api/guardian/members", guardian_token).json()
    assert members["name"] == "Rohan"  # whitespace trimmed
    assert members["members"] == []


def test_auth_is_required(server):
    for path in ("/api/member/me", "/api/guardian/members"):
        r = server.get(path)
        assert r.status_code == 401
        assert r.json()["error"] == "invalid_token"
    assert server.get("/api/member/me", "not-a-real-token").status_code == 401
    assert server.post("/api/guardian/register", json_body={"name": "   "}).status_code == 400
    assert server.get("/health").json()["ok"] is True


def test_code_issue_expiry_single_use_and_invalidation(server):
    _, member_token = server.register_member()
    _, guardian_token = server.register_guardian()

    r = server.post("/api/member/code", member_token).json()
    first = r["code"]
    assert len(first) == 6 and first.isdigit()
    assert r["expires_in_seconds"] == 600

    # A new code invalidates the previous unused one.
    second = server.issue_code(member_token)
    r = server.post("/api/guardian/pair", guardian_token, {"code": first})
    assert r.status_code == 410 and r.json()["error"] == "code_expired"

    # The new code works, once. Spaces typed by the guardian are ignored.
    r = server.post("/api/guardian/pair", guardian_token, {"code": f"{second[:3]} {second[3:]}"})
    assert r.status_code == 200, r.text
    r = server.post("/api/guardian/pair", guardian_token, {"code": second})
    assert r.status_code == 409 and r.json()["error"] == "code_used"

    # Codes expire after 10 minutes.
    third = server.issue_code(member_token)
    server.clock.advance(601)
    r = server.post("/api/guardian/pair", guardian_token, {"code": third})
    assert r.status_code == 410 and r.json()["error"] == "code_expired"


def test_pairing_errors_and_lockout(server):
    _, member_token = server.register_member()
    _, guardian_token = server.register_guardian()

    r = server.post("/api/guardian/pair", guardian_token, {"code": "12a"})
    assert r.status_code == 400 and r.json()["error"] == "invalid_code_format"

    good = server.issue_code(member_token)
    wrong = "000000" if good != "000000" else "111111"
    for _ in range(5):
        r = server.post("/api/guardian/pair", guardian_token, {"code": wrong})
        assert r.status_code == 404 and r.json()["error"] == "code_not_found"
        assert "message" in r.json()

    # Locked out for 60 s, even with the right code.
    r = server.post("/api/guardian/pair", guardian_token, {"code": good})
    assert r.status_code == 429 and r.json()["error"] == "too_many_attempts"
    assert r.json()["retry_after_seconds"] > 0

    # Another guardian is not affected.
    _, other_token = server.register_guardian("Priya")
    other_code_owner_token = server.register_member()[1]
    assert server.post("/api/guardian/pair", other_token, {"code": server.issue_code(other_code_owner_token)}).status_code == 200

    server.clock.advance(61)
    r = server.post("/api/guardian/pair", guardian_token, {"code": good})
    assert r.status_code == 200, r.text


def test_many_to_many_links_and_naming(server):
    member_a, token_a = server.register_member()
    member_b, token_b = server.register_member()
    _, rohan = server.register_guardian("Rohan")
    _, priya = server.register_guardian("Priya")

    assert server.pair(rohan, token_a) == member_a
    assert server.pair(rohan, token_b) == member_b
    assert server.pair(priya, token_a) == member_a

    r = server.patch(f"/api/guardian/members/{member_a}", rohan, {"display_name": "Papa", "phone": "+91 98765 43210"})
    assert r.status_code == 200
    assert r.json()["display_name"] == "Papa" and r.json()["phone"] == "+91 98765 43210"
    # Names are per guardian.
    r = server.patch(f"/api/guardian/members/{member_a}", priya, {"display_name": "Dada"})
    assert r.json()["display_name"] == "Dada" and r.json()["phone"] is None

    me = server.get("/api/member/me", token_a).json()
    assert sorted(g["name"] for g in me["guardians"]) == ["Priya", "Rohan"]
    assert len(server.get("/api/guardian/members", rohan).json()["members"]) == 2

    # Unknown member -> 404
    assert server.patch("/api/guardian/members/nope", rohan, {"display_name": "X"}).status_code == 404


def test_pair_and_unpair_notify_the_member(server):
    member_id, member_token = server.register_member()
    _, guardian_token = server.register_guardian("Rohan")
    with server.ws(member_token) as member_ws:
        code = server.issue_code(member_token)
        assert server.post("/api/guardian/pair", guardian_token, {"code": code}).status_code == 200
        paired = recv_type(member_ws, "paired")
        assert paired["guardian_name"] == "Rohan"

        assert server.delete(f"/api/guardian/members/{member_id}", guardian_token).json() == {"ok": True}
        unpaired = recv_type(member_ws, "unpaired")
        assert unpaired["guardian_name"] == "Rohan"
    assert server.get("/api/member/me", member_token).json()["guardians"] == []
