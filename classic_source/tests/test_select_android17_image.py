"""Synthetic exact-package and no-license-acceptance Android17 SDK catalog tests."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "classic_source/private_ci/select_android17_image.py"
spec = importlib.util.spec_from_file_location("classic_stock_sdk_catalog", MODULE)
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


def listing(*packages):
    rows = "\n".join("  " + name + " | 7 | Android Emulator image" for name in packages)
    return ("Installed packages:\n  Path | Version | Description\n"
            "Available Packages:\n  Path | Version | Description\n" + rows + "\n")


class Android17ImageCatalogTests(unittest.TestCase):
    def test_prefers_real_current_minor_api_level_and_16k_gms(self):
        self.assertEqual(
            catalog.select_stable_android17_image(listing(
                "system-images;android-37.1;google_apis_ps16k;x86_64",
                "system-images;android-37.2;google_apis_ps16k;x86_64"
            )),
            catalog.APPROVED_IMAGES[0],
        )

    def test_exact_available_37_1_second_choice_is_bounded(self):
        self.assertEqual(
            catalog.select_stable_android17_image(listing(
                "system-images;android-37.1;google_apis_ps16k;x86_64"
            )),
            catalog.APPROVED_IMAGES[1],
        )

    def test_stable_installed_catalog_also_accepted(self):
        text = ("Installed packages:\n"
                "  system-images;android-37.2;google_apis_ps16k;x86_64 | 7 | installed\n"
                "Available Packages:\n")
        self.assertEqual(catalog.select_stable_android17_image(text),
                         catalog.APPROVED_IMAGES[0])

    def test_no_package_guessing_or_arbitrary_fallback(self):
        forbidden = (
            "system-images;android-37;google_apis;x86_64",
            "system-images;android-37.2;google_apis;x86_64",
            "system-images;android-37.0;google_apis_ps16k;x86_64",
            "system-images;android-37.2-beta3;google_apis_ps16k;x86_64",
            "system-images;android-CANARY;google_apis_ps16k;x86_64",
            "system-images;android-37.2;google_apis_playstore_ps16k;x86_64",
            "system-images;android-37.2;google_apis_ps16k;arm64-v8a",
            "system-images;android-36;google_apis;x86_64",
            "system-images;android-37.2;google_apis_ps16k;x86_64-malice",
        )
        for path in forbidden:
            with self.subTest(path=path):
                self.assertIsNone(catalog.select_stable_android17_image(listing(path)))

    def test_installed_updates_warnings_and_injection_not_packages(self):
        for data in (
            "",
            "Available Updates:\n  system-images;android-37.2;google_apis_ps16k;x86_64 | 7 | foo",
            "system-images;android-37.2;google_apis_ps16k;x86_64 | 7 | foo",
            "Available Packages:\n  system-images;android-37.2;google_apis_ps16k;x86_64;rm -rf | 7 | bad",
            "Available Packages:\n  system-images;android-37.2;google_apis_ps16k;x86_64",
            "Available Packages:\n  system-images;android-37.2;google_apis_ps16k;x86_64.bak | 7 | fake",
            "Available Packages:\n  system-images;android-37.2;google_apis_ps16k;x86_64".replace(";", "\\;"),
        ):
            with self.subTest(data=data):
                self.assertIsNone(catalog.select_stable_android17_image(data))

    def test_cli_positive_and_fail_closed_negative(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "catalog.txt"
            p.write_text(listing(catalog.APPROVED_IMAGES[0]), encoding="utf-8")
            cmd = [sys.executable, str(MODULE), str(p)]
            ok = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            self.assertEqual(ok.returncode, 0)
            self.assertEqual(ok.stdout.strip(), catalog.APPROVED_IMAGES[0])
            p.write_text(listing("system-images;android-37;google_apis;x86_64"),
                         encoding="utf-8")
            fail = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            self.assertEqual(fail.returncode, 2)
            self.assertEqual(fail.stdout, "")
            p.unlink()
            missing = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            self.assertEqual(missing.returncode, 2)
            self.assertEqual(missing.stdout, "")

    def test_oversize_catalog_is_safely_rejected(self):
        huge = listing(catalog.APPROVED_IMAGES[0]) + "X" * catalog.MAX_CATALOG_BYTES
        self.assertIsNone(catalog.select_stable_android17_image(huge))


if __name__ == "__main__":
    unittest.main()
