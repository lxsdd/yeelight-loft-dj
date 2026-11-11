"""No device/emulator invocation: fixed-enum privacy regression tests."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "private_ci" / "emulator_boot_diagnosis.py"
spec = importlib.util.spec_from_file_location("classic_emu_diagnosis", MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class EmulatorBootDiagnosisTests(unittest.TestCase):
    def test_distinct_root_cause_categories(self):
        cases = {
            "/dev/kvm: Permission denied": "KVM_PERMISSION_DENIED",
            "Failed to open /dev/kvm: No such file or directory": "KVM_DEVICE_UNAVAILABLE",
            "x86_64 emulation currently requires hardware acceleration": "ACCELERATION_UNAVAILABLE",
            "Emulator: failed to get EGL display": "GRAPHICS_BACKEND_FAILURE",
            "qemu-system-x86_64-headless exit SIGSEGV": "EMULATOR_FATAL_SIGNAL",
            "PANIC: Cannot find AVD 'classic-stock-offline-37'": "AVD_CONFIGURATION_FAILURE",
            "Cannot allocate memory": "DISK_OR_MEMORY_EXHAUSTED",
            "ERROR unknown OEM internal class Lprivate/Secret;": "UNCLASSIFIED",
        }
        for raw, reason in cases.items():
            with self.subTest(reason=reason):
                self.assertEqual(m.diagnose(raw), reason)

    def test_oversize_or_nonstring_is_nonidentifying(self):
        self.assertEqual(m.diagnose(None), "UNCLASSIFIED")
        self.assertEqual(m.diagnose("Q" * (m.MAX_BYTES + 1)), "UNCLASSIFIED")

    def test_cli_prints_only_fixed_enum_and_does_not_leak_source(self):
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "raw-emulator.log"
            path.write_text(
                "PrivateAccount token=secret LocalIP=192.168.0.134\n"
                "Failed to get EGL display\n"
                "OEMClass: Lprivate/Secret;\n", encoding="utf-8")
            outcome = subprocess.run([sys.executable, str(MODULE), str(path)],
                                     capture_output=True, text=True, timeout=10)
            self.assertEqual(outcome.returncode, 0)
            self.assertEqual(outcome.stdout.strip(),
                             "ANDROID17_EMULATOR_BOOT_DIAGNOSIS_GRAPHICS_BACKEND_FAILURE")
            for forbidden in ("PrivateAccount", "secret", "192.168", "OEMClass", str(path)):
                self.assertNotIn(forbidden, outcome.stdout + outcome.stderr)
            path.unlink()
            negative = subprocess.run([sys.executable, str(MODULE), str(path)],
                                      capture_output=True, text=True, timeout=10)
            self.assertEqual(negative.returncode, 2)
            self.assertFalse(negative.stdout)


if __name__ == "__main__":
    unittest.main()
