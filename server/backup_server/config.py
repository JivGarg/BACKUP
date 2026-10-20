from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

SERVER_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = SERVER_DIR.parent
ENV_FILE = REPO_DIR / ".env"


def load_env(path: Path = ENV_FILE) -> None:
    """Reads KEY=VALUE lines from the repo's .env into the environment.
    Variables already set in the real environment win. Stdlib only, so the
    build script can use it without the server's virtual environment."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        if value.startswith("#"):
            value = ""
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ.setdefault(key, value)


def env_str(key: str, default: Optional[str] = None) -> Optional[str]:
    value = os.environ.get(key, "").strip()
    return value or default


def env_int(key: str, default: int) -> int:
    try:
        return int(env_str(key) or default)
    except ValueError:
        return default


def env_bool(key: str, default: bool) -> bool:
    value = env_str(key)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes", "on")


def repo_path(value: str) -> Path:
    """Paths in .env may be relative to the repo root."""
    path = Path(value)
    return path if path.is_absolute() else REPO_DIR / path


@dataclass
class ServerConfig:
    db_path: Path = SERVER_DIR / "data" / "backup.db"
    dist_dir: Path = REPO_DIR / "dist"
    # Pairing
    code_ttl_seconds: int = 600
    max_wrong_attempts: int = 5
    wrong_attempt_window_seconds: float = 60.0
    lockout_seconds: float = 60.0
    # Presence: clients ping every 15 s. After 20 s of silence the server asks for a
    # ping (the push wakes a sleeping phone); after 35 s the client is marked offline.
    offline_after_seconds: float = 35.0
    ping_request_after_seconds: float = 20.0
    presence_check_interval: float = 1.0
    # Wall clock in seconds; tests swap in a fake clock to expire codes instantly.
    clock: Callable[[], float] = time.time
    log_events: bool = True

    @classmethod
    def from_env(cls) -> "ServerConfig":
        """Defaults, overridden by BACKUP_DB_PATH and BACKUP_CODE_TTL_SECONDS from .env."""
        config = cls()
        db = env_str("BACKUP_DB_PATH")
        if db:
            config.db_path = repo_path(db)
        config.code_ttl_seconds = env_int("BACKUP_CODE_TTL_SECONDS", config.code_ttl_seconds)
        return config
