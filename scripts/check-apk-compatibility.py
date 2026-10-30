#!/usr/bin/env python3
"""Check shipped APK minimum SDKs and the debug APK's Java time desugaring."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import zipfile

sdk = Path(os.environ["ANDROID_HOME"])
aapt = next(iter(sorted((sdk / "build-tools").glob("*/aapt"), reverse=True)), None)
if aapt is None:
    raise SystemExit("No Android build-tools aapt found")

for variant in ("debug", "release"):
    apks = list(Path(f"app/build/outputs/apk/{variant}").glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit(f"Expected one {variant} APK")
    apk = apks[0]
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not minimum or minimum[1] != "21" or not target or target[1] != "34":
        raise SystemExit(f"Unexpected minimum/target SDK in {variant} APK")
    with zipfile.ZipFile(apk) as archive:
        fonts = {"literata_clock_light.ttf", "literata_title_regular.ttf", "literata_title_light.ttf",
                 "instrument_sans_regular.ttf", "instrument_sans_medium.ttf", "instrument_sans_semibold.ttf"}
        # Release resource optimization may shorten filenames. Verify original font
        # bytes instead of assuming that their source paths survive packaging.
        expected = {font: hashlib.sha256((Path("app/src/main/res/font") / font).read_bytes()).digest()
                    for font in fonts}
        present = {hashlib.sha256(archive.read(name)).digest()
                   for name in archive.namelist() if name.startswith("res/") and not name.endswith("/")}
        missing = sorted(font for font, fingerprint in expected.items() if fingerprint not in present)
        if missing:
            raise SystemExit(f"Missing legacy font contents in {variant}: {missing}")
        if variant == "debug":
            # JVM Android sandboxes use the host JDK, so assert desugaring in the real APK too.
            dex = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
            if b"Lj$/time/" not in dex:
                raise SystemExit("Debug APK has no desugared Java time classes")
    print(f"PASS: {variant} APK minSdk 21, targetSdk 34 and bundled legacy fonts")
