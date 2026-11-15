"""Read-only public regression for the private Android-17 app-launch gate."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/classic-private-test-signed.yml"
SHELL = ROOT / "classic_source/private_ci/run_android17_emulator.sh"
PROBE = ROOT / "classic_source/private_ci/android17_start_smoke.py"

GATE = "- name: PRIVATE Android-17 emulator startup and native Loft DJ UI gate"
PUBLISH = "- name: Send TEST-SIGNED APK to PRIVATE draft Release only"
PARITY = "- name: Independently audit FINAL signed classes and verify unsigned/signed payload parity"


def verify_workflow(value):
    assert "workflow_dispatch:" in value, "owner dispatch required"
    assert "github.actor == 'lxsdd'" in value, "owner identity required"
    assert "github.ref == 'refs/heads/main'" in value, "protected branch only"
    assert "name: classic-private-build" in value, "private environment required"
    assert value.count(GATE) == value.count(PUBLISH) == 1, "missing/multiple gates"
    assert value.index(PARITY) < value.index(GATE) < value.index(PUBLISH), "publish before runtime acceptance"
    block = value.split(GATE, 1)[1].split(PUBLISH, 1)[0]
    assert "bash classic_source/private_ci/run_android17_emulator.sh" in block
    assert "$RUNNER_TEMP/private-output/classic-loft-dj-TEST-SIGNED.apk" in block
    assert "secrets." not in block, "must not pass secrets to emulator"
    assert "upload-artifact@" not in value, "public artifact prohibited"


def verify_private_shell(value):
    required = (
        'GITHUB_REPOSITORY:-', 'GITHUB_ACTOR:-', 'GITHUB_REF:-',
        'GITHUB_EVENT_NAME:-', 'RUNNER_ENVIRONMENT:-',
        '-z "${GH_TOKEN:-}"', '-z "${CLASSIC_VAULT_TOKEN:-}"',
        'sdkmanager --list --channel=0', "select_android17_image.py",
        "'system-images;android-37.2;google_apis_ps16k;x86_64'",
        "'system-images;android-37.1;google_apis_ps16k;x86_64'",
        "sdkmanager --install", "kvm_scope_acquire", "kvm_scope_restore",
        "-accel on -gpu swangle_indirect -feature -Vulkan",
        "emulator_boot_diagnosis.py",
        'adb -s emulator-5554 root',
        'settings put global airplane_mode_on 1',
        'settings put global wifi_on 0',
        'settings put global mobile_data 0',
        'ip -4 route flush table main',
        'ip -6 route flush table main',
        'install -r --no-streaming "$APK"', 'sha256sum "$APK"',
        'clone_launcher_selection.py', 'query-activities --components --user 0',
        'resolve-activity --components --user 0',
        'android17_start_smoke.py', 'classloader_linkage_forensics.py',
        'private_original_baseline.py',
        '--explicit-owner-approved-private-emulator || fail',
        '--expected-installed-apk-sha256',
        '--explicit-owner-approved-private-emulator',
        'PRIVATE_ANDROID17_CLASSIC_NATIVE_UI_SMOKE_PASS',
    )
    for token in required:
        assert token in value, "private emulator safety preflight missing"
    assert 'sdkmanager --licenses' not in value, "no automatic new license acceptance"
    assert "swiftshader_indirect" not in value, "obsolete emulator path"
    assert "system-images;android-37;google_apis;x86_64" not in value, "unsupported image"
    assert "launchable-activity:" not in value, "aapt launcher heuristic forbidden"
    assert "echo 'y'" not in value and "yes |" not in value, "no license assent"
    assert value.index("ip -6 route flush table main") < value.index('install -r --no-streaming "$APK"')
    assert value.index('install -r --no-streaming "$APK"') < value.index("query-activities --components")
    assert value.index("android17_start_smoke.py") < value.index("classloader_linkage_forensics.py") < value.index("private_original_baseline.py") < value.rfind("  fail\nfi")
    assert 'upload-artifact' not in value, "private APK never uploaded publicly"
    assert 'gh release' not in value, "emulator does not publish"
    assert 'adb -d ' not in value, "physical device route forbidden"


class PrivateEmulatorWorkflowContract(unittest.TestCase):
    def test_real_workflow_requires_signed_parity_then_launch_before_publish(self):
        verify_workflow(WORKFLOW.read_text("utf-8"))

    def test_negative_reordered_publish_blocks(self):
        value = WORKFLOW.read_text("utf-8")
        bad = value.replace(GATE, "TEMP_GATE").replace(PUBLISH, GATE).replace("TEMP_GATE", PUBLISH)
        with self.assertRaises(AssertionError):
            verify_workflow(bad)

    def test_negative_missing_owner_or_environment_blocks(self):
        value = WORKFLOW.read_text("utf-8")
        for bad in (value.replace("github.actor == 'lxsdd'", "github.actor == 'any'"),
                    value.replace("name: classic-private-build", "name: public"),
                    value.replace("workflow_dispatch:", "push:")):
            with self.subTest(bad=bad[:40]), self.assertRaises(AssertionError):
                verify_workflow(bad)

    def test_negative_emulator_secrets_and_artifacts_block(self):
        value = WORKFLOW.read_text("utf-8")
        with self.assertRaises(AssertionError):
            verify_workflow(value.replace(GATE, GATE+"\n        env:\n          X: @${ secrets.CLASSIC_VAULT_TOKEN }"))
        with self.assertRaises(AssertionError):
            verify_workflow(value+"\n      - uses: actions/upload-artifact@v4\n")

    def test_private_shell_syntax_and_security(self):
        text = SHELL.read_text("utf-8")
        verify_private_shell(text)
        result = subprocess.run(["bash", "-n", str(SHELL)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, "private shell syntax invalid")

    def test_negative_shell_no_network_check_fails(self):
        text = SHELL.read_text("utf-8")
        with self.assertRaises(AssertionError):
            verify_private_shell(text.replace("ip -4 route flush table main", "echo unsafe"))

    def test_negative_shell_no_apk_pin_fails(self):
        text = SHELL.read_text("utf-8")
        with self.assertRaises(AssertionError):
            verify_private_shell(text.replace('--expected-installed-apk-sha256', '--skip-sha'))

    def test_private_clone_uses_installed_os_launcher_and_proven_sdk(self):
        text = SHELL.read_text("utf-8")
        for removed in ("clone_launcher_selection.py", "query-activities --components",
                        "resolve-activity --components", "kvm_scope_restore",
                        "select_android17_image.py", "swangle_indirect"):
            with self.subTest(removed=removed), self.assertRaises(AssertionError):
                verify_private_shell(text.replace(removed, "unsafe"))
        with self.assertRaises(AssertionError):
            verify_private_shell(text.replace("install -r --no-streaming", "install -r"))

    def test_original_baseline_only_after_failed_clone_and_forensics(self):
        text = SHELL.read_text("utf-8")
        self.assertEqual(text.count("private_original_baseline.py"), 1)
        self.assertIn("private_original_baseline.py", text)
        self.assertIn("--explicit-owner-approved-private-emulator || fail", text)
        self.assertTrue(
            text.index("android17_start_smoke.py") <
            text.index("classloader_linkage_forensics.py") <
            text.index("private_original_baseline.py") <
            text.rfind("  fail\nfi")
        )
        self.assertIn('if python3 classic_source/private_ci/android17_start_smoke.py', text)
        self.assertIn('PRIVATE_ANDROID17_CLASSIC_NATIVE_UI_SMOKE_PASS', text)
        with self.assertRaises(AssertionError):
            verify_private_shell(text.replace("private_original_baseline.py", "unsafe"))
        with self.assertRaises(AssertionError):
            verify_private_shell(text.replace(
                "--explicit-owner-approved-private-emulator || fail",
                "--skip-guard || fail"))

    def test_probe_never_directly_exports_private_dj_activity(self):
        value = PROBE.read_text("utf-8")
        self.assertIn('run(["shell", "input", "tap"', value)
        self.assertIn('if owner_approved is not True:', value)
        self.assertIn('actual_sha_output', value)
        self.assertIn('SMOKE_EMULATOR_ROUTE_NOT_ISOLATED', value)


if __name__ == "__main__":
    unittest.main()
