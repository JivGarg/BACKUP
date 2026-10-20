#!/usr/bin/env python3
"""Pretend to be the Backup Circle app, to test the Member app without a second phone.

    python scripts/fake_guardian.py 482913
    python scripts/fake_guardian.py 482913 --name Rohan --call-them Papa --server http://192.168.43.17:8000

Registers a guardian, pairs with the 6-digit code shown on the Member phone,
keeps a WebSocket open and prints every message readably. Ctrl+C to stop.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

try:
    import httpx
    from websockets.asyncio.client import connect
    from websockets.exceptions import ConnectionClosed, InvalidStatus

    from backup_server.config import env_int, load_env
    from backup_server.console import BOLD, CYAN, GREEN, GREY, RED, YELLOW, describe_event, fmt_duration, paint, setup_console
except ImportError as exc:
    print(f"Missing Python packages ({exc.name}). Use the server's environment: pip install -r server/requirements.txt")
    sys.exit(1)


def say(text: str, *styles: str) -> None:
    print(f"{paint(time.strftime('%H:%M:%S'), GREY)} {paint(text, *styles)}", flush=True)


def health_problem(health: dict[str, Any]) -> str | None:
    labels = {
        "notifications": "notifications are off",
        "phone": "phone permission is off",
        "call_screening": "Caller ID & spam is off",
        "usage_access": "usage access is off",
    }
    for key, label in labels.items():
        if health.get(key) is False:
            return label
    return None


async def main() -> None:
    load_env()
    parser = argparse.ArgumentParser(description="Fake Backup Circle (guardian) for testing the Member app")
    parser.add_argument("code", help="the 6-digit code shown on the Member phone")
    parser.add_argument("--server", default=f"http://127.0.0.1:{env_int('BACKUP_PORT', 8000)}")
    parser.add_argument("--name", default="Rohan", help="the guardian's own name")
    parser.add_argument("--call-them", default="Papa", help="what the guardian calls the member")
    parser.add_argument("--resolve", choices=["safe", "called"], help="automatically resolve alerts after 5 s")
    args = parser.parse_args()
    setup_console()

    base = args.server.rstrip("/")
    async with httpx.AsyncClient(base_url=base, timeout=10) as http:
        try:
            r = await http.post("/api/guardian/register", json={"name": args.name})
        except httpx.HTTPError as exc:
            say(f"Can't reach the server at {base}: {exc}", RED, BOLD)
            sys.exit(1)
        token = r.json()["token"]
        auth = {"Authorization": f"Bearer {token}"}

        r = await http.post("/api/guardian/pair", json={"code": args.code}, headers=auth)
        if r.status_code != 200:
            say(f"Pairing failed: {r.json().get('message')}", RED, BOLD)
            sys.exit(1)
        member_id = r.json()["member"]["member_id"]
        await http.patch(f"/api/guardian/members/{member_id}", json={"display_name": args.call_them}, headers=auth)
        names = {member_id: args.call_them}
        say(f"Paired. {args.name} is now the Backup for {args.call_them}. Listening... (Ctrl+C to stop)", GREEN, BOLD)

        ws_url = base.replace("http", "ws", 1) + f"/ws?token={token}"
        last_id = 0
        while True:
            try:
                async with connect(ws_url, open_timeout=5) as ws:
                    say("WebSocket connected", GREY)
                    r = await http.get("/api/guardian/events", params={"after_id": last_id}, headers=auth)
                    for event in r.json()["events"]:
                        last_id = max(last_id, event["id"])
                        say("(missed) " + describe_event(names.get(event["member_id"], "Member"), event), GREY)

                    async def pinger() -> None:
                        while True:
                            await ws.send(json.dumps({"type": "ping"}))
                            await asyncio.sleep(15)

                    ping_task = asyncio.create_task(pinger())
                    try:
                        async for raw in ws:
                            message = json.loads(raw)
                            kind = message.get("type")
                            if kind == "ping_request":
                                await ws.send(json.dumps({"type": "ping"}))
                            elif kind == "member_status":
                                name = names.get(message["member_id"], "Member")
                                if not message["online"]:
                                    seen = message.get("last_seen")
                                    when = time.strftime("%H:%M", time.localtime(seen / 1000)) if seen else "never"
                                    say(f"{name}: Offline · last seen {when}", GREY)
                                elif problem := health_problem(message.get("permission_health") or {}):
                                    say(f"{name}: Needs attention: {problem}", YELLOW)
                                else:
                                    say(f"{name}: Protected · Online", GREEN)
                            elif kind == "event":
                                event = message["event"]
                                last_id = max(last_id, event["id"])
                                p = event["payload"]
                                text = describe_event(names.get(event["member_id"], "Member"), event)
                                if event["type"] == "risk_alert":
                                    say(text, RED if p.get("level") == "high" else YELLOW, BOLD)
                                    if args.resolve:
                                        asyncio.create_task(resolve_later(http, auth, event["id"], args.resolve))
                                elif event["type"] == "long_unknown_call":
                                    say(text, YELLOW)
                                else:
                                    say(text, CYAN)
                            elif kind == "event_resolved":
                                how = "marked safe" if message["resolution"] == "safe" else "called"
                                say(f"Alert #{message['event_id']} {how} by {message['resolver_name']}", GREEN)
                    finally:
                        ping_task.cancel()
            except InvalidStatus:
                say("The server no longer knows this guardian (was it reset?). Stopping.", RED, BOLD)
                return
            except (OSError, ConnectionClosed, asyncio.TimeoutError):
                say("Connection lost, retrying in 2 s...", YELLOW)
                await asyncio.sleep(2)


async def resolve_later(http: httpx.AsyncClient, auth: dict[str, str], event_id: int, resolution: str) -> None:
    await asyncio.sleep(5)
    r = await http.post(f"/api/guardian/events/{event_id}/resolve", json={"resolution": resolution}, headers=auth)
    say(f"Resolved alert #{event_id} as '{resolution}' ({r.status_code})", GREY)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
