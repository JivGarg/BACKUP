from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Optional

from sqlalchemy import UniqueConstraint
from sqlmodel import Field, Session, SQLModel, create_engine

# All timestamps are epoch milliseconds in the server's clock.


class Member(SQLModel, table=True):
    id: str = Field(primary_key=True)
    token: str = Field(index=True, unique=True)
    created_at: int
    last_seen: Optional[int] = None
    permission_health: str = "{}"  # JSON object reported by the Member app
    settings: str = "{}"  # JSON object: sharing switches, threshold, window
    app_version: Optional[str] = None


class Guardian(SQLModel, table=True):
    id: str = Field(primary_key=True)
    token: str = Field(index=True, unique=True)
    name: str
    created_at: int
    last_seen: Optional[int] = None


class Link(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("member_id", "guardian_id", name="uq_link_pair"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    member_id: str = Field(index=True)
    guardian_id: str = Field(index=True)
    display_name: Optional[str] = None  # what this guardian calls the member, e.g. "Papa"
    member_phone: Optional[str] = None
    created_at: int


class PairingCode(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    code: str = Field(index=True)
    member_id: str = Field(index=True)
    expires_at: int
    used: bool = False
    created_at: int


class Event(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("member_id", "client_event_id", name="uq_event_client_id"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    member_id: str = Field(index=True)
    client_event_id: str
    type: str
    occurred_at: int
    received_at: int
    payload: str  # JSON object
    resolved: bool = False
    resolution: Optional[str] = None  # "safe" | "called"
    resolved_by: Optional[str] = None  # guardian id
    resolved_at: Optional[int] = None


class Database:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.engine = create_engine(
            f"sqlite:///{path.as_posix()}", connect_args={"check_same_thread": False}
        )
        SQLModel.metadata.create_all(self.engine)

    def session(self) -> Session:
        return Session(self.engine, expire_on_commit=False)

    def dispose(self) -> None:
        self.engine.dispose()


def loads(text: Optional[str]) -> dict[str, Any]:
    if not text:
        return {}
    try:
        value = json.loads(text)
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def dumps(value: dict[str, Any]) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False)
