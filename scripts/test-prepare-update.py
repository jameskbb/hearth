#!/usr/bin/env python3
"""Regression checks for the public update asset metadata contract."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("prepare_update", Path(__file__).with_name("prepare-update.py"))
prepare_update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_update)

BADGING = "package: name='io.github.jameskbb.hearth' versionCode='4' versionName='0.1.3'\nsdkVersion:'21'\n"


class UpdateMetadataTest(unittest.TestCase):
    def test_reads_version_from_apk(self):
        self.assertEqual({"versionCode": 4, "versionName": "0.1.3", "minSdk": 21}, prepare_update.metadata(BADGING))

    def test_rejects_other_package_debug_or_unsupported_metadata(self):
        for bad in [BADGING.replace("io.github.jameskbb.hearth", "com.example.other"),
                    BADGING + "application-debuggable\n", BADGING.replace("'4'", "'0'"),
                    BADGING.replace("'21'", "'19'"), BADGING.replace("0.1.3", "unsafe version"), ""]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                prepare_update.metadata(bad)


if __name__ == "__main__":
    unittest.main()
