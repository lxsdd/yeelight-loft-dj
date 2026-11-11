"""Test the exact source-only hosted SDK preflight with fake local tools.

All tests are offline and have no access to original APKs or to a real Android
SDK/emulator; source CI additionally runs the real read-only catalog preflight.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PRE = ROOT / "classic_source/private_ci/hosted_sdk_catalog_preflight.sh"
SOURCE_CI = ROOT / ".github/workflows/classic-source-verify.yml"
STOCK_CI = ROOT / ".github/workflows/classic-private-stock-original-android17.yml"
APPROVED = "system-images;android-37.2;google_apis_ps16k;x86_64"


class HostedCatalogPreflight(unittest.TestCase):
    def make_fake_sdk(self, root: Path, package=APPROVED):
        home = root / "android"
        cli = home / "cmdline-tools/latest/bin"
        cli.mkdir(parents=True)
        (cli.parent / "source.properties").write_text("Pkg.Path=cmdline-tools;20.0\n")
        plat = home / "platform-tools"
        plat.mkdir(parents=True)
        for path in (cli / "avdmanager", plat / "adb"):
            path.write_text("#!/bin/sh\nexit 0\n")
            path.chmod(0o700)
        sdk = cli / "sdkmanager"
        sdk.write_text("#!/bin/sh\n"
                       'test "$1" = "--list" && test "$2" = "--channel=0" || exit 33\n'
                       "printf 'Installed packages:\\nAvailable Packages:\\n'\n"
                       + "printf '  " + package + " | 7 | stable\\n'\n")
        sdk.chmod(0o700)
        return home

    def execute(self, home: Path, tmp: Path, extra=None):
        env = dict(os.environ)
        env.update({"ANDROID_HOME": str(home), "RUNNER_TEMP": str(tmp),
                    "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj"})
        if extra:
            env.update(extra)
        return subprocess.run(["bash", str(PRE), "--catalog"], cwd=ROOT, env=env,
                              capture_output=True, text=True, timeout=20)

    def test_valid_hosted_catalog_no_side_effect_and_no_names_in_logs(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            home = self.make_fake_sdk(root)
            outcome = self.execute(home, root)
            self.assertEqual(outcome.returncode, 0, outcome.stderr)
            self.assertEqual(outcome.stdout.strip(), "ANDROID17_PUBLIC_STABLE_CATALOG_PASS")
            self.assertNotIn(APPROVED, outcome.stdout + outcome.stderr)
            self.assertFalse(list(root.glob("classic-readonly-sdk.*")))
            self.assertEqual(len(list(home.rglob("*"))), 7)

    def test_unlisted_package_fails_early_and_cleanly(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            home = self.make_fake_sdk(root, "system-images;android-37;google_apis;x86_64")
            outcome = self.execute(home, root)
            self.assertEqual(outcome.returncode, 2)
            self.assertIn("HOSTED_SDK_IMAGE_UNLISTED", outcome.stderr)
            self.assertEqual(outcome.stdout, "")
            self.assertFalse(list(root.glob("classic-readonly-sdk.*")))

    def test_missing_sdk_and_bad_owner_fail_closed(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            home = self.make_fake_sdk(root)
            for extra in ({"GITHUB_REPOSITORY": "other/repo"},
                          {"ANDROID_HOME": str(root / "absent")}):
                with self.subTest(extra=extra):
                    outcome = self.execute(home, root, extra)
                    self.assertEqual(outcome.returncode, 2)
                    self.assertEqual(outcome.stdout, "")

    def test_rejects_noncatalog_invocations_and_no_contract_consent(self):
        text = PRE.read_text()
        for forbidden in ("sdkmanager --licenses", "yes |", "sdkmanager --install",
                          "adb shell", "apksigner", "accept-android-sdk-licenses",
                          "vault.sh", "curl -k", "upload-artifact"):
            self.assertNotIn(forbidden, text)
        self.assertIn('timeout 150s "$CLI/sdkmanager" --list --channel=0', text)
        self.assertIn('</dev/null', text)
        self.assertIn('trap cleanup EXIT', text)

    def test_public_and_private_workflows_execute_same_preflight(self):
        source = SOURCE_CI.read_text()
        private = STOCK_CI.read_text()
        command = "bash classic_source/private_ci/hosted_sdk_catalog_preflight.sh --catalog"
        self.assertIn(command, source)
        self.assertIn(command, private)
        self.assertLess(source.index("Run synthetic-only Classic tests"),
                        source.index(command))
        self.assertLess(private.index(command), private.index("vault.sh fetch"))
        for value in ("android-actions/setup-android@", "accept-android-sdk-licenses"):
            self.assertNotIn(value, private)


if __name__ == "__main__":
    unittest.main()
