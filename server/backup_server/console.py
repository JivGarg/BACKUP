"""Readable, coloured console output for the laptop screen during the demo."""
from __future__ import annotations

import os
import sys
import time
from typing import Any, Callable

_COLOR = True

RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
RED = "\033[91m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
BLUE = "\033[94m"
CYAN = "\033[96m"
GREY = "\033[90m"
WHITE = "\033[97m"


def setup_console() -> None:
    """UTF-8 output (masked numbers use bullets) and ANSI colours on Windows."""
    global _COLOR
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
        except (AttributeError, ValueError):
            pass
    _COLOR = sys.stdout.isatty() and "NO_COLOR" not in os.environ
    if _COLOR and os.name == "nt":
        try:
            import ctypes

            kernel32 = ctypes.windll.kernel32
            handle = kernel32.GetStdHandle(-11)
            mode = ctypes.c_uint32()
            if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
                kernel32.SetConsoleMode(handle, mode.value | 0x0004)  # VT processing
        except Exception:
            _COLOR = False


def paint(text: str, *styles: str) -> str:
    if not _COLOR or not styles:
        return text
    return "".join(styles) + text + RESET


def fmt_duration(seconds: int | float) -> str:
    seconds = max(0, int(seconds))
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours} h {minutes} min"
    if minutes:
        return f"{minutes} min {secs} s" if secs else f"{minutes} min"
    return f"{secs} s"


class EventLog:
    """One line per thing that happens, e.g. '12:04:31 Papa: unknown call answered (••••3210)'."""

    def __init__(self, enabled: bool = True, clock: Callable[[], float] = time.time):
        self.enabled = enabled
        self.clock = clock

    def line(self, text: str, *styles: str) -> None:
        if not self.enabled:
            return
        stamp = time.strftime("%H:%M:%S", time.localtime(self.clock()))
        print(f"{paint(stamp, GREY)} {paint(text, *styles)}", flush=True)

    def event(self, label: str, event_type: str, p: dict[str, Any]) -> None:
        if event_type == "call_started":
            self.line(f"{label}: unknown call answered ({p.get('masked_number') or 'hidden number'})", CYAN)
        elif event_type == "long_unknown_call":
            self.line(
                f"{label}: long call with an unknown number, {fmt_duration(p.get('elapsed_seconds', 0))} so far",
                YELLOW,
            )
        elif event_type == "call_ended":
            self.line(f"{label}: unknown call ended after {fmt_duration(p.get('duration_seconds', 0))}", CYAN)
        elif event_type == "risk_alert":
            duration = fmt_duration(p.get("call_duration_seconds", 0))
            app = p.get("app_label") or p.get("app_package")
            if p.get("level") == "high":
                self.line(f"{label}: HIGH ALERT, opened {app} after {duration} call", RED, BOLD)
            else:
                self.line(f"{label}: MEDIUM ALERT, opened {app} during a {duration} call", YELLOW, BOLD)
        else:
            self.line(f"{label}: {event_type}")


def describe_event(display: str, event: dict[str, Any]) -> str:
    """The sentence a guardian would read for an event (used by the fake guardian script)."""
    p = event.get("payload", {})
    kind = event.get("type")
    if kind == "call_started":
        return f"{display} is on a call with an unknown number ({p.get('masked_number') or 'hidden number'})"
    if kind == "long_unknown_call":
        return f"Long call: {display} has been on a call with an unknown number for {fmt_duration(p.get('elapsed_seconds', 0))}"
    if kind == "call_ended":
        return f"{display}'s call with an unknown number ended after {fmt_duration(p.get('duration_seconds', 0))}"
    if kind == "risk_alert":
        level = (p.get("level") or "").upper()
        return (
            f"{level} ALERT: {display} has been on a call with an unknown number for "
            f"{fmt_duration(p.get('call_duration_seconds', 0))} and just opened {p.get('app_label')}"
        )
    return f"{display}: {kind}"


# A 5-row block font so the address can be read from across the room.
_GLYPHS = {
    "0": ["███", "█ █", "█ █", "█ █", "███"],
    "1": [" █ ", "██ ", " █ ", " █ ", "███"],
    "2": ["███", "  █", "███", "█  ", "███"],
    "3": ["███", "  █", "███", "  █", "███"],
    "4": ["█ █", "█ █", "███", "  █", "  █"],
    "5": ["███", "█  ", "███", "  █", "███"],
    "6": ["███", "█  ", "███", "█ █", "███"],
    "7": ["███", "  █", "  █", "  █", "  █"],
    "8": ["███", "█ █", "███", "█ █", "███"],
    "9": ["███", "█ █", "███", "  █", "███"],
    ".": [" ", " ", " ", " ", "█"],
    " ": ["  ", "  ", "  ", "  ", "  "],
    ":": [" ", "█", " ", "█", " "],
}


def big_text(text: str) -> list[str]:
    rows = ["", "", "", "", ""]
    for ch in text:
        glyph = _GLYPHS.get(ch)
        if glyph is None:
            continue
        for i in range(5):
            rows[i] += glyph[i] + " "
    return rows
