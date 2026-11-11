"""Synthetic stock OEM Android17 runtime tests: NO ADB/subprocess/device invoked."""
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import ORIGINAL_SHA256
from private_ci import stock_original_emulator_probe as m

LAUNCHER = "com.yeelight.cherry.RealLauncher"
APK = "/data/app/~~stage/com.yeelight.cherry-xyz/base.apk"


class FakeADB:
    def __init__(self):
        self.calls = []
        self.overrides = {}

    def __call__(self, args, serial):
        self.calls.append(tuple(args))
        key = tuple(args)
        values = {
            ("shell", "getprop", "ro.kernel.qemu"): "1",
            ("shell", "getprop", "ro.build.version.sdk"): "37",
            ("shell", "settings", "get", "global", "airplane_mode_on"): "1",
            ("shell", "settings", "get", "global", "wifi_on"): "0",
            ("shell", "settings", "get", "global", "mobile_data"): "0",
            ("shell", "ip", "-4", "route", "show", "table", "main"): "",
            ("shell", "ip", "-6", "route", "show", "table", "main"): "",
            ("shell", "pm", "path", m.PACKAGE): "package:" + APK,
            ("shell", "sha256sum", APK): ORIGINAL_SHA256 + "  " + APK,
            ("logcat", "-b", "crash", "-c"): "",
            ("shell", "am", "start", "-W", "-n", m.PACKAGE + "/" + LAUNCHER):
                "Status: ok\nActivity: " + m.PACKAGE + "/" + LAUNCHER,
            ("shell", "dumpsys", "activity", "activities"):
                "topResumedActivity: ActivityRecord{77 " + m.PACKAGE + "/" + LAUNCHER + "}",
            ("logcat", "-b", "crash", "-d"): "",
            ("shell", "pidof", m.PACKAGE): "1234",
        }
        if key not in values:
            raise AssertionError("unexpected ADB call")
        return self.overrides.get(key, values[key])

    def run(self):
        return m.probe(serial=m.SERIAL, launcher=LAUNCHER,
                       pinned_sha256=ORIGINAL_SHA256, owner_approved=True,
                       adb=self, pause=lambda _: None)


class OriginalStockEmulatorTests(unittest.TestCase):
    def test_stock_launch_checks_runtime_and_never_proves_dormant_types(self):
        adb = FakeADB()
        result = adb.run()
        self.assertEqual(result["status"], "STOCK_ORIGINAL_OFFLINE_LAUNCH_OBSERVED")
        self.assertTrue(result["stock_launch_verified"])
        for key in ("loft_dj_clone_built", "loft_dj_clone_tested",
                    "all_oem_classes_linkable_proven",
                    "missing_manifest_activities_safe_to_delete_proven",
                    "missing_parent_types_resolved_proven", "physical_device_tested"):
            self.assertFalse(result[key], key)
        self.assertNotIn(("shell", "input", "tap"), adb.calls)
        self.assertEqual(sum(args[:3] == ("shell", "am", "start") for args in adb.calls), 1)
        self.assertTrue(all("install" not in args for args in adb.calls))

    def test_owner_serial_launcher_and_pin_fence_precede_any_adb(self):
        for change in (
            {"owner_approved": False},
            {"serial": "Pixel_6a"},
            {"serial": "adb-25231"},
            {"launcher": "com.other.App"},
            {"launcher": "com.yeelight.cherry.Illegal;rm"},
            {"pinned_sha256": "0" * 64},
            {"pinned_sha256": ""},
        ):
            a = FakeADB()
            kwargs = dict(serial=m.SERIAL, launcher=LAUNCHER,
                          pinned_sha256=ORIGINAL_SHA256, owner_approved=True,
                          adb=a, pause=lambda _: None)
            kwargs.update(change)
            with self.subTest(change=change), self.assertRaises(m.StockBlocked):
                m.probe(**kwargs)
            self.assertFalse(a.calls)

    def test_isolation_required_before_installed_original_is_touched(self):
        bad_states = (
            (("shell", "getprop", "ro.kernel.qemu"), "0"),
            (("shell", "getprop", "ro.build.version.sdk"), "36"),
            (("shell", "settings", "get", "global", "airplane_mode_on"), "0"),
            (("shell", "settings", "get", "global", "wifi_on"), "1"),
            (("shell", "settings", "get", "global", "mobile_data"), "1"),
            (("shell", "ip", "-4", "route", "show", "table", "main"), "default via 10.0.2.2"),
            (("shell", "ip", "-6", "route", "show", "table", "main"), "default dev eth0"),
        )
        for k, value in bad_states:
            a = FakeADB()
            a.overrides[k] = value
            with self.subTest(state=k), self.assertRaises(m.StockBlocked):
                a.run()
            self.assertNotIn(("shell", "pm", "path", m.PACKAGE), a.calls)

    def test_split_install_path_or_wrong_apk_blocks_before_launch(self):
        bad_cases = (
            (("shell", "pm", "path", m.PACKAGE), "package:" + APK + "\npackage:/data/app/split.apk"),
            (("shell", "pm", "path", m.PACKAGE), "package:/sdcard/oem.apk"),
            (("shell", "sha256sum", APK), "a" * 64 + "  " + APK),
        )
        for key, value in bad_cases:
            a = FakeADB()
            a.overrides[key] = value
            with self.subTest(key=key), self.assertRaises(m.StockBlocked):
                a.run()
            self.assertFalse(any(args[:3] == ("shell", "am", "start") for args in a.calls))

    def test_crash_is_sanitized_and_never_qualifies_as_startup(self):
        a = FakeADB()
        a.overrides[("logcat", "-b", "crash", "-d")] = (
            "FATAL EXCEPTION: main\nProcess: com.yeelight.cherry, PID: 12\n"
            "java.lang.NoClassDefFoundError: Lvendor/private/Secret;\n")
        result = a.run()
        self.assertFalse(result["stock_launch_verified"])
        self.assertEqual(result["crash_summary"]["exception_category"],
                         "MISSING_RUNTIME_DEFINITION")
        self.assertNotIn("Secret", json.dumps(result))
        self.assertNotIn("vendor/private", json.dumps(result))

    def test_missing_process_ui_or_ambiguous_crash_fails(self):
        for key, value in (
            (("shell", "pidof", m.PACKAGE), ""),
            (("shell", "dumpsys", "activity", "activities"),
             "topResumedActivity: ActivityRecord{77 com.other/.Main}"),
            (("logcat", "-b", "crash", "-d"), "FATAL EXCEPTION: main\nno process marker"),
        ):
            a = FakeADB()
            a.overrides[key] = value
            with self.subTest(key=key):
                self.assertFalse(a.run()["stock_launch_verified"])

    def test_launch_line_alone_is_not_resumed_ui_proof(self):
        self.assertFalse(m.launched("Activity: " + m.PACKAGE + "/" + LAUNCHER, LAUNCHER))
        self.assertFalse(m.categorize_crash(
            "FATAL EXCEPTION: main\nProcess: com.other, PID: 99\n"
            "NoClassDefFoundError")["target_crash"])

    def test_cli_missing_approval_does_not_invoke_adb(self):
        args = ["stock_original_emulator_probe.py", "--serial", m.SERIAL,
                "--launcher", LAUNCHER, "--pinned-sha256", ORIGINAL_SHA256]
        with patch.object(sys, "argv", args), \
             patch.object(m, "command", side_effect=AssertionError("ADB called")), \
             patch("builtins.print") as output:
            exit_code = m.main()
        self.assertEqual(exit_code, 2)
        self.assertEqual(json.loads(output.call_args.args[0]),
                         {"status":"BLOCKED","reason":"STOCK_ORIGINAL_EMULATOR_UNVERIFIED"})


if __name__ == "__main__":
    unittest.main()
