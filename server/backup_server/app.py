"""FastAPI app: pairing, event relay, presence, persistence and APK downloads.

The server stores and relays. Every risk decision is made on the Member phone.
"""
from __future__ import annotations

import json
import math
import re
import secrets
from collections import deque
from contextlib import asynccontextmanager
from typing import Any, Literal, Optional

from fastapi import FastAPI, Header, Request, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import or_
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, col, select

from . import SERVER_VERSION, SERVICE_ID, pages
from .config import ServerConfig
from .console import BOLD, GREEN, GREY, EventLog
from .db import Database, Event, Guardian, Link, Member, PairingCode, dumps, loads
from .hub import Hub


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra: Any):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.extra = extra


# ---------------------------------------------------------------- request bodies
class MemberRegisterBody(BaseModel):
    app_version: Optional[str] = Field(default=None, max_length=40)


class MemberStatusBody(BaseModel):
    permission_health: Optional[dict[str, Any]] = None
    settings: Optional[dict[str, Any]] = None
    app_version: Optional[str] = Field(default=None, max_length=40)


class EventBody(BaseModel):
    client_event_id: str = Field(min_length=1, max_length=100)
    type: str
    occurred_at: int  # member clock, epoch ms
    sent_at: Optional[int] = None  # member clock at the moment of this attempt
    payload: dict[str, Any] = Field(default_factory=dict)


class GuardianNameBody(BaseModel):
    name: str = Field(max_length=40)


class PairBody(BaseModel):
    code: str = Field(max_length=20)


class MemberPatchBody(BaseModel):
    display_name: Optional[str] = Field(default=None, max_length=40)
    phone: Optional[str] = Field(default=None, max_length=30)


class ResolveBody(BaseModel):
    resolution: Literal["safe", "called"]


# ---------------------------------------------------------------- event payloads
class CallStartedPayload(BaseModel):
    call_id: str
    masked_number: str
    elapsed_seconds: int = Field(ge=0)


class LongCallPayload(BaseModel):
    call_id: str
    elapsed_seconds: int = Field(ge=0)
    threshold_seconds: int = Field(ge=0)


class CallEndedPayload(BaseModel):
    call_id: str
    duration_seconds: int = Field(ge=0)


class RiskAlertPayload(BaseModel):
    call_id: str
    level: Literal["medium", "high"]
    app_package: str
    app_label: str
    call_duration_seconds: int = Field(ge=0)
    call_active: bool
    masked_number: str


PAYLOADS: dict[str, type[BaseModel]] = {
    "call_started": CallStartedPayload,
    "long_unknown_call": LongCallPayload,
    "call_ended": CallEndedPayload,
    "risk_alert": RiskAlertPayload,
}


class PairingLimiter:
    """After N wrong codes inside the window, a guardian is locked out for a while."""

    def __init__(self, config: ServerConfig):
        self.config = config
        self.failures: dict[str, deque[float]] = {}
        self.blocked_until: dict[str, float] = {}

    def check(self, guardian_id: str) -> None:
        now = self.config.clock()
        until = self.blocked_until.get(guardian_id, 0.0)
        if now < until:
            raise ApiError(
                429,
                "too_many_attempts",
                "Too many wrong codes. Wait a minute, then try again.",
                retry_after_seconds=math.ceil(until - now),
            )

    def fail(self, guardian_id: str) -> None:
        cfg = self.config
        now = cfg.clock()
        attempts = self.failures.setdefault(guardian_id, deque())
        attempts.append(now)
        while attempts and now - attempts[0] > cfg.wrong_attempt_window_seconds:
            attempts.popleft()
        if len(attempts) >= cfg.max_wrong_attempts:
            self.blocked_until[guardian_id] = now + cfg.lockout_seconds
            attempts.clear()


def _bearer(authorization: Optional[str]) -> Optional[str]:
    if not authorization:
        return None
    parts = authorization.split(" ", 1)
    if len(parts) == 2 and parts[0].lower() == "bearer" and parts[1].strip():
        return parts[1].strip()
    return None


def _clean_name(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    value = " ".join(value.split())
    return value or None


def create_app(config: Optional[ServerConfig] = None) -> FastAPI:
    config = config or ServerConfig()
    db = Database(config.db_path)
    log = EventLog(enabled=config.log_events, clock=config.clock)
    limiter = PairingLimiter(config)

    def now_ms() -> int:
        return int(config.clock() * 1000)

    # ------------------------------------------------------------ helpers
    def guardian_ids_for(session: Session, member_id: str) -> list[str]:
        return list(session.exec(select(Link.guardian_id).where(Link.member_id == member_id)).all())

    def member_label(session: Session, member_id: str) -> str:
        link = session.exec(
            select(Link)
            .where(Link.member_id == member_id, col(Link.display_name).is_not(None))
            .order_by(col(Link.id))
        ).first()
        return link.display_name if link and link.display_name else f"Member {member_id[:4]}"

    def member_status_message(member: Member) -> dict[str, Any]:
        return {
            "type": "member_status",
            "member_id": member.id,
            "online": hub.is_online("member", member.id),
            "last_seen": member.last_seen,
            "permission_health": loads(member.permission_health),
            "app_version": member.app_version,
        }

    def broadcast_member_status(member_id: str) -> None:
        with db.session() as s:
            member = s.get(Member, member_id)
            if member is None:
                return
            message = member_status_message(member)
            for gid in guardian_ids_for(s, member_id):
                hub.send_to("guardian", gid, message)

    def on_member_presence(member_id: str) -> None:
        with db.session() as s:
            member = s.get(Member, member_id)
            if member is None:
                return
            member.last_seen = now_ms()
            s.add(member)
            s.commit()
            label = member_label(s, member_id)
        online = hub.is_online("member", member_id)
        log.line(f"{label}: {'online' if online else 'offline'}", GREEN if online else GREY)
        broadcast_member_status(member_id)

    hub = Hub(config, on_member_presence)

    def auth_member(session: Session, authorization: Optional[str]) -> Member:
        token = _bearer(authorization)
        member = session.exec(select(Member).where(Member.token == token)).first() if token else None
        if member is None:
            raise ApiError(401, "invalid_token", "This phone is not set up on the server. Open Backup to set it up again.")
        member.last_seen = now_ms()
        session.add(member)
        return member

    def auth_guardian(session: Session, authorization: Optional[str]) -> Guardian:
        token = _bearer(authorization)
        guardian = session.exec(select(Guardian).where(Guardian.token == token)).first() if token else None
        if guardian is None:
            raise ApiError(401, "invalid_token", "This phone is not set up on the server. Open Backup Circle to set it up again.")
        guardian.last_seen = now_ms()
        session.add(guardian)
        return guardian

    def linked(session: Session, guardian_id: str, member_id: str) -> Link:
        link = session.exec(
            select(Link).where(Link.guardian_id == guardian_id, Link.member_id == member_id)
        ).first()
        if link is None:
            raise ApiError(404, "not_linked", "You are not connected to this person.")
        return link

    def active_call(session: Session, member_id: str) -> Optional[dict[str, Any]]:
        rows = session.exec(
            select(Event)
            .where(Event.member_id == member_id, col(Event.type).in_(["call_started", "call_ended"]))
            .order_by(col(Event.id).desc())
            .limit(20)
        ).all()
        ended: set[str] = set()
        for ev in rows:
            p = loads(ev.payload)
            if ev.type == "call_ended":
                ended.add(p.get("call_id", ""))
                continue
            if p.get("call_id") in ended:
                return None  # the most recent unknown call already ended
            started_at = ev.occurred_at - int(p.get("elapsed_seconds", 0)) * 1000
            elapsed = max(0, (now_ms() - started_at) // 1000)
            if elapsed > 4 * 3600:
                return None  # call_ended never arrived; don't show a call forever
            return {
                "call_id": p.get("call_id"),
                "masked_number": p.get("masked_number", ""),
                "started_at": started_at,
                "elapsed_seconds": elapsed,
            }
        return None

    def open_alerts(session: Session, member_id: str, since: int) -> int:
        return len(
            session.exec(
                select(Event.id).where(
                    Event.member_id == member_id,
                    Event.type == "risk_alert",
                    col(Event.resolved).is_(False),
                    Event.received_at >= since,
                )
            ).all()
        )

    def member_view(session: Session, link: Link) -> dict[str, Any]:
        member = session.get(Member, link.member_id)
        assert member is not None
        return {
            "member_id": member.id,
            "display_name": link.display_name,
            "phone": link.member_phone,
            "online": hub.is_online("member", member.id),
            "last_seen": member.last_seen,
            "permission_health": loads(member.permission_health),
            "settings": loads(member.settings),
            "app_version": member.app_version,
            "active_call": active_call(session, member.id),
            "open_alerts": open_alerts(session, member.id, link.created_at),
            "linked_at": link.created_at,
        }

    def event_view(session: Session, ev: Event) -> dict[str, Any]:
        resolver = session.get(Guardian, ev.resolved_by) if ev.resolved_by else None
        return {
            "id": ev.id,
            "member_id": ev.member_id,
            "client_event_id": ev.client_event_id,
            "type": ev.type,
            "occurred_at": ev.occurred_at,
            "received_at": ev.received_at,
            "payload": loads(ev.payload),
            "resolved": ev.resolved,
            "resolution": ev.resolution,
            "resolved_by": ev.resolved_by,
            "resolved_by_name": resolver.name if resolver else None,
            "resolved_at": ev.resolved_at,
        }

    def new_code(session: Session, now: int) -> str:
        for _ in range(50):
            code = f"{secrets.randbelow(1_000_000):06d}"
            clash = session.exec(
                select(PairingCode).where(
                    PairingCode.code == code, col(PairingCode.used).is_(False), PairingCode.expires_at > now
                )
            ).first()
            if clash is None:
                return code
        raise ApiError(503, "no_codes", "Could not make a new code. Try again.")

    # ------------------------------------------------------------ app
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        hub.start()
        try:
            yield
        finally:
            await hub.stop()
            db.dispose()

    app = FastAPI(title="Backup demo server", version=SERVER_VERSION, lifespan=lifespan)
    app.state.config = config
    app.state.hub = hub

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content={"error": exc.code, "message": exc.message, **exc.extra})

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        errors = exc.errors()
        detail = ""
        if errors:
            where = ".".join(str(part) for part in errors[0].get("loc", []) if part != "body")
            detail = f" ({where}: {errors[0].get('msg', '')})"
        return JSONResponse(status_code=400, content={"error": "invalid_request", "message": f"Invalid request{detail}."})

    # ------------------------------------------------------------ public
    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {"ok": True, "service": SERVICE_ID, "version": SERVER_VERSION, "time": now_ms()}

    @app.get("/", response_class=HTMLResponse)
    async def download_page() -> HTMLResponse:
        return HTMLResponse(pages.download_page(config.dist_dir), headers={"Cache-Control": "no-store"})

    @app.get("/download/{name}", response_model=None)
    async def download(name: str, request: Request) -> FileResponse | HTMLResponse:
        if name not in pages.APKS:
            return HTMLResponse("Not found", status_code=404)
        file_name, save_as, _, _ = pages.APKS[name]
        path = config.dist_dir / file_name
        if not path.exists():
            return HTMLResponse(pages.missing_apk_page(name), status_code=404, headers={"Cache-Control": "no-store"})
        client = request.client.host if request.client else "?"
        log.line(f"Download: {save_as} to {client}", GREY)
        return FileResponse(
            path,
            media_type="application/vnd.android.package-archive",
            filename=save_as,
            headers={"Cache-Control": "no-store"},
        )

    # ------------------------------------------------------------ member
    @app.post("/api/member/register")
    async def member_register(body: Optional[MemberRegisterBody] = None) -> dict[str, Any]:
        body = body or MemberRegisterBody()
        now = now_ms()
        member = Member(
            id=secrets.token_hex(8),
            token=secrets.token_urlsafe(32),
            created_at=now,
            last_seen=now,
            app_version=body.app_version,
        )
        with db.session() as s:
            s.add(member)
            s.commit()
        log.line(f"New Backup phone registered (member {member.id[:4]})", GREEN)
        return {"member_id": member.id, "token": member.token}

    @app.post("/api/member/code")
    async def member_code(authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        with db.session() as s:
            member = auth_member(s, authorization)
            now = now_ms()
            # A new code replaces any unused one.
            for old in s.exec(
                select(PairingCode).where(
                    PairingCode.member_id == member.id, col(PairingCode.used).is_(False), PairingCode.expires_at > now
                )
            ).all():
                old.expires_at = now
                s.add(old)
            row = PairingCode(
                code=new_code(s, now),
                member_id=member.id,
                expires_at=now + config.code_ttl_seconds * 1000,
                created_at=now,
            )
            s.add(row)
            s.commit()
        return {"code": row.code, "expires_at": row.expires_at, "expires_in_seconds": config.code_ttl_seconds}

    @app.get("/api/member/me")
    async def member_me(authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        with db.session() as s:
            member = auth_member(s, authorization)
            rows = s.exec(
                select(Link, Guardian)
                .where(Link.member_id == member.id, Link.guardian_id == Guardian.id)
                .order_by(col(Link.id))
            ).all()
            s.commit()
            return {
                "member_id": member.id,
                "guardians": [
                    {"guardian_id": g.id, "name": g.name, "linked_at": link.created_at} for link, g in rows
                ],
                "settings": loads(member.settings),
                "permission_health": loads(member.permission_health),
                "app_version": member.app_version,
                "server_time": now_ms(),
            }

    @app.post("/api/member/status")
    async def member_status(body: MemberStatusBody, authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        changed = False
        with db.session() as s:
            member = auth_member(s, authorization)
            if body.permission_health is not None:
                text = dumps(body.permission_health)
                if text != member.permission_health:
                    member.permission_health = text
                    changed = True
            if body.settings is not None:
                text = dumps(body.settings)
                if text != member.settings:
                    member.settings = text
                    changed = True
            if body.app_version is not None and body.app_version != member.app_version:
                member.app_version = body.app_version
                changed = True
            s.add(member)
            s.commit()
            member_id = member.id
        if changed:
            broadcast_member_status(member_id)
        return {"ok": True, "server_time": now_ms()}

    @app.post("/api/member/events")
    async def member_event(body: EventBody, authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        received = now_ms()
        model = PAYLOADS.get(body.type)
        if model is None:
            raise ApiError(400, "unknown_event_type", f"Unknown event type '{body.type}'.")
        try:
            payload = model.model_validate(body.payload).model_dump()
        except ValidationError as exc:
            first = exc.errors()[0]
            where = ".".join(str(p) for p in first.get("loc", []))
            raise ApiError(400, "invalid_payload", f"Invalid {body.type} payload ({where}: {first.get('msg', '')}).")
        with db.session() as s:
            member = auth_member(s, authorization)
            existing = s.exec(
                select(Event).where(Event.member_id == member.id, Event.client_event_id == body.client_event_id)
            ).first()
            if existing is not None:
                s.commit()
                return {"event_id": existing.id, "duplicate": True}
            # Phone clocks drift; shift the member's timestamp into the server's clock.
            offset = received - body.sent_at if body.sent_at else 0
            occurred = min(body.occurred_at + offset, received)
            ev = Event(
                member_id=member.id,
                client_event_id=body.client_event_id,
                type=body.type,
                occurred_at=occurred,
                received_at=received,
                payload=dumps(payload),
            )
            s.add(ev)
            try:
                s.commit()
            except IntegrityError:
                s.rollback()
                again = s.exec(
                    select(Event).where(Event.member_id == member.id, Event.client_event_id == body.client_event_id)
                ).one()
                return {"event_id": again.id, "duplicate": True}
            view = event_view(s, ev)
            guardian_ids = guardian_ids_for(s, member.id)
            label = member_label(s, member.id)
        for gid in guardian_ids:
            hub.send_to("guardian", gid, {"type": "event", "event": view})
        log.event(label, body.type, payload)
        return {"event_id": view["id"], "duplicate": False}

    # ------------------------------------------------------------ guardian
    @app.post("/api/guardian/register")
    async def guardian_register(body: GuardianNameBody) -> dict[str, Any]:
        name = _clean_name(body.name)
        if not name:
            raise ApiError(400, "name_required", "Please enter your name.")
        now = now_ms()
        guardian = Guardian(
            id=secrets.token_hex(8), token=secrets.token_urlsafe(32), name=name, created_at=now, last_seen=now
        )
        with db.session() as s:
            s.add(guardian)
            s.commit()
        log.line(f"New Backup Circle phone: {name}", GREEN)
        return {"guardian_id": guardian.id, "token": guardian.token, "name": guardian.name}

    @app.patch("/api/guardian/me")
    async def guardian_rename(body: GuardianNameBody, authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        name = _clean_name(body.name)
        if not name:
            raise ApiError(400, "name_required", "Please enter your name.")
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            guardian.name = name
            s.add(guardian)
            s.commit()
        return {"guardian_id": guardian.id, "name": guardian.name}

    @app.post("/api/guardian/pair")
    async def guardian_pair(body: PairBody, authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        code = re.sub(r"\D", "", body.code)
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            s.commit()
            limiter.check(guardian.id)
            if len(code) != 6:
                raise ApiError(400, "invalid_code_format", "Enter all 6 digits of the code.")
            row = s.exec(
                select(PairingCode).where(PairingCode.code == code).order_by(col(PairingCode.id).desc())
            ).first()
            now = now_ms()
            if row is None:
                limiter.fail(guardian.id)
                raise ApiError(404, "code_not_found", "That code doesn't match any phone. Check the 6 digits and try again.")
            if row.used:
                limiter.fail(guardian.id)
                raise ApiError(409, "code_used", "This code was already used. Ask them to tap New code.")
            if row.expires_at <= now:
                limiter.fail(guardian.id)
                raise ApiError(410, "code_expired", "This code has expired. Ask them to tap New code, then type the new one.")
            row.used = True
            s.add(row)
            link = s.exec(
                select(Link).where(Link.member_id == row.member_id, Link.guardian_id == guardian.id)
            ).first()
            already_linked = link is not None
            if link is None:
                link = Link(member_id=row.member_id, guardian_id=guardian.id, created_at=now)
                s.add(link)
            s.commit()
            view = member_view(s, link)
            label = member_label(s, row.member_id)
        hub.send_to("member", row.member_id, {"type": "paired", "guardian_id": guardian.id, "guardian_name": guardian.name})
        log.line(f"{guardian.name} is now the Backup for {label} (code {code})", GREEN, BOLD)
        return {"member": view, "already_linked": already_linked}

    @app.get("/api/guardian/members")
    async def guardian_members(authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            s.commit()
            links = s.exec(select(Link).where(Link.guardian_id == guardian.id).order_by(col(Link.id))).all()
            return {
                "guardian_id": guardian.id,
                "name": guardian.name,
                "members": [member_view(s, link) for link in links],
                "server_time": now_ms(),
            }

    @app.patch("/api/guardian/members/{member_id}")
    async def guardian_update_member(
        member_id: str, body: MemberPatchBody, authorization: Optional[str] = Header(default=None)
    ) -> dict[str, Any]:
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            link = linked(s, guardian.id, member_id)
            fields = body.model_fields_set
            if "display_name" in fields:
                link.display_name = _clean_name(body.display_name)
            if "phone" in fields:
                phone = (body.phone or "").strip()
                link.member_phone = phone or None
            s.add(link)
            s.commit()
            if "display_name" in fields and link.display_name:
                log.line(f"{guardian.name} calls member {member_id[:4]} \"{link.display_name}\"", GREY)
            return member_view(s, link)

    @app.delete("/api/guardian/members/{member_id}")
    async def guardian_unlink(member_id: str, authorization: Optional[str] = Header(default=None)) -> dict[str, Any]:
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            link = linked(s, guardian.id, member_id)
            label = member_label(s, member_id)
            s.delete(link)
            s.commit()
        hub.send_to("member", member_id, {"type": "unpaired", "guardian_id": guardian.id, "guardian_name": guardian.name})
        log.line(f"{guardian.name} is no longer the Backup for {label}", GREY)
        return {"ok": True}

    @app.get("/api/guardian/events")
    async def guardian_events(
        member_id: Optional[str] = None,
        after_id: int = 0,
        limit: int = 500,
        authorization: Optional[str] = Header(default=None),
    ) -> dict[str, Any]:
        limit = max(1, min(limit, 1000))
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            s.commit()
            if member_id:
                links = [linked(s, guardian.id, member_id)]
            else:
                links = list(s.exec(select(Link).where(Link.guardian_id == guardian.id)).all())
            if not links:
                return {"events": [], "server_time": now_ms()}
            # A guardian sees a member's events from the moment they connected.
            visible = or_(*[(Event.member_id == l.member_id) & (Event.received_at >= l.created_at) for l in links])
            rows = s.exec(
                select(Event).where(Event.id > after_id, visible).order_by(col(Event.id)).limit(limit)
            ).all()
            return {"events": [event_view(s, ev) for ev in rows], "server_time": now_ms()}

    @app.post("/api/guardian/events/{event_id}/resolve")
    async def guardian_resolve(
        event_id: int, body: ResolveBody, authorization: Optional[str] = Header(default=None)
    ) -> dict[str, Any]:
        with db.session() as s:
            guardian = auth_guardian(s, authorization)
            ev = s.get(Event, event_id)
            if ev is None:
                raise ApiError(404, "event_not_found", "That alert no longer exists.")
            linked(s, guardian.id, ev.member_id)
            if ev.type != "risk_alert":
                raise ApiError(400, "not_an_alert", "Only alerts can be marked as handled.")
            if ev.resolved:
                s.commit()
                return event_view(s, ev)  # someone else got there first; nothing to broadcast
            ev.resolved = True
            ev.resolution = body.resolution
            ev.resolved_by = guardian.id
            ev.resolved_at = now_ms()
            s.add(ev)
            s.commit()
            view = event_view(s, ev)
            guardian_ids = guardian_ids_for(s, ev.member_id)
            label = member_label(s, ev.member_id)
        message = {
            "type": "event_resolved",
            "event_id": view["id"],
            "member_id": view["member_id"],
            "resolution": view["resolution"],
            "resolved_by": guardian.id,
            "resolver_name": guardian.name,
            "resolved_at": view["resolved_at"],
        }
        for gid in guardian_ids:
            hub.send_to("guardian", gid, message)
        what = "marked the alert safe" if body.resolution == "safe" else "is calling about the alert"
        log.line(f"{label}: {guardian.name} {what}", GREEN)
        return view

    # ------------------------------------------------------------ websocket
    @app.websocket("/ws")
    async def websocket_endpoint(websocket: WebSocket, token: str = "") -> None:
        role: Optional[str] = None
        principal_id = ""
        with db.session() as s:
            if token:
                member = s.exec(select(Member).where(Member.token == token)).first()
                if member is not None:
                    role, principal_id = "member", member.id
                    member.last_seen = now_ms()
                    s.add(member)
                else:
                    guardian = s.exec(select(Guardian).where(Guardian.token == token)).first()
                    if guardian is not None:
                        role, principal_id = "guardian", guardian.id
                        guardian.last_seen = now_ms()
                        s.add(guardian)
            s.commit()
        if role is None:
            # Closing before accept answers the handshake with HTTP 403; the apps
            # treat that as "this phone was reset on the server" and set up again.
            await websocket.close(code=4401)
            return
        await websocket.accept()
        conn = hub.add(websocket, role, principal_id)
        try:
            if role == "member":
                if hub.count("member", principal_id) == 1:
                    on_member_presence(principal_id)
            else:
                with db.session() as s:
                    for link in s.exec(select(Link).where(Link.guardian_id == principal_id)).all():
                        member = s.get(Member, link.member_id)
                        if member is not None:
                            conn.send(member_status_message(member))
            while True:
                text = await websocket.receive_text()
                try:
                    message = json.loads(text)
                except ValueError:
                    continue
                if isinstance(message, dict) and message.get("type") == "ping":
                    hub.mark_ping(conn)
                    conn.send({"type": "pong", "time": now_ms()})
                    with db.session() as s:
                        row = s.get(Member if role == "member" else Guardian, principal_id)
                        if row is not None:
                            row.last_seen = now_ms()
                            s.add(row)
                            s.commit()
        except WebSocketDisconnect:
            pass
        except Exception:
            pass
        finally:
            await hub.remove(conn)

    return app
