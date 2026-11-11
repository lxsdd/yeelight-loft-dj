"""Source-only owner-gated original OEM Android17 workflow contracts."""
from pathlib import Path
import subprocess
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=ROOT / ".github/workflows/classic-private-stock-original-android17.yml"
SHELL=ROOT / "classic_source/private_ci/run_stock_original_android17.sh"
PROBE=ROOT / "classic_source/private_ci/stock_original_emulator_probe.py"


def verify_workflow(text):
    # GitHub's standalone owner-dispatch policy MUST recognize this exact
    # workflow; merely calling policy.py is insufficient (run 43012985357).
    import importlib.util
    module=ROOT / "github_hosted/policy.py"
    spec=importlib.util.spec_from_file_location("classic_owner_policy", module)
    policy=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(policy)
    required=policy.SOURCE_REPO+"/"+str(WORKFLOW.relative_to(ROOT))+"@refs/heads/main"
    assert required in policy.ALLOWED_WORKFLOW_REFS, "protected workflow is not policy allowlisted"
    y=yaml.safe_load(text)
    assert set(y.get("on", y.get(True))) == {"workflow_dispatch"}
    assert y["permissions"] == {"contents":"read"}
    assert set(y["jobs"]) == {"stock-original-emulator"}
    job=y["jobs"]["stock-original-emulator"]
    for code in ("github.actor == 'lxsdd'", "github.ref == 'refs/heads/main'",
                 "github.repository == 'lxsdd/yeelight-loft-dj'",
                 "github.event.repository.private == false"):
        assert code in job["if"]
    assert job["environment"]["name"] == "classic-private-build"
    assert job["timeout-minutes"] <= 45
    steps=job["steps"]
    assert len(steps)==7
    assert [s["uses"] for s in steps if "uses" in s] == [
        "actions/checkout@v7", "actions/setup-python@v6"]
    assert steps[0]["with"]["persist-credentials"] is False
    # The v4 action's boolean input rejected 'no' at run 43025024759.
    # Eliminate the action altogether, including automatic SDK consent.
    assert steps[3]["run"].strip() == (
        "bash classic_source/private_ci/hosted_sdk_catalog_preflight.sh --catalog")
    assert steps[-1]["if"] == "always()"
    assert "android-actions/setup-android" not in text
    assert "accept-android-sdk-licenses" not in text
    assert "sdkmanager --licenses" not in text
    commands="\n".join(s.get("run","") for s in steps)
    assert commands.index("github_hosted/policy.py") < commands.index("vault.sh fetch")
    assert commands.index("vault.sh fetch") < commands.index("run_stock_original_android17.sh")
    for token in ("secrets.CLASSIC_VAULT_TOKEN", "classic-original-3.5.4-v1",
                  "rm -rf --", "workflow_dispatch:"):
        assert token in text
    for forbidden in ("pull_request:", "push:", "upload-artifact@", "gh release",
                      "private_test_sign", "build_private_unsigned", "contents: write",
                      "persist-credentials: true", "sdkmanager --licenses"):
        assert forbidden not in text


def verify_shell(text):
    required=(
        'GITHUB_ACTOR:-', 'GITHUB_REF:-', 'GITHUB_REPOSITORY:-',
        'GITHUB_EVENT_NAME:-', 'RUNNER_ENVIRONMENT:-',
        '-z "${GH_TOKEN:-}"', '-z "${CLASSIC_VAULT_TOKEN:-}"',
        'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"',
        'sdkmanager --list --channel=0',
        'select_android17_image.py',
        "'system-images;android-37.2;google_apis_ps16k;x86_64'",
        "'system-images;android-37.1;google_apis_ps16k;x86_64'",
        'sdkmanager --install', '"$SYSTEM_IMAGE"',
        '</dev/null', 'SDK_IMAGE_NOT_LISTED',
        'SDK_LICENSE_NOT_PREAUTHORIZED',
        'source classic_source/private_ci/hosted_kvm_scope.sh',
        'kvm_scope_acquire', 'kvm_scope_restore',
        'KVM_HOST_DEVICE_MISSING', 'KVM_HOST_ACCESS_UNAVAILABLE',
        'KVM_ACCELERATION_CHECK_FAILED',
        '-accel on -gpu swangle_indirect -feature -Vulkan',
        'emulator_boot_diagnosis.py',
        'inspect_original(Path(sys.argv[1]))', '"$APKSIGNER" verify',
        'adb -s emulator-5554 root', 'settings put global airplane_mode_on 1',
        'ip -4 route flush table main', 'ip -6 route flush table main',
        'install -r --no-streaming', 'stock_original_emulator_probe.py',
        'STOCK_ORIGINAL_ANDROID17_EMULATOR_BLOCKED_',
        'STOCK_SIGNATURE_INVALID', 'STOCK_STARTUP_UNVERIFIED',
        'SDK_PACKAGE_INSTALL_FAILED', 'EMULATOR_BOOT_FAILED',
        'NETWORK_ISOLATION_FAILED',
        '--explicit-owner-approved-private-emulator', 'trap cleanup EXIT'
    )
    for token in required:
        assert token in text
    for forbidden in ("sdkmanager --licenses", "yes |", "apksigner sign",
                      "adb -d ", "adb -s Pixel", "gh release", "upload-artifact"):
        assert forbidden not in text
    assert text.index("ip -6 route flush table main") < text.index("install -r --no-streaming")


class StockWorkflowContract(unittest.TestCase):
    def test_contract_and_shell_syntax(self):
        verify_workflow(WORKFLOW.read_text("utf8"))
        verify_shell(SHELL.read_text("utf8"))
        checked=subprocess.run(["bash","-n",str(SHELL)],capture_output=True,text=True)
        self.assertEqual(checked.returncode,0,checked.stderr)

    def test_negative_workflow_scopes(self):
        text=WORKFLOW.read_text("utf8")
        for old,new in (
            ("workflow_dispatch:", "push:"),
            ("github.actor == 'lxsdd'", "github.actor == 'anyone'"),
            ("name: classic-private-build", "name: public"),
            ("if: always()", "if: success()"),
            ("persist-credentials: false", "persist-credentials: true"),
            ("bash classic_source/private_ci/hosted_sdk_catalog_preflight.sh --catalog",
             "echo skipped sdk safety preflight"),
        ):
            with self.subTest(old=old),self.assertRaises(AssertionError):
                verify_workflow(text.replace(old,new))

    def test_negative_shell_safety_contract(self):
        text=SHELL.read_text("utf8")
        for old,new in (
            ("ip -6 route flush table main", "echo unsafe"),
            ('"$APKSIGNER" verify', '"$APKSIGNER" sign'),
            ("--explicit-owner-approved-private-emulator", "--skip-owner"),
            ("adb -s emulator-5554 root", "adb -d root"),
            ('-z "${GH_TOKEN:-}"', '-n "${GH_TOKEN:-}"'),
        ):
            with self.subTest(old=old),self.assertRaises(AssertionError):
                verify_shell(text.replace(old,new))

    def test_probe_does_not_sign_or_install(self):
        source=PROBE.read_text("utf8")
        self.assertNotIn('"install"',source)
        self.assertNotIn("apksigner",source)
        self.assertIn("owner_approved is True",source)


if __name__=="__main__":
    unittest.main()
