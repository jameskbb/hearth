#!/usr/bin/env python3
"""Fail CI if an Android runtime was silently filtered out or any test was skipped."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

expected = {int(sdk) for sdk in sys.argv[1].split(",")}
evidence = Path("app/build/compatibility-runtimes")
actual = {int(file.read_text()) for file in evidence.glob("api-*.txt")}
if actual != expected:
    raise SystemExit(f"Runtime coverage mismatch: expected {sorted(expected)}, ran {sorted(actual)}")

suites = [ET.parse(file).getroot() for file in Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
tests = sum(int(suite.get("tests", "0")) for suite in suites)
bad = sum(int(suite.get(key, "0")) for suite in suites for key in ("failures", "errors", "skipped"))
if tests == 0 or bad:
    raise SystemExit(f"Compatibility tests must run without failures, errors or skips: tests={tests}, issues={bad}")
print(f"PASS: API {', '.join(str(sdk) for sdk in sorted(actual))}; {tests} Android framework tests, no skips")
