"""Live WebSocket connections and presence.

Each connection has its own outgoing queue and writer task, so a slow phone never
blocks broadcasts to the others. Presence is per connection: a member is online
while at least one of its sockets is alive.
"""
from __future__ import annotations

import asyncio
import json
import time
from typing import Any, Callable, Optional

from fastapi import WebSocket

from .config import ServerConfig


class Conn:
    def __init__(self, ws: WebSocket, role: str, principal_id: str):
        self.ws = ws
        self.role = role  # "member" | "guardian"
        self.principal_id = principal_id
        self.queue: asyncio.Queue[Optional[dict[str, Any]]] = asyncio.Queue()
        self.last_ping = time.monotonic()
        self.ping_requested = False
        self.closed = False
        self.writer: Optional[asyncio.Task[None]] = None

    def send(self, message: dict[str, Any]) -> None:
        if not self.closed:
            self.queue.put_nowait(message)


class Hub:
    def __init__(self, config: ServerConfig, on_member_presence: Callable[[str], None]):
        self.config = config
        self.on_member_presence = on_member_presence
        self.conns: set[Conn] = set()
        self._presence_task: Optional[asyncio.Task[None]] = None

    # -- lifecycle -------------------------------------------------------
    def start(self) -> None:
        self._presence_task = asyncio.create_task(self._presence_loop())

    async def stop(self) -> None:
        if self._presence_task:
            self._presence_task.cancel()
        for conn in list(self.conns):
            conn.closed = True
            conn.queue.put_nowait(None)
        self.conns.clear()

    # -- connections -----------------------------------------------------
    def add(self, ws: WebSocket, role: str, principal_id: str) -> Conn:
        conn = Conn(ws, role, principal_id)
        conn.writer = asyncio.create_task(self._write(conn))
        self.conns.add(conn)
        return conn

    async def remove(self, conn: Conn, close_code: Optional[int] = None) -> None:
        if conn.closed:
            return
        conn.closed = True
        self.conns.discard(conn)
        conn.queue.put_nowait(None)
        if close_code is not None:
            try:
                await asyncio.wait_for(conn.ws.close(code=close_code), timeout=2)
            except Exception:
                pass
        if conn.role == "member" and not self.is_online("member", conn.principal_id):
            self.on_member_presence(conn.principal_id)

    def count(self, role: str, principal_id: str) -> int:
        return sum(1 for c in self.conns if c.role == role and c.principal_id == principal_id and not c.closed)

    def is_online(self, role: str, principal_id: str) -> bool:
        return self.count(role, principal_id) > 0

    def send_to(self, role: str, principal_id: str, message: dict[str, Any]) -> None:
        for conn in list(self.conns):
            if conn.role == role and conn.principal_id == principal_id:
                conn.send(message)

    def mark_ping(self, conn: Conn) -> None:
        conn.last_ping = time.monotonic()
        conn.ping_requested = False

    # -- internals -------------------------------------------------------
    async def _write(self, conn: Conn) -> None:
        try:
            while True:
                message = await conn.queue.get()
                if message is None:
                    return
                await conn.ws.send_text(json.dumps(message, ensure_ascii=False))
        except Exception:
            # The socket is gone; the reader loop notices and cleans up.
            pass

    async def _presence_loop(self) -> None:
        cfg = self.config
        while True:
            await asyncio.sleep(cfg.presence_check_interval)
            now = time.monotonic()
            for conn in list(self.conns):
                silent = now - conn.last_ping
                if silent >= cfg.offline_after_seconds:
                    await self.remove(conn, close_code=4000)
                elif silent >= cfg.ping_request_after_seconds and not conn.ping_requested:
                    # A phone with its screen off may have paused its own 15 s timer.
                    # This push wakes it, and the app answers with a ping right away.
                    conn.ping_requested = True
                    conn.send({"type": "ping_request"})
