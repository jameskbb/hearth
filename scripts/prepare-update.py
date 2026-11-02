#!/usr/bin/env python3
"""Prepare signed release assets locally. This script never uploads or publishes."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

PACKAGE = "io.github.jameskbb.hearth"
MAX_APK_BYTES = 256 * 1024 * 1024


def metadata(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.M)
    if not package or package[1] != PACKAGE or not minimum:
        raise ValueError("Expected a Hearth APK with version and minimum SDK metadata")
    code, name, sdk = int(package[2]), package[3], int(minimum[1])
    if not 1 <= code <= 2147483647 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,39}", name):
        raise ValueError("Unsupported APK version metadata")
    if not 21 <= sdk <= 10000:
        raise ValueError("Unsupported minimum SDK")
    if re.search(r"^application-debuggable", badging, re.M):
        raise ValueError("Use a release build, not a debuggable APK")
    return {"versionCode": code, "versionName": name, "minSdk": sdk}


def prepare(apk, output, sdk):
    tools = sdk / "build-tools" / "35.0.0"
    if not (tools / "aapt").is_file() or not (tools / "apksigner").is_file():
        raise ValueError("Install Android build-tools 35.0.0")
    if not apk.is_file() or not 1 <= apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("Missing APK or APK exceeds the 256 MiB update limit")
    # This verifies the signature for the APK's declared supported Android versions.
    verification = subprocess.run([str(tools / "apksigner"), "verify", str(apk)], capture_output=True, text=True)
    if verification.returncode:
        raise ValueError("APK signature verification failed; inspect it with apksigner verify")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    manifest = metadata(badging)
    manifest.update(apkAsset="app-release.apk", sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), sizeBytes=apk.stat().st_size)
    output.mkdir(parents=True, exist_ok=True)
    destination = output / "app-release.apk"
    if apk.resolve() != destination.resolve():
        shutil.copyfile(apk, destination)
    (output / "hearth-update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared Hearth {manifest['versionName']} (code {manifest['versionCode']}) update assets locally.")
    print("Nothing was uploaded. Use the same signing key as the installed app.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-release.apk"))
    parser.add_argument("--output", type=Path, default=Path("build/update-release"))
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_HOME or pass --sdk")
    try:
        prepare(args.apk, args.output, args.sdk)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Cannot prepare update: {error}\n")
