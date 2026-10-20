#!/usr/bin/env python3
"""Pretend to be the Backup app on the older person's phone, to test the Guardian app
without a real call.

    python scripts/fake_member.py
    python scripts/fake_member.py --threshold 60 --alert-at 75 --call-length 100 --app "Google Pay"

Registers a member, prints its pairing code, waits until a Backup Circle phone pairs,
then plays a scam scenario (seconds from the start of the call):

    0            unknown call answered      -> call_started
    --threshold  call passes the threshold  -> long_unknown_call
    --alert-at   payment app opened         -> risk_alert (high if past the threshold, else medium)
    --call-length call ends                 -> call_ended

Afterwards it stays online (so the dashboard shows "Protected · Online") until Ctrl+C.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

try:
    import httpx
    from websockets.asyncio.client import connect
    from websockets.exceptions import ConnectionClosed

    from backup_server.config import env_int, load_env
    from backup_server.console import BOLD, CYAN, GREEN, GREY, RED, WHITE, YELLOW, big_text, fmt_duration, paint, setup_console
except ImportError as exc:
    print(f"Missing Python packages ({exc.name}). Use the server's environment: pip install -r server/requirements.txt")
    sys.exit(1)

APPS = {
    "PhonePe": "com.phonepe.app",
    "Google Pay": "com.google.android.apps.nbu.paisa.user",
    "Paytm": "net.one97.paytm",
    "BHIM": "in.org.npci.upiapp",
}
ALL_OK = {"notifications": True, "phone": True, "call_screening": True, "usage_access": True, "battery": True}


def say(text: str, *styles: str) -> None:
    print(f"{paint(time.strftime('%H:%M:%S'), GREY)} {paint(text, *styles)}", flush=True)


class FakeMember:
    def __init__(self, base: str):
        self.base = base
        self.http = httpx.AsyncClient(base_url=base, timeout=10)
        self.token = ""
        self.paired = asyncio.Event()
        self.guardian_names: list[str] = []

    @property
    def auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}

    async def register(self) -> None:
        r = await self.http.post("/api/member/register", json={"app_version": "fake-member"})
        self.token = r.json()["token"]
        await self.http.post(
            "/api/member/status",
            json={"permission_health": ALL_OK, "app_version": "fake-member",
                  "settings": {"share_long_calls": True, "share_payment_alerts": True}},
            headers=self.auth,
        )

    async def show_code(self) -> float:
        r = (await self.http.post("/api/member/code", headers=self.auth)).json()
        code = r["code"]
        print()
        print(paint("  Type this code in the Backup Circle app:", BOLD, WHITE))
        print()
        for row in big_text(f"{code[:3]} {code[3:]}"):
            print("   " + paint(row, BOLD, CYAN))
        print(f"\n   {paint(code, BOLD)}  (valid for {fmt_duration(r['expires_in_seconds'])})\n", flush=True)
        return time.monotonic() + r["expires_in_seconds"]

    async def wait_for_pairing(self) -> None:
        expires = await self.show_code()
        while not self.paired.is_set():
            if time.monotonic() > expires - 2:
                say("Code expired, here is a new one.", YELLOW)
                expires = await self.show_code()
            # Poll as well, in case the paired message is missed.
            me = (await self.http.get("/api/member/me", headers=self.auth)).json()
            if me["guardians"]:
                self.guardian_names = [g["name"] for g in me["guardians"]]
                self.paired.set()
                break
            try:
                await asyncio.wait_for(self.paired.wait(), timeout=2)
            except asyncio.TimeoutError:
                pass
        say(f"Connected to {', '.join(self.guardian_names) or 'a Backup'}", GREEN, BOLD)

    async def websocket(self) -> None:
        url = self.base.replace("http", "ws", 1) + f"/ws?token={self.token}"
        while True:
            try:
                async with connect(url, open_timeout=5) as ws:
                    async def pinger() -> None:
                        while True:
                            await ws.send(json.dumps({"type": "ping"}))
                            await asyncio.sleep(15)

                    task = asyncio.create_task(pinger())
                    try:
                        async for raw in ws:
                            message = json.loads(raw)
                            if message.get("type") == "ping_request":
                                await ws.send(json.dumps({"type": "ping"}))
                            elif message.get("type") == "paired":
                                self.guardian_names.append(message["guardian_name"])
                                self.paired.set()
                            elif message.get("type") == "unpaired":
                                say(f"{message['guardian_name']} removed this phone", YELLOW)
                    finally:
                        task.cancel()
            except (OSError, ConnectionClosed, asyncio.TimeoutError):
                await asyncio.sleep(2)

    async def send(self, event_type: str, payload: dict[str, Any]) -> None:
        now = int(time.time() * 1000)
        body = {"client_event_id": str(uuid.uuid4()), "type": event_type, "occurred_at": now, "sent_at": now,
                "payload": payload}
        for attempt in range(5):
            try:
                r = await self.http.post("/api/member/events", json=body, headers=self.auth)
                if r.status_code == 200:
                    return
                say(f"Server refused {event_type}: {r.text}", RED)
                return
            except httpx.HTTPError:
                await asyncio.sleep(1 + attempt)


async def main() -> None:
    load_env()
    parser = argparse.ArgumentParser(description="Fake Backup (member) phone for testing the Guardian app")
    parser.add_argument("--server", default=f"http://127.0.0.1:{env_int('BACKUP_PORT', 8000)}")
    parser.add_argument("--threshold", type=int, default=30, help="long-call threshold in seconds")
    parser.add_argument("--alert-at", type=int, help="seconds into the call when the payment app opens")
    parser.add_argument("--call-length", type=int, help="total call length in seconds")
    parser.add_argument("--app", default="PhonePe", choices=sorted(APPS))
    parser.add_argument("--number", default="+91 98765 43210", help="caller number (only last 4 digits are sent)")
    parser.add_argument("--start-delay", type=int, default=5, help="seconds to wait after pairing before the call")
    parser.add_argument("--repeat", type=int, default=1, help="play the scenario this many times")
    parser.add_argument("--gap", type=int, default=30, help="seconds between repeated scenarios")
    args = parser.parse_args()
    alert_at = args.alert_at if args.alert_at is not None else args.threshold + 10
    call_length = args.call_length if args.call_length is not None else max(alert_at, args.threshold) + 20
    setup_console()

    member = FakeMember(args.server.rstrip("/"))
    try:
        await member.register()
    except httpx.HTTPError as exc:
        say(f"Can't reach the server at {args.server}: {exc}", RED, BOLD)
        sys.exit(1)
    ws_task = asyncio.create_task(member.websocket())
    await member.wait_for_pairing()

    for run in range(args.repeat):
        delay = args.start_delay if run == 0 else args.gap
        say(f"Scenario {run + 1}/{args.repeat} starts in {delay} s (threshold {args.threshold} s, "
            f"{args.app} at {alert_at} s, call ends at {call_length} s)", GREY)
        await asyncio.sleep(delay)
        await play_scenario(member, args, alert_at, call_length)

    say("Scenario finished. Staying online; press Ctrl+C to stop.", GREEN)
    await ws_task


async def play_scenario(member: FakeMember, args: argparse.Namespace, alert_at: int, call_length: int) -> None:
    digits = "".join(ch for ch in args.number if ch.isdigit())
    masked = "••••" + digits[-4:]
    call_id = str(uuid.uuid4())
    started = time.monotonic()

    async def at(second: int) -> None:
        await asyncio.sleep(max(0.0, started + second - time.monotonic()))

    steps: list[tuple[int, str]] = sorted(
        [(0, "start"), (args.threshold, "long"), (alert_at, "alert"), (call_length, "end")], key=lambda s: s[0]
    )
    for second, step in steps:
        await at(second)
        elapsed = int(time.monotonic() - started)
        if step == "start":
            await member.send("call_started", {"call_id": call_id, "masked_number": masked, "elapsed_seconds": 0})
            say(f"Unknown call answered ({masked})", CYAN)
        elif step == "long" and args.threshold < call_length:
            await member.send("long_unknown_call", {"call_id": call_id, "elapsed_seconds": elapsed,
                                                    "threshold_seconds": args.threshold})
            say(f"Call passed the {fmt_duration(args.threshold)} threshold", YELLOW)
        elif step == "alert":
            active = alert_at < call_length
            level = "high" if alert_at >= args.threshold else "medium"
            await member.send("risk_alert", {
                "call_id": call_id, "level": level, "app_package": APPS[args.app], "app_label": args.app,
                "call_duration_seconds": min(alert_at, call_length), "call_active": active, "masked_number": masked,
            })
            say(f"{args.app} opened -> {level.upper()} alert", RED if level == "high" else YELLOW, BOLD)
        elif step == "end":
            await member.send("call_ended", {"call_id": call_id, "duration_seconds": call_length})
            say(f"Call ended after {fmt_duration(call_length)}", CYAN)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
