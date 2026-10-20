#!/usr/bin/env python3
"""Build both debug APKs and copy them to dist/, where the demo server serves them.

    python scripts/build_apks.py          build
    python scripts/build_apks.py --clean  clean build

Results: dist/member.apk (Backup) and dist/circle.apk (Backup Circle).
The Android SDK and Gradle cache locations can be set in the repo's .env (see README).
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "server"))
from backup_server.config import load_env, repo_path  # noqa: E402  (stdlib only)

ANDROID = REPO / "android"
DIST = REPO / "dist"
TOOLCHAIN = REPO / ".toolchain"  # optional project-local SDK / Gradle cache (see README)

OUTPUTS = {
    ANDROID / "member" / "build" / "outputs" / "apk" / "debug" / "member-debug.apk": DIST / "member.apk",
    ANDROID / "guardian" / "build" / "outputs" / "apk" / "debug" / "guardian-debug.apk": DIST / "circle.apk",
}


def find_sdk() -> Path | None:
    candidates = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), str(TOOLCHAIN / "android-sdk")]
    if os.name == "nt":
        candidates.append(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"))
    elif sys.platform == "darwin":
        candidates.append(str(Path.home() / "Library" / "Android" / "sdk"))
    else:
        candidates.append(str(Path.home() / "Android" / "Sdk"))
    for candidate in candidates:
        if candidate and (Path(candidate) / "platform-tools").exists():
            return Path(candidate)
    return None


def build_env() -> dict[str, str]:
    env = os.environ.copy()
    # Keep Gradle's cache next to the project when a local toolchain folder exists.
    if "GRADLE_USER_HOME" not in env and (TOOLCHAIN / "gradle").exists():
        env["GRADLE_USER_HOME"] = str(TOOLCHAIN / "gradle")
    # On some Windows setups the JDK can't create its internal AF_UNIX socket in a temp
    # folder whose path has spaces, and Gradle fails with "Unable to establish loopback
    # connection". A short, space-free folder avoids that.
    if os.name == "nt" and "jdk.net.unixdomain.tmpdir" not in env.get("JAVA_TOOL_OPTIONS", ""):
        tmp = TOOLCHAIN / "tmp" if " " not in str(TOOLCHAIN) else Path(os.environ.get("SystemDrive", "C:") + "\\") / "gradle-uds"
        try:
            tmp.mkdir(parents=True, exist_ok=True)
            env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + f" -Djdk.net.unixdomain.tmpdir={tmp}").strip()
        except OSError:
            pass
    return env


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Backup and Backup Circle debug APKs into dist/")
    parser.add_argument("--clean", action="store_true", help="run a clean build")
    args = parser.parse_args()

    load_env()
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME"):
        if os.environ.get(key):
            os.environ[key] = str(repo_path(os.environ[key]))

    # An SDK named in .env (or the environment) wins; otherwise keep an existing
    # local.properties, or detect the SDK and write one.
    local_props = ANDROID / "local.properties"
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured or not local_props.exists():
        sdk = Path(configured) if configured else find_sdk()
        if sdk is None or not (sdk / "platform-tools").exists():
            print(f"Android SDK not found{f' at {sdk}' if sdk else ''}. Install it (see README) and set ANDROID_HOME in .env.")
            sys.exit(1)
        local_props.write_text(f"sdk.dir={sdk.as_posix()}\n", encoding="utf-8")
        print(f"Using Android SDK at {sdk}")

    # Debug APKs are patched in place between builds and can keep dead space from old
    # builds; deleting them first makes Gradle package a fresh, compact APK.
    for source in OUTPUTS:
        source.unlink(missing_ok=True)

    gradlew = ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew")
    tasks = (["clean"] if args.clean else []) + [":member:assembleDebug", ":guardian:assembleDebug"]
    print(f"Building: {' '.join(tasks)}  (the first build downloads Gradle and libraries; give it a few minutes)")
    started = time.monotonic()
    result = subprocess.run([str(gradlew), *tasks, "--console=plain"], cwd=ANDROID, env=build_env())
    if result.returncode != 0:
        print("\nBuild failed. Scroll up for the first error.")
        sys.exit(result.returncode)

    DIST.mkdir(exist_ok=True)
    for source, target in OUTPUTS.items():
        if not source.exists():
            print(f"Expected {source} after the build, but it is missing.")
            sys.exit(1)
        shutil.copy2(source, target)
        print(f"  {target.relative_to(REPO)}  {target.stat().st_size / (1024 * 1024):.1f} MB")
    print(f"Done in {time.monotonic() - started:.0f} s. Start the server with: python server/run.py")


if __name__ == "__main__":
    main()
