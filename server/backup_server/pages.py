"""The phone-friendly download page served at '/'."""
from __future__ import annotations

import html
import time
from pathlib import Path

APKS = {
    # url name: (file in dist/, file name the phone saves, button title, button subtitle)
    "member.apk": ("member.apk", "Backup.apk", "Download Backup", "for the person you protect"),
    "circle.apk": ("circle.apk", "Backup-Circle.apk", "Download Backup Circle", "for you"),
}

_STYLE = """
:root { --bg:#EEF2F7; --top:#DCE6F2; --surface:#FFFFFF; --primary:#1E88FF; --danger:#F23D57;
        --text:#1F2933; --muted:#8A97A6; --border:#E3EAF2; --success:#22C55E; --warning:#FFB020; }
* { box-sizing:border-box; }
body { margin:0; font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; color:var(--text);
       background:linear-gradient(180deg,var(--top) 0%,var(--bg) 320px); min-height:100vh; }
main { max-width:520px; margin:0 auto; padding:28px 16px 40px; }
.logo { display:flex; align-items:center; gap:12px; margin-bottom:6px; }
h1 { font-size:30px; margin:0; }
p.lead { color:var(--muted); font-size:17px; margin:4px 0 24px; }
a.btn, div.btn { display:block; text-decoration:none; border-radius:24px; padding:20px 22px; margin:0 0 14px;
       font-size:21px; font-weight:700; min-height:64px; }
a.btn small, div.btn small { display:block; font-size:16px; font-weight:500; margin-top:4px; opacity:.85; }
a.primary { background:var(--primary); color:#fff; box-shadow:0 6px 18px rgba(30,136,255,.25); }
a.secondary { background:var(--surface); color:var(--primary); border:2px solid var(--primary); }
div.missing { background:var(--surface); color:var(--muted); border:2px dashed var(--border); }
.meta { font-size:13px; color:var(--muted); margin:-6px 0 18px 6px; }
.card { background:var(--surface); border:1px solid var(--border); border-radius:24px; padding:18px 20px; margin-top:22px; }
.card h2 { font-size:19px; margin:0 0 10px; }
.card ol { margin:0; padding-left:22px; font-size:16px; line-height:1.5; }
.card li { margin-bottom:6px; }
code { background:var(--bg); padding:2px 6px; border-radius:6px; font-size:14px; }
footer { color:var(--muted); font-size:13px; text-align:center; margin-top:26px; }
"""

_SHIELD = """<svg width="44" height="44" viewBox="0 0 48 48" aria-hidden="true">
<path d="M24 4 40 10v12c0 10.5-6.8 18.6-16 22-9.2-3.4-16-11.5-16-22V10z" fill="#1E88FF"/>
<path d="m17 24 5 5 9-10" stroke="#fff" stroke-width="4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"""


def _apk_block(url_name: str, dist_dir: Path, style: str) -> str:
    file_name, _, title, subtitle = APKS[url_name]
    path = dist_dir / file_name
    if not path.exists():
        return (
            f'<div class="btn missing">{html.escape(title)}<small>Not built yet. On the laptop, run '
            f"<code>python scripts/build_apks.py</code>, then reload this page.</small></div>"
        )
    stat = path.stat()
    size_mb = stat.st_size / (1024 * 1024)
    built = time.strftime("%d %b, %H:%M", time.localtime(stat.st_mtime))
    return (
        f'<a class="btn {style}" href="/download/{url_name}">{html.escape(title)}'
        f"<small>{html.escape(subtitle)}</small></a>"
        f'<div class="meta">{size_mb:.1f} MB · built {built}</div>'
    )


def download_page(dist_dir: Path) -> str:
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Install Backup</title><style>{_STYLE}</style></head>
<body><main>
<div class="logo">{_SHIELD}<h1>Install Backup</h1></div>
<p class="lead">Pick the app for this phone.</p>
{_apk_block("member.apk", dist_dir, "primary")}
{_apk_block("circle.apk", dist_dir, "secondary")}
<div class="card"><h2>How to install</h2><ol>
<li>Tap the button. If the browser warns about the file, tap <b>Download anyway</b>.</li>
<li>Open the downloaded file from the notification or your Downloads.</li>
<li>If Android blocks it, tap <b>Settings</b>, turn on <b>Allow from this source</b>, go back and tap <b>Install</b>.</li>
<li>If Play Protect asks, tap <b>Install anyway</b>. These are demo apps built on this laptop.</li>
</ol></div>
<footer>Backup demo server · works on this Wi-Fi only</footer>
</main></body></html>"""


def missing_apk_page(url_name: str) -> str:
    _, _, title, _ = APKS[url_name]
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Not built yet</title><style>{_STYLE}</style></head>
<body><main>
<h1>Not built yet</h1>
<p class="lead">{html.escape(title)} has not been built on the laptop.</p>
<div class="card"><ol><li>On the laptop, run <code>python scripts/build_apks.py</code>.</li>
<li>Then go back to the <a href="/">download page</a> and try again.</li></ol></div>
</main></body></html>"""
