"""Synthetic original-stock launcher selection tests; no APK or ADB access."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "classic_source/private_ci"))
import stock_launcher_selection as m

PKG = "com.yeelight.cherry"
ALIAS = PKG + "/.LaunchAlias"
MAIN = PKG + "/com.yeelight.cherry.Main"
BADGE = "package: name='com.yeelight.cherry' versionCode='35402'\n"


class StockLauncherSelectionTests(unittest.TestCase):
    def test_missing_aapt_launchable_with_package_manager_alias(self):
        self.assertTrue(m.verify_badging(BADGE))
        self.assertEqual(m.select_installed_launcher(ALIAS, ALIAS),
                         PKG + ".LaunchAlias")

    def test_alias_and_multiple_candidates_with_os_resolution(self):
        self.assertEqual(m.select_installed_launcher(MAIN, MAIN), PKG + ".Main")
        self.assertEqual(m.select_installed_launcher(ALIAS + "\n" + MAIN, MAIN),
                         PKG + ".Main")
        self.assertTrue(m.verify_badging(BADGE + "launchable-activity: name='com.yeelight.cherry.Other'\n"))

    def test_badging_is_exact_and_does_not_leak_unknown_names(self):
        for text in ("", "package: name='com.elsewhere'\n", BADGE + BADGE,
                     BADGE + "package: name='com.elsewhere'\n", "x" * 65537):
            with self.subTest(case=len(text)), self.assertRaises(m.LauncherBlocked):
                m.verify_badging(text)

    def test_no_unknown_or_ambiguous_launcher_may_start(self):
        examples = [
            ("", ALIAS), ("No activities found", "No activity found"),
            (ALIAS, "No activity found"), (ALIAS, MAIN),
            (ALIAS + "\n" + ALIAS, ALIAS),
            ("another.package/.Launcher", "another.package/.Launcher"),
            (PKG + "/another.package.Activity", PKG + "/another.package.Activity"),
            (ALIAS + "\nandroid/com.android.internal.app.ResolverActivity", ALIAS),
            (ALIAS + "\nextra garbage", ALIAS),
            (ALIAS, ALIAS + "\n" + MAIN),
            ("\n".join([MAIN] * 33), MAIN),
        ]
        for query, resolved in examples:
            with self.subTest(query=query[:60], resolved=resolved[:60]):
                with self.assertRaises(m.LauncherBlocked):
                    m.select_installed_launcher(query, resolved)

    def test_cli_preinstall_and_installed_success(self):
        script = ROOT / "classic_source/private_ci/stock_launcher_selection.py"
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            badging = root / "badging"
            query = root / "query"
            resolved = root / "resolved"
            badging.write_text(BADGE, encoding="utf-8")
            query.write_text(ALIAS + "\n" + MAIN, encoding="utf-8")
            resolved.write_text(ALIAS, encoding="utf-8")
            early = subprocess.run([sys.executable, str(script), "--preinstall",
                                    "--badging", str(badging)],
                                   capture_output=True, text=True, timeout=10)
            self.assertEqual((early.returncode, early.stdout, early.stderr), (0, "", ""))
            later = subprocess.run([sys.executable, str(script), "--installed",
                                    "--badging", str(badging), "--query", str(query),
                                    "--resolved", str(resolved)],
                                   capture_output=True, text=True, timeout=10)
            self.assertEqual(later.returncode, 0, later.stderr)
            self.assertEqual(later.stdout.strip(), PKG + ".LaunchAlias")
            self.assertEqual(later.stderr, "")

    def test_cli_private_input_does_not_escape_in_failure(self):
        script = ROOT / "classic_source/private_ci/stock_launcher_selection.py"
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            badging, query, resolved = [root / name for name in ("badging", "query", "resolved")]
            badging.write_text(BADGE, encoding="utf-8")
            query.write_text("private-vendor-package/.Secret", encoding="utf-8")
            resolved.write_text(ALIAS, encoding="utf-8")
            args = [sys.executable, str(script), "--installed", "--badging", str(badging),
                    "--query", str(query), "--resolved", str(resolved)]
            result = subprocess.run(args, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, "")
            self.assertEqual(result.stderr.strip(),
                             "STOCK_LAUNCHER_DIAGNOSTIC_INSTALLED_LAUNCHER_UNTRUSTED")
            self.assertNotIn("Secret", result.stderr)
            query.unlink()
            result = subprocess.run(args, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stderr.strip(),
                             "STOCK_LAUNCHER_DIAGNOSTIC_PRIVATE_INPUT_INVALID")

    def test_reject_missing_private_stage_arguments(self):
        script = ROOT / "classic_source/private_ci/stock_launcher_selection.py"
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "badging"
            path.write_text(BADGE)
            result = subprocess.run([sys.executable, str(script), "--installed",
                                     "--badging", str(path)], capture_output=True,
                                    text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stderr.strip(),
                             "STOCK_LAUNCHER_DIAGNOSTIC_PRIVATE_INPUT_INVALID")

    def test_selector_has_no_own_device_network_or_apk_path(self):
        source = (ROOT / "classic_source/private_ci/stock_launcher_selection.py").read_text()
        for forbidden in ("subprocess", "import socket", "import requests", "apksigner",
                          "import urllib", "import zipfile"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
