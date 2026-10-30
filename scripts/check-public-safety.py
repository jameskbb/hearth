#!/usr/bin/env python3
"""Check tracked content, or all reachable Git blobs, without echoing matches."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


PATTERNS = {
    "private network address": rb"\b(?:192\.168|10\.[0-9]{1,3}|172\.(?:1[6-9]|2[0-9]|3[01]))\.[0-9]{1,3}\.[0-9]{1,3}\b",
    "hardware address": rb"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])",
    "personal machine path": rb"/(?:home|Users)/[A-Za-z][A-Za-z0-9_.-]*/|[A-Za-z]:\\Users\\[A-Za-z]",
}
TEXT_SUFFIXES = {".md", ".kt", ".kts", ".py", ".sh", ".xml", ".html", ".json", ".yml", ".yaml", ".toml", ".properties"}
PRIVATE_SUFFIXES = {".apk", ".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".hprof", ".log"}
PRIVATE_NAMES = {"local.properties", "keystore.properties", "kubeconfig", "id_rsa", "id_ed25519"}


def git(*args):
    return subprocess.check_output(["git", *args])


def private_path(name):
    path = Path(name)
    return (
        path.suffix.lower() in PRIVATE_SUFFIXES
        or path.name in PRIVATE_NAMES
        or path.name.startswith(".env")
        or ".terraform" in path.parts
    )


def content_findings(name, data):
    findings = [kind for kind, pattern in PATTERNS.items() if re.search(pattern, data)]
    # Binary metadata is checked for identifiers too; typography rules apply to source text.
    if Path(name).suffix in TEXT_SUFFIXES and chr(0x2014).encode() in data:
        findings.append("em dash")
    return findings


def normalize_hosted_log(data, dependabot=False):
    """Ignore specific hosted toolchain fields, never general private content."""
    data = re.sub(rb"/(?:home/(?:runner|cirrus|gradle)|Users/runner)/", b"<hosted-toolchain>/", data)
    if dependabot:
        data = data.replace(b"/home/" + b"dependabot/", b"<hosted-dependabot>/")
        data = re.sub(
            rb"(-D(?:http|https)\.proxyHost=)" + PATTERNS["private network address"],
            rb"\1<hosted-proxy>",
            data,
        )
    return data


def broken_links(name, data):
    if Path(name).suffix != ".md":
        return []
    findings = []
    text = data.decode("utf-8")
    for match in re.finditer(r"!?\[[^\]\n]*\]\(([^)\n]+)\)", text):
        target = match.group(1).split(' "', 1)[0].strip("<>")
        link = urlsplit(target)
        if link.scheme or link.netloc or not link.path:
            continue
        destination = Path(name).parent / unquote(link.path)
        if not destination.exists():
            # Do not print potentially sensitive target text from a contributor's file.
            findings.append(f"broken relative link on line {text[:match.start()].count(chr(10)) + 1}")
    return findings


def check(history=False):
    failures = 0
    checked = 0

    def report(name, findings):
        nonlocal failures
        for finding in findings:
            print(f"FAIL: {name}: {finding} (matched content withheld)")
            failures += 1

    if history:
        if git("rev-parse", "--is-shallow-repository").strip() != b"false":
            raise RuntimeError("History checks require a full checkout (fetch-depth: 0).")
        # Inspect complete blob contents, including deleted files and binary metadata.
        objects = git("rev-list", "--objects", "--all").decode().splitlines()
        object_types = subprocess.check_output(
            ["git", "cat-file", "--batch-check=%(objectname) %(objecttype)"],
            input="\n".join(line.split(" ", 1)[0] for line in objects).encode(),
        ).decode().splitlines()
        for entry, info in zip(objects, object_types):
            if not info.endswith(" blob"):
                continue
            oid, name = entry.split(" ", 1)
            report(name, ["private or generated file in history"] if private_path(name) else [])
            report(name, content_findings(name, git("cat-file", "blob", oid)))
            checked += 1
    else:
        for raw in git("ls-files", "-z").split(b"\0"):
            if not raw:
                continue
            name = raw.decode()
            report(name, ["private or generated tracked file"] if private_path(name) else [])
            data = Path(name).read_bytes()
            report(name, content_findings(name, data) + broken_links(name, data))
            checked += 1
        if Path("AGENTS.md").read_bytes() != Path("CLAUDE.md").read_bytes():
            report("AGENTS.md", ["agent rules differ from CLAUDE.md"])
    print(f"{'FAIL' if failures else 'PASS'}: checked {checked} {'history blobs' if history else 'tracked files'}; {failures} findings")
    return bool(failures)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    args = parser.parse_args()
    sys.exit(check(args.history))
