#!/usr/bin/env python3
"""Regression tests for publication checks, run on CI with synthetic data."""

from contextlib import redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("public_safety", Path(__file__).with_name("check-public-safety.py"))
safety = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safety)


class PublicSafetyTest(unittest.TestCase):
    def test_identifiers_in_binary_metadata_are_detected(self):
        address = ".".join(["192", "168", "4", "9"]).encode()
        self.assertIn("private network address", safety.content_findings("image.webp", b"\0" + address))

    def test_paths_and_hardware_addresses_are_detected(self):
        path = "/".join(["", "home", "sample", "work"]).encode()
        mac = ":".join(["ab", "cd", "ef", "01", "23", "45"]).encode()
        self.assertIn("personal machine path", safety.content_findings("notes.md", path))
        self.assertIn("hardware address", safety.content_findings("notes.md", mac))

    def test_versions_timestamps_and_placeholders_are_allowed(self):
        self.assertEqual([], safety.content_findings("notes.md", b"API 21; 2.1.5; 2026-10-29T12:34:56Z; <tv-ip>"))

    def test_typography_and_private_paths(self):
        self.assertIn("em dash", safety.content_findings("notes.md", chr(0x2014).encode()))
        for name in ["local.properties", "keys/signing.jks", ".env.production", "capture.log"]:
            self.assertTrue(safety.private_path(name))
        self.assertFalse(safety.private_path("gradle.properties"))

    def test_hosted_log_exceptions_do_not_hide_user_content(self):
        address = ".".join(["10", "4", "5", "6"]).encode()
        bot_home = b"/home/" + b"dependabot/work/"
        data = bot_home + b" -Dhttp.proxyHost=" + address
        self.assertEqual([], safety.content_findings("log.txt", safety.normalize_hosted_log(data, dependabot=True)))
        self.assertIn("private network address", safety.content_findings("log.txt", safety.normalize_hosted_log(data)))
        user_path = "/".join(["", "home", "sample", "work"]).encode()
        unrelated = user_path + b" device=" + address
        self.assertEqual(2, len(safety.content_findings("log.txt", safety.normalize_hosted_log(unrelated, dependabot=True))))

    def test_deleted_history_is_checked_and_matches_are_not_echoed(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                def git(*args):
                    subprocess.run(["git", *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                git("init")
                git("config", "user.name", "Test")
                git("config", "user.email", "test@example.invalid")
                address = ".".join(["10", "4", "5", "6"])
                Path("removed.md").write_text(address)
                git("add", ".")
                git("commit", "-m", "Add synthetic fixture")
                git("rm", "removed.md")
                git("commit", "-m", "Remove synthetic fixture")
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertTrue(safety.check(history=True))
                self.assertNotIn(address, output.getvalue())
            finally:
                os.chdir(previous)

    def test_local_links_are_checked_without_fetching_remote_content(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                Path("ok.md").write_text("# Hello")
                self.assertEqual([], safety.broken_links("README.md", b"[OK](ok.md#hello) [Web](https://example.com)"))
                self.assertEqual(1, len(safety.broken_links("README.md", b"[Missing](missing.md)")))
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
