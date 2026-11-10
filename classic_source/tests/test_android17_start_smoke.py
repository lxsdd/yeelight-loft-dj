"""Synthetic Android-17 Classic startup smoke regressions: never execute adb."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
from android17_start_smoke import (
    PACKAGE, DJ_ACTIVITY, SmokeBlocked, classify_crash, classify_visible_ui,
    entry_button_coordinates, evaluate_launch_window, evaluate_navigation_window,
    probe_private_emulator
)

LAUNCHER = PACKAGE + "/com.yeelight.cherry.MainActivity"
DJ = PACKAGE + "/" + DJ_ACTIVITY
PIN = "a" * 64
APK = "/data/app/~~valid/io.lxsdd.yeelightdj-abc==/base.apk"
ENTRY = ('<?xml version="1.0"?><hierarchy>'
         '<node text="Loft DJ" clickable="true" bounds="[10,20][210,100]"/>'
         '</hierarchy>')
DJ_UI = ('<?xml version="1.0"?><hierarchy>'
         '<node text="Loft DJ · Yeelight Classic" clickable="false"/>'
         '<node text="Mikrofon starten" clickable="true"/>'
         '<node text="DJ stoppen" clickable="true"/>'
         '</hierarchy>')
ACTIVITY = "mResumedActivity: ActivityRecord{123 %s}"


class FakeADB:
    """Exactly one simulated disposable emulator, no real subprocess."""
    def __init__(self):
        self.calls = []
        self.ui_count = 0
        self.overrides = {}

    def __call__(self, args, *, serial):
        self.calls.append(tuple(args))
        key = tuple(args)
        if key in self.overrides:
            return self.overrides[key]
        if key == ("shell", "getprop", "ro.kernel.qemu"):
            return "1"
        if key == ("shell", "getprop", "ro.build.version.release"):
            return "17"
        if key == ("shell", "settings", "get", "global", "airplane_mode_on"):
            return "1"
        if key in (
            ("shell", "settings", "get", "global", "wifi_on"),
            ("shell", "settings", "get", "global", "mobile_data"),
        ):
            return "0"
        if key in (("shell", "ip", "-4", "route", "show"),
                   ("shell", "ip", "-6", "route", "show")):
            return ""
        if key == ("shell", "pm", "path", PACKAGE):
            return "package:" + APK
        if key == ("shell", "sha256sum", APK):
            return PIN + "  " + APK
        if key == ("shell", "am", "start", "-W", "-n", LAUNCHER):
            return "Status: ok\nActivity: " + LAUNCHER + "\nComplete\n"
        if key == ("shell", "dumpsys", "activity", "activities"):
            return ACTIVITY % (DJ if self.ui_count else LAUNCHER)
        if key == ("shell", "uiautomator", "dump", "/dev/tty"):
            self.ui_count += 1
            return ENTRY if self.ui_count == 1 else DJ_UI
        if key == ("shell", "wm", "size"):
            return "Physical size: 1080x2400"
        if key in (("logcat", "-b", "crash", "-c"),
                   ("logcat", "-b", "crash", "-d"),
                   ("shell", "am", "force-stop", PACKAGE)):
            return ""
        if args[:3] == ["shell", "input", "tap"]:
            return ""
        raise AssertionError("unexpected test-only adb call")

    def run(self, **kwargs):
        return probe_private_emulator(
            "emulator-5554", owner_approved=True,
            launcher_component=LAUNCHER,
            expected_installed_apk_sha256=PIN,
            adb=self, pause=lambda seconds: None, **kwargs,
        )


class Android17StartupTest(unittest.TestCase):
    def test_actual_two_stage_ui_navigation(self):
        a = FakeADB()
        result = a.run()
        self.assertEqual(result["status"], "PRIVATE_EMULATOR_NATIVE_NAVIGATION_OBSERVED")
        self.assertEqual(result["android"], "17")
        self.assertTrue(result["installed_apk_hash_verified"])
        self.assertEqual(len(result["entrypoint_observations"]), 2)
        self.assertEqual(result["entrypoint_observations"][0]["status"],
                         "EMULATOR_ACTIVITY_RESUMED_OBSERVED")
        self.assertEqual(result["entrypoint_observations"][1]["status"],
                         "EMULATOR_NATIVE_DJ_NAVIGATION_OBSERVED")
        self.assertIn(("shell", "input", "tap", "110", "60"), a.calls)
        self.assertEqual(sum(c[:3] == ("shell", "am", "start") for c in a.calls), 1)
        self.assertFalse(result["physical_device_tested"])
        self.assertFalse(result["app_functionally_accepted"])

    def test_absent_explicit_owner_approval_blocks_before_adb(self):
        a = FakeADB()
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_OWNER_APPROVAL_REQUIRED"):
            probe_private_emulator("emulator-5554", owner_approved=False,
                                   launcher_component=LAUNCHER,
                                   expected_installed_apk_sha256=PIN, adb=a)
        self.assertEqual(a.calls, [])

    def test_no_physical_serial_or_other_package(self):
        a = FakeADB()
        for serial in ("Pixel_6a", "adb-serial", "emulator-5554;whoami"):
            with self.assertRaises(SmokeBlocked):
                probe_private_emulator(serial, owner_approved=True,
                                       launcher_component=LAUNCHER,
                                       expected_installed_apk_sha256=PIN, adb=a)
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_COMPONENT_OUT_OF_SCOPE"):
            probe_private_emulator("emulator-5554", owner_approved=True,
                                   launcher_component="com.other/com.other.Main",
                                   expected_installed_apk_sha256=PIN, adb=a)
        self.assertEqual(a.calls, [])

    def test_os_and_virtual_hardware_are_both_verified(self):
        for key, value, error in [
            (("shell", "getprop", "ro.kernel.qemu"), "0", "SMOKE_NOT_AN_EMULATOR"),
            (("shell", "getprop", "ro.build.version.release"), "16", "SMOKE_NOT_ANDROID_17"),
            (("shell", "settings", "get", "global", "wifi_on"), "1", "SMOKE_EMULATOR_NOT_OFFLINE"),
            (("shell", "settings", "get", "global", "mobile_data"), "1", "SMOKE_EMULATOR_NOT_OFFLINE"),
            (("shell", "ip", "-4", "route", "show"), "default via 10.0.2.2 dev eth0", "SMOKE_EMULATOR_ROUTE_NOT_ISOLATED"),
            (("shell", "ip", "-6", "route", "show"), "default dev eth0", "SMOKE_EMULATOR_ROUTE_NOT_ISOLATED"),
        ]:
            a = FakeADB()
            a.overrides[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(SmokeBlocked, error):
                a.run()
            self.assertFalse(any(c[:3] == ("shell", "am", "start") for c in a.calls))

    def test_installed_apk_hash_fences_stale_candidate(self):
        a = FakeADB()
        a.overrides[("shell", "sha256sum", APK)] = ("b" * 64) + "  " + APK
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_INSTALLED_APK_HASH_MISMATCH"):
            a.run()
        self.assertNotIn(("shell", "am", "start", "-W", "-n", LAUNCHER), a.calls)

    def test_fail_closed_for_missing_clickable_native_entry(self):
        a = FakeADB()
        a.overrides[("shell", "uiautomator", "dump", "/dev/tty")] = DJ_UI
        result = a.run()
        self.assertEqual(result["status"], "PRIVATE_EMULATOR_LAUNCH_BLOCKED")
        self.assertFalse(result["native_entry_navigation_tested"])

    def test_ui_metadata_is_not_implicitly_a_real_click(self):
        self.assertTrue(classify_visible_ui(ENTRY, stage="classic_entry")["expected_native_ui_observed"])
        self.assertFalse(classify_visible_ui(DJ_UI, stage="classic_entry")["expected_native_ui_observed"])
        self.assertEqual(entry_button_coordinates(ENTRY, "Physical size: 1080x2400"), (110, 60))
        with self.assertRaises(SmokeBlocked):
            entry_button_coordinates(ENTRY + "secret", "invalid")
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_UNSAFE_UI_XML"):
            classify_visible_ui("<!DOCTYPE x>" + ENTRY, stage="classic_entry")

    def test_launch_without_resumed_activity_is_not_pass(self):
        result = evaluate_launch_window(component=LAUNCHER,
                                        launch_output="Status: ok\nActivity: "+LAUNCHER+"\nComplete",
                                        activity_snapshot="mResumedActivity: something irrelevant",
                                        crash_log="", observed_ms=3000)
        self.assertEqual(result["status"], "BLOCKED_ACTIVITY_NOT_RESUMED")

    def test_unattributed_or_target_fatal_blocks_and_stays_sanitized(self):
        crash="FATAL EXCEPTION: main\nProcess: "+PACKAGE+", PID: 123\njava.lang.NoClassDefFoundError: secretVendorSymbol\nat ActivityThread.installContentProviders"
        summary = classify_crash(crash)
        self.assertTrue(summary["target_crash"])
        self.assertEqual(summary["exception_category"], "MISSING_RUNTIME_DEFINITION")
        self.assertEqual(summary["failure_stage"], "PROVIDER_INIT")
        self.assertNotIn("secretVendorSymbol", str(summary))
        n = evaluate_navigation_window(component=DJ, activity_snapshot=ACTIVITY % DJ,
                                       crash_log=crash, ui_dump=DJ_UI, observed_ms=3000)
        self.assertEqual(n["status"], "BLOCKED_CRASH")
        self.assertEqual(classify_crash("FATAL EXCEPTION: main")["unattributed_fatal"], True)

    def test_no_exported_dj_activity_required(self):
        a=FakeADB()
        a.run()
        self.assertNotIn(("shell", "am", "start", "-W", "-n", DJ), a.calls)


if __name__ == "__main__":
    unittest.main()
