#!/usr/bin/env python3
"""Audit retained Actions logs on CI before making repository history public."""

import importlib.util
import io
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location("public_safety", Path(__file__).with_name("check-public-safety.py"))
safety = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safety)

api = os.environ["GITHUB_API_URL"]
repository = os.environ["GITHUB_REPOSITORY"]
destination = Path(os.environ["RUNNER_TEMP"]) / "hearth-actions-logs"
destination.mkdir(exist_ok=True)


def request(path):
    # Fetch the signed download URL without passing the repository token to its host.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    req = urllib.request.Request(
        f"{api}/repos/{repository}/{path}",
        headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}", "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        with urllib.request.urlopen(error.headers["Location"], timeout=60) as response:
            return response.read()


failures = 0
count = 0
page = 1
while True:
    runs = json.loads(request(f"actions/runs?status=completed&per_page=100&page={page}"))["workflow_runs"]
    if not runs:
        break
    for run in runs:
        try:
            archive = zipfile.ZipFile(io.BytesIO(request(f"actions/runs/{run['id']}/logs")))
        except urllib.error.HTTPError as error:
            if error.code in {404, 410}:
                print(f"SKIP: run {run['id']} has no retained logs")
                continue
            raise
        for index, entry in enumerate(archive.infolist()):
            if entry.is_dir():
                continue
            data = archive.read(entry)
            # Only GitHub's own dynamic updater can use the bot-specific exceptions.
            dependabot = run.get("event") == "dynamic" and run.get("path", "").startswith("dynamic/dependabot/")
            scrubbed = safety.normalize_hosted_log(data, dependabot)
            findings = safety.content_findings("actions.log", scrubbed)
            for finding in findings:
                print(f"FAIL: run {run['id']}, log {index}: {finding} (matched content withheld)")
                failures += 1
            # Use controlled file names, never paths supplied by the ZIP archive.
            (destination / f"{run['id']}-{index}.txt").write_bytes(data)
        count += 1
    page += 1
print(f"{'FAIL' if failures else 'PASS'}: inspected retained logs for {count} completed Actions runs; {failures} privacy findings")
raise SystemExit(bool(failures))
