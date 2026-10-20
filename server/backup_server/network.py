"""LAN address detection and mDNS (DNS-SD) advertisement."""
from __future__ import annotations

import ipaddress
import re
import socket
from typing import Optional

SERVICE_TYPE = "_backupcircle._tcp.local."

# Adapter names that are never the Wi-Fi/hotspot network the phones are on.
_VIRTUAL_ADAPTERS = (
    "vethernet", "virtualbox", "vmware", "hyper-v", "wsl", "docker", "loopback",
    "bluetooth", "tailscale", "zerotier", "vpn", "npcap", "tap-",
)


def _is_lan_ipv4(ip: str) -> bool:
    try:
        addr = ipaddress.IPv4Address(ip)
    except ValueError:
        return False
    return addr.is_private and not addr.is_loopback and not addr.is_link_local


def detect_lan_ips() -> list[str]:
    """Best guess first: the address of the default-route interface, then other LAN adapters."""
    found: list[str] = []
    try:
        # No packet is sent; this only asks the OS which interface it would route through.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            if _is_lan_ipv4(ip):
                found.append(ip)
    except OSError:
        pass
    try:
        import ifaddr

        for adapter in ifaddr.get_adapters():
            name = f"{adapter.nice_name} {adapter.name}".lower()
            if any(v in name for v in _VIRTUAL_ADAPTERS):
                continue
            for ip in adapter.ips:
                if isinstance(ip.ip, str) and _is_lan_ipv4(ip.ip) and ip.ip not in found:
                    found.append(ip.ip)
    except Exception:
        pass
    return found


def port_is_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("0.0.0.0", port))
        except OSError:
            return False
    return True


class MdnsAdvertiser:
    """Advertises the server as _backupcircle._tcp so both apps can find it with NsdManager."""

    def __init__(self, ip: str, port: int):
        self.ip = ip
        self.port = port
        self._zc = None
        self._info = None
        self.instance_name: Optional[str] = None

    def start(self) -> tuple[bool, str]:
        try:
            from zeroconf import IPVersion, ServiceInfo, Zeroconf

            host = re.sub(r"[^a-zA-Z0-9-]", "-", socket.gethostname().split(".")[0]) or "laptop"
            self._zc = Zeroconf(interfaces=[self.ip], ip_version=IPVersion.V4Only)
            self._info = ServiceInfo(
                SERVICE_TYPE,
                f"Backup demo server on {host}.{SERVICE_TYPE}",
                addresses=[socket.inet_aton(self.ip)],
                port=self.port,
                properties={"path": "/", "api": "/api", "v": "1"},
                server=f"{host}-backup.local.",
            )
            self._zc.register_service(self._info, allow_name_change=True)
            self.instance_name = self._info.name
            return True, f"advertised as {SERVICE_TYPE.rstrip('.')} on {self.ip}:{self.port}"
        except Exception as exc:  # zeroconf raises a variety of OSErrors on odd networks
            self.stop()
            return False, f"{type(exc).__name__}: {exc}"

    def stop(self) -> None:
        try:
            if self._zc and self._info:
                self._zc.unregister_service(self._info)
        except Exception:
            pass
        try:
            if self._zc:
                self._zc.close()
        except Exception:
            pass
        self._zc = None
        self._info = None
