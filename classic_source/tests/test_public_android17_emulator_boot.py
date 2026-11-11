"""No emulator invoked by unit tests. Public PR CI performs the real boot."""
from pathlib import Path
import subprocess
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
BASH = ROOT / "classic_source/private_ci/run_public_android17_emulator_boot.sh"
WORKFLOW = ROOT / ".github/workflows/classic-public-emulator-boot.yml"
KVM = ROOT / "classic_source/private_ci/hosted_kvm_scope.sh"


class PublicAndroid17BootContracts(unittest.TestCase):
    def test_public_workflow_is_owner_pr_only_no_secrets(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        values = yaml.safe_load(source)
        assert set(values.get("on", values.get(True))) == {"pull_request"}
        self.assertEqual(values["permissions"], {"contents": "read"})
        self.assertEqual(set(values["jobs"]), {"android17-no-oem-boot"})
        job = values["jobs"]["android17-no-oem-boot"]
        self.assertLessEqual(job["timeout-minutes"], 18)
        for part in ("github.repository == 'lxsdd/yeelight-loft-dj'",
                     "github.actor == 'lxsdd'",
                     "github.event_name == 'pull_request'"):
            self.assertIn(part, job["if"])
        self.assertEqual(len(job["steps"]), 3)
        self.assertEqual(job["steps"][0]["with"]["persist-credentials"], False)
        self.assertEqual(job["steps"][-1]["run"],
                         "bash classic_source/private_ci/run_public_android17_emulator_boot.sh --no-oem")
        for bad in ("secrets.", "workflow_dispatch:", "push:", "upload-artifact",
                    "github.actor != ", "contents: write", "github.event.repository.private"):
            self.assertNotIn(bad, source)

    def test_public_runner_keeps_no_apk_no_new_licenses_and_fixed_serial(self):
        script = BASH.read_text(encoding="utf-8")
        for required in ("source classic_source/private_ci/hosted_kvm_scope.sh",
                         'sdkmanager" --install --channel=0',
                         "select_android17_image.py", '"$EMU" -accel-check',
                         "kvm_scope_acquire", "kvm_scope_restore",
                         "-accel on -gpu swangle_indirect -feature -Vulkan",
                         "emulator_boot_diagnosis.py", "ANDROID17_PUBLIC_EMULATOR_COLD_BOOT_PROVEN_NO_OEM",
                         "trap cleanup EXIT", 'getprop ro.build.version.sdk'):
            self.assertIn(required, script)
        for forbidden in ("apksigner", "vault.sh", "CLASSIC_VAULT_TOKEN", "sign ",
                          "gh release", "upload-artifact", "chmod 666", "chmod 777",
                          "sudo ", "adb -d ", "adb install", "sdkmanager --licenses",
                          "yes |"):
            self.assertNotIn(forbidden, script)
        result = subprocess.run(["bash","-n",str(BASH)],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)

    def test_one_runner_user_kvm_permission_scope_restored_no_global_mode(self):
        helper = KVM.read_text(encoding="utf-8")
        self.assertIn("kvm_scope_acquire()", helper)
        self.assertIn("kvm_scope_restore()", helper)
        self.assertIn("KVM_SCOPE_OLD_OWNER", helper)
        self.assertIn("KVM_SCOPE_OLD_MODE", helper)
        self.assertIn('sudo -n chown "$(id -u):$(id -g)" /dev/kvm', helper)
        self.assertIn("sudo -n chmod 0600 /dev/kvm", helper)
        self.assertIn('sudo -n chown "$KVM_SCOPE_OLD_OWNER" /dev/kvm', helper)
        self.assertIn('sudo -n chmod "$KVM_SCOPE_OLD_MODE" /dev/kvm', helper)
        for dangerous in ("0666", "0777", "udevadm", "modprobe", "apt-get",
                          "setfacl -m g::rwx", "chmod 666", "chmod 777"):
            self.assertNotIn(dangerous, helper)
        self.assertIn("kvm_scope_restore", BASH.read_text())
        c = subprocess.run(["bash", "-n", str(KVM)],
                           capture_output=True, text=True)
        self.assertEqual(c.returncode, 0, c.stderr)

    def test_rejects_local_execution_before_any_emulator_or_download(self):
        result = subprocess.run(["bash",str(BASH),"--no-oem"],
                                capture_output=True,text=True,timeout=10,
                                cwd=ROOT,env={"PATH":"/usr/bin:/bin"})
        self.assertEqual(result.returncode,2)
        self.assertIn("ANDROID17_PUBLIC_EMULATOR_BLOCKED_INVALID_CONTEXT",
                      result.stderr)
        self.assertEqual(result.stdout,"")


if __name__ == "__main__":
    unittest.main()
