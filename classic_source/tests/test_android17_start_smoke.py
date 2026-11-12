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
ACTIVITY = "ResumedActivity: ActivityRecord{123 u0 %s t1}"


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

    def test_real_original_classic_handoff_does_not_fail_native_entry(self):
        # Launcher alias can forward into a different OEM class of the *same*
        # clone package. This is proven on protected STOCK (original) run,
        # but remains a synthetic test for the modified Classic candidate.
        next_activity = PACKAGE + "/com.yeelight.cherry.AfterSplash"
        result = evaluate_launch_window(
            component=LAUNCHER,
            launch_output="Status: ok\nActivity: " + next_activity + "\nComplete\n",
            activity_snapshot=ACTIVITY % next_activity,
            crash_log="", observed_ms=3000)
        self.assertEqual(result["status"], "EMULATOR_ACTIVITY_RESUMED_OBSERVED")
        self.assertEqual(result["foreground_category"], "SAME_CLONE_ORIGINAL_ACTIVITY")
        self.assertTrue(result["resumed_activity_observed"])
        self.assertFalse(result["app_functionally_accepted"])

    def test_global_resumed_not_old_task_or_foreign_user(self):
        bad_snapshots = (
            "mResumedActivity: ActivityRecord{123 u0 " + LAUNCHER + " t1}",
            "Hist #0: ActivityRecord{123 u0 " + LAUNCHER + " t1}",
            "ResumedActivity: null",
            "ResumedActivity: ActivityRecord{123 u10 " + LAUNCHER + " t1}",
            "ResumedActivity: ActivityRecord{123 u0 com.other/.Main t1}",
            "ResumedActivity: ActivityRecord{123 u0 " + DJ + " t1}",
            "ResumedActivity: ActivityRecord{123 u0 " + LAUNCHER + " t1}\n" +
                "topResumedActivity: ActivityRecord{124 u0 com.other/.Main t2}",
            "",
        )
        for snapshot in bad_snapshots:
            with self.subTest(snapshot=snapshot[:40]):
                result = evaluate_launch_window(
                    component=LAUNCHER,
                    launch_output="Status: ok\nActivity: " + LAUNCHER + "\nComplete",
                    activity_snapshot=snapshot, crash_log="", observed_ms=3000)
                self.assertEqual(result["status"], "BLOCKED_ACTIVITY_NOT_RESUMED")
                self.assertFalse(result["resumed_activity_observed"])

    def test_native_dj_requires_exact_global_top_resumed(self):
        for snapshot in (
            ACTIVITY % LAUNCHER,
            "mResumedActivity: ActivityRecord{123 u0 " + DJ + " t1}",
            "ResumedActivity: ActivityRecord{123 u0 " + DJ + " t1}\n" +
                "topResumedActivity: ActivityRecord{124 u0 " + LAUNCHER + " t2}",
        ):
            result = evaluate_navigation_window(
                component=DJ, activity_snapshot=snapshot,
                crash_log="", ui_dump=DJ_UI, observed_ms=3000)
            self.assertEqual(result["status"], "BLOCKED_DJ_NOT_RESUMED")
            self.assertFalse(result["resumed_activity_observed"])

    def test_am_start_must_resolve_to_original_classic_not_dj_or_other(self):
        for resolved in (DJ, PACKAGE + "/com.other.App", "com.other/.Main"):
            result = evaluate_launch_window(
                component=LAUNCHER,
                launch_output="Status: ok\nActivity: " + resolved + "\nComplete",
                activity_snapshot=ACTIVITY % LAUNCHER,
                crash_log="", observed_ms=3000)
            self.assertEqual(result["status"], "BLOCKED_START_RESULT")
            self.assertFalse(result["am_result_accepted"])

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


    def test_deep_provider_causes_are_not_lost_after_fourteen_frames(self):
        padding = "".join("    at android.app.ActivityThread.installProvider(Source:31)\n" for _ in range(28))
        for cause, expected in (
                ("java.lang.NoClassDefFoundError", "MISSING_RUNTIME_DEFINITION"),
                ("java.lang.NoSuchMethodError", "METHOD_LINKAGE"),
                ("java.lang.NoSuchFieldError", "FIELD_LINKAGE"),
                ("java.lang.VerifyError", "DEX_VERIFICATION"),
                ("java.lang.UnsatisfiedLinkError", "NATIVE_LIBRARY_LINKAGE"),
                ("android.content.res.Resources$NotFoundException", "RESOURCE_REFERENCE")):
            with self.subTest(cause=cause):
                text = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                        ", PID: 77\njava.lang.RuntimeException: private OEM content\n" +
                        padding + "Caused by: " + cause + ": privateDescriptorMustNeverLeak\n")
                result = classify_crash(text)
                self.assertTrue(result["target_crash"])
                self.assertEqual(result["failure_stage"], "PROVIDER_INIT")
                self.assertEqual(result["exception_category"], expected)
                self.assertEqual(result["nested_cause_count"], 1)
                self.assertEqual(result["exception_depth"], "NESTED_CAUSE")
                self.assertNotIn("privateDescriptorMustNeverLeak", str(result))

    def test_last_nested_cause_wins_without_leaking_original_exception_names(self):
        text = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                ", PID: 12\njava.lang.RuntimeException: vendorOuter\n"
                "    at android.app.ActivityThread.installContentProviders(Source:7)\n"
                "Caused by: java.lang.IllegalStateException: vendorMiddle\n"
                "    at hidden.Vendor.init(Unknown Source)\n"
                "Caused by: java.lang.NoSuchMethodError: vendorRoot\n")
        result = classify_crash(text)
        self.assertEqual(result["exception_category"], "METHOD_LINKAGE")
        self.assertEqual(result["nested_cause_count"], 2)
        for name in ("vendorOuter", "vendorMiddle", "vendorRoot", "hidden.Vendor"):
            self.assertNotIn(name, str(result))

    def test_other_process_causes_are_never_attributed_to_target(self):
        text = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                ", PID: 12\njava.lang.RuntimeException: targetOnly\n"
                "    at android.app.ActivityThread.installContentProviders(Source:7)\n"
                "FATAL EXCEPTION: main\nProcess: com.other.app, PID: 14\n"
                "Caused by: java.lang.NoClassDefFoundError: unrelated\n")
        result = classify_crash(text)
        self.assertEqual(result["exception_category"], "OTHER_FATAL_RUNTIME")
        self.assertEqual(result["nested_cause_count"], 0)
        self.assertEqual(result["failure_stage"], "PROVIDER_INIT")

    def test_foreign_fatal_before_target_does_not_poison_target(self):
        text = ("FATAL EXCEPTION: main\nProcess: com.other.app, PID: 11\n"
                "java.lang.NoClassDefFoundError: other\n"
                "FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                ", PID: 12\njava.lang.RuntimeException: targetOnly\n"
                "    at android.app.ActivityThread.installContentProviders(Source:7)\n"
                "Caused by: java.lang.IllegalArgumentException: targetRoot\n")
        result = classify_crash(text)
        self.assertEqual(result["exception_category"], "ARGUMENT_CONTRACT")
        self.assertTrue(result["target_crash"])
        self.assertEqual(result["failure_stage"], "PROVIDER_INIT")

    def test_unknown_nested_vendor_exception_is_sanitized_not_evidence_of_resolution(self):
        text = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                ", PID: 12\njava.lang.RuntimeException: wrapper\n"
                "    at android.app.ActivityThread.installContentProviders(Source:7)\n"
                "Caused by: hidden.vendor.SecretException: externalPrivateData\n")
        result = classify_crash(text)
        self.assertEqual(result["exception_category"], "OTHER_NESTED_CAUSE")
        self.assertEqual(result["nested_cause_count"], 1)
        self.assertNotIn("Secret", str(result))
        self.assertNotIn("externalPrivateData", str(result))

    def test_unattributed_fatal_still_blocks_without_source_disclosure(self):
        text = ("FATAL EXCEPTION: main\njava.lang.NoClassDefFoundError: unknown\n"
                "FATAL EXCEPTION: main\nProcess: com.other.app, PID: 90\n"
                "Caused by: java.lang.NoSuchMethodError: unrelated\n")
        result = classify_crash(text)
        self.assertTrue(result["unattributed_fatal"])
        self.assertFalse(result["target_crash"])
        self.assertEqual(result["exception_category"], "NONE")

if __name__ == "__main__":
    unittest.main()
