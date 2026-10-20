#!/usr/bin/env python3
"""Start the Backup demo server.

    python server/run.py            start (keeps existing pairings)
    python server/run.py --reset    wipe the database first, for a fresh demo
    python server/run.py --ip 192.168.43.17   force the address to advertise

Settings come from the repo's .env (see README); command-line flags override them.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import uvicorn

    from backup_server.app import create_app
    from backup_server.config import ServerConfig, env_bool, env_int, env_str, load_env
    from backup_server.console import BOLD, CYAN, GREEN, GREY, RED, WHITE, YELLOW, big_text, paint, setup_console
    from backup_server.network import MdnsAdvertiser, detect_lan_ips, port_is_free
except ImportError as exc:  # friendly message instead of a stack trace
    print(f"Missing Python packages ({exc.name}).")
    print("Run:  pip install -r server/requirements.txt   (inside the server's virtual environment)")
    sys.exit(1)


def wipe_database(config: ServerConfig) -> bool:
    removed = False
    for suffix in ("", "-wal", "-shm", "-journal"):
        path = Path(str(config.db_path) + suffix)
        if path.exists():
            try:
                path.unlink()
                removed = True
            except OSError:
                print(paint(f"Could not delete {path}. Is another server window still running?", RED, BOLD))
                sys.exit(1)
    return removed


def print_banner(ip: str, port: int, other_ips: list[str], mdns_ok: bool, mdns_detail: str,
                 reset: bool, config: ServerConfig) -> None:
    base = f"http://{ip}:{port}"
    rule = paint("═" * 72, CYAN)
    print()
    print(rule)
    print(paint("  BACKUP DEMO SERVER IS RUNNING", BOLD, WHITE))
    print()
    print("  On each phone, open this address in the browser:")
    print()
    for row in big_text(f"{ip}:{port}"):
        print("  " + paint(row, BOLD, CYAN))
    print()
    print(f"  Download page : {paint(base + '/', BOLD, WHITE)}")
    print(f"  API base URL  : {paint(base + '/api', BOLD, WHITE)}")
    if mdns_ok:
        print(f"  mDNS          : {paint('OK', BOLD, GREEN)}  {mdns_detail}")
    else:
        print(f"  mDNS          : {paint('FAILED', BOLD, RED)}  {mdns_detail}")
        print(paint("                  Phones can still connect: type the address in the app's demo panel.", YELLOW))
    for name, label in (("member.apk", "Backup APK    "), ("circle.apk", "Circle APK    ")):
        ok = (config.dist_dir / name).exists()
        state = paint("ready", GREEN) if ok else paint("missing - run: python scripts/build_apks.py", YELLOW)
        print(f"  {label}: {state}")
    print(f"  Database      : {config.db_path}  ({'wiped, fresh demo' if reset else 'kept'})")
    if other_ips:
        print(paint(f"  Other addresses on this laptop: {', '.join(other_ips)}  (use --ip to pick one)", GREY))
    print(paint("  Emulator: type 10.0.2.2:" + str(port) + " in the app's demo panel.", GREY))
    print(paint("  Windows: if phones can't open the page, allow Python through the firewall (see README).", GREY))
    print(rule)
    print(paint("  Live events appear below. Press Ctrl+C to stop.", GREY))
    print()


def main() -> None:
    load_env()
    parser = argparse.ArgumentParser(description="Backup demo server")
    parser.add_argument("--reset", action="store_true", help="wipe the database before starting")
    parser.add_argument("--port", type=int, default=env_int("BACKUP_PORT", 8000))
    parser.add_argument("--ip", default=env_str("BACKUP_IP"), help="LAN address to advertise (default: detected)")
    parser.add_argument(
        "--no-mdns",
        action="store_true",
        default=not env_bool("BACKUP_MDNS", True),
        help="do not advertise over mDNS",
    )
    args = parser.parse_args()

    setup_console()
    config = ServerConfig.from_env()

    if not port_is_free(args.port):
        print(paint(f"Port {args.port} is already in use. Is the server already running in another window?", RED, BOLD))
        sys.exit(1)
    if args.reset:
        wipe_database(config)

    ips = detect_lan_ips()
    ip = args.ip or (ips[0] if ips else "127.0.0.1")
    others = [x for x in ips if x != ip]

    mdns = MdnsAdvertiser(ip, args.port)
    if args.no_mdns:
        mdns_ok, mdns_detail = False, "disabled with --no-mdns"
    elif ip == "127.0.0.1":
        mdns_ok, mdns_detail = False, "no Wi-Fi/LAN address found; join the hotspot and restart"
    else:
        mdns_ok, mdns_detail = mdns.start()

    print_banner(ip, args.port, others, mdns_ok, mdns_detail, args.reset, config)
    try:
        uvicorn.run(create_app(config), host="0.0.0.0", port=args.port, log_level="warning")
    except KeyboardInterrupt:
        pass
    finally:
        mdns.stop()
        print(paint("Server stopped.", GREY))


if __name__ == "__main__":
    main()
