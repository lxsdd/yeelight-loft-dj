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


    def test_real_threadtime_prefixed_android_runtime_cause(self):
        # adb logcat -b crash -d formats its lines; plain synthetic lines
        # alone did not exercise the real owner-only emulator observation.
        prefix = "11-12 08:11:15.123  2451  2451 E AndroidRuntime: "
        padding = "".join(prefix + "    at android.app.ActivityThread.installProvider(Source:9)\n"
                          for _ in range(28))
        log = (prefix + "FATAL EXCEPTION: main\n" +
               prefix + "Process: " + PACKAGE + ", PID: 2451\n" +
               prefix + "java.lang.RuntimeException: privateTop\n" +
               padding +
               prefix + "Caused by: java.lang.NoSuchMethodError: secretMethod\n")
        result = classify_crash(log)
        self.assertTrue(result["target_crash"])
        self.assertEqual(result["failure_stage"], "PROVIDER_INIT")
        self.assertEqual(result["exception_category"], "METHOD_LINKAGE")
        self.assertEqual(result["nested_cause_count"], 1)
        self.assertEqual(result["exception_depth"], "NESTED_CAUSE")
        self.assertNotIn("secretMethod", str(result))

    def test_brief_and_time_logcat_formats_are_equivalent(self):
        prefixes = (
            "E/AndroidRuntime( 2451): ",
            "11-12 08:11:15.123 E/AndroidRuntime( 2451): ",
        )
        for prefix in prefixes:
            with self.subTest(prefix=prefix):
                log = (prefix + "FATAL EXCEPTION: main\n" +
                       prefix + "Process: " + PACKAGE + ", PID: 2451\n" +
                       prefix + "java.lang.RuntimeException: hidden\n" +
                       prefix + "    at android.app.ActivityThread.installContentProviders(Source:9)\n" +
                       prefix + "Caused by: java.lang.NoClassDefFoundError: hiddenClass\n")
                result = classify_crash(log)
                self.assertEqual(result["exception_category"], "MISSING_RUNTIME_DEFINITION")
                self.assertEqual(result["failure_stage"], "PROVIDER_INIT")
                self.assertEqual(result["exception_depth"], "NESTED_CAUSE")
                self.assertNotIn("hiddenClass", str(result))

    def test_unrelated_log_tag_cannot_inject_a_nested_cause(self):
        prefix = "11-12 08:11:15.123  2451  2451 E AndroidRuntime: "
        log = (prefix + "FATAL EXCEPTION: main\n" +
               prefix + "Process: " + PACKAGE + ", PID: 2451\n" +
               prefix + "java.lang.RuntimeException: wrapper\n" +
               prefix + "    at android.app.ActivityThread.installContentProviders(Source:9)\n" +
               "11-12 08:11:15.124  2451  2451 E AnotherTag: Caused by: java.lang.NoSuchMethodError: fake\n")
        result = classify_crash(log)
        self.assertEqual(result["exception_category"], "OTHER_FATAL_RUNTIME")
        self.assertEqual(result["nested_cause_count"], 0)
        self.assertEqual(result["exception_depth"], "TOP_LEVEL")
        self.assertTrue(result["target_crash"])


    def test_private_provider_class_missing_distinguished_from_transitive_dependency(self):
        provider = "hidden.vendor.PrivateProvider"
        dependency = "com.yeelight.cherry.internal.SecretDependency"
        prefix = "11-12 10:15:27.231  2468  2468 E AndroidRuntime: "
        def fatal(missing):
            return (prefix + "FATAL EXCEPTION: main\n" +
                    prefix + "Process: " + PACKAGE + ", PID: 2468\n" +
                    prefix + "java.lang.RuntimeException: Unable to get provider " +
                    provider + ": java.lang.ClassNotFoundException\n" +
                    prefix + " at android.app.ActivityThread.installContentProviders\n" +
                    prefix + 'Caused by: java.lang.ClassNotFoundException: Didn' +
                    "'t find class \"" + missing + "\" on path: privatePath\n")
        defs = {"Lhidden/vendor/PrivateProvider;"}
        graph = {"definitions": frozenset(defs),
                 "manifest_providers": frozenset({provider})}
        direct = classify_crash(fatal(provider), candidate_graph=graph)
        self.assertEqual(direct["exception_category"], "CLASS_LOADER_MISSING")
        self.assertEqual(direct["failure_stage"], "PROVIDER_INIT")
        self.assertEqual(direct["provider_instantiation_relation"], "DIRECT_PROVIDER_CLASS")
        self.assertEqual(direct["provider_declared_in_candidate_manifest"], "DECLARED")
        self.assertEqual(direct["provider_defined_in_candidate_dex"], "PRESENT")
        self.assertEqual(direct["missing_class_defined_in_candidate_dex"], "PRESENT")
        self.assertEqual(direct["missing_class_namespace"], "OTHER_RUNTIME_LIBRARY")
        indirect = classify_crash(fatal(dependency), candidate_graph=graph)
        self.assertEqual(indirect["provider_instantiation_relation"],
                         "PROVIDER_DEPENDENCY_OR_REFLECTION")
        self.assertEqual(indirect["missing_class_namespace"], "OEM_CLASS_FAMILY")
        self.assertEqual(indirect["missing_class_defined_in_candidate_dex"], "ABSENT")
        for value in (provider, dependency, "privatePath", "SecretDependency"):
            self.assertNotIn(value, str(direct))
            self.assertNotIn(value, str(indirect))
        no_graph = classify_crash(fatal(provider))
        self.assertEqual(no_graph["classloader_provenance"], "LOG_ONLY")
        self.assertEqual(no_graph["provider_defined_in_candidate_dex"], "NOT_CHECKED")

    def test_provider_class_missing_absent_from_dex_is_not_claimed_resolved(self):
        provider = "hidden.vendor.UnavailableProvider"
        log = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
               ", PID: 7\njava.lang.RuntimeException: Unable to get provider " +
               provider + ": ClassNotFoundException\n" +
               "at android.app.ActivityThread.installContentProviders\n" +
               "Caused by: java.lang.ClassNotFoundException: " + provider + "\n")
        graph = {"definitions": frozenset(),
                 "manifest_providers": frozenset({provider})}
        evidence = classify_crash(log, candidate_graph=graph)
        self.assertEqual(evidence["missing_class_defined_in_candidate_dex"], "ABSENT")
        self.assertEqual(evidence["provider_defined_in_candidate_dex"], "ABSENT")
        self.assertEqual(evidence["provider_declared_in_candidate_manifest"], "DECLARED")
        self.assertEqual(evidence["provider_instantiation_relation"], "DIRECT_PROVIDER_CLASS")
        self.assertNotIn("UnavailableProvider", str(evidence))

    def test_foreign_tag_cannot_inject_private_classloader_provenance(self):
        injected = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
                    ", PID: 8\njava.lang.RuntimeException: unavailable\n" +
                    "at android.app.ActivityThread.installContentProviders\n" +
                    "11-12 10:15:27.231  999  999 E OtherTag: " +
                    "Caused by: java.lang.ClassNotFoundException: hidden.Inject\n")
        classified = classify_crash(injected)
        self.assertEqual(classified["exception_category"], "OTHER_FATAL_RUNTIME")
        self.assertNotIn("provider_instantiation_relation", classified)
        self.assertNotIn("hidden.Inject", str(classified))

    def test_private_candidate_evidence_invalid_path_blocks_without_legacy_fallback(self):
        from android17_start_smoke import _private_candidate_graph
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_PRIVATE_CANDIDATE_PATH_REQUIRED"):
            _private_candidate_graph("/tmp/other.apk")



    def test_classloader_origin_and_installed_apk_path_are_correlated_privately(self):
        provider = "com.yeelight.internal.ExampleProvider"
        missing = "com.yeelight.internal.HiddenDependency"
        apk = "/data/app/~~safe/io.lxsdd.yeelightdj-abc==/base.apk"
        prefix = "11-12 11:42:04.231  1234  1234 E AndroidRuntime: "
        def log(path):
            return (prefix + "FATAL EXCEPTION: main\n" +
                    prefix + "Process: " + PACKAGE + ", PID: 1234\n" +
                    prefix + "java.lang.RuntimeException: Unable to instantiate provider " +
                    provider + ": bad\n" +
                    prefix + " at android.app.ActivityThread.installContentProviders\n" +
                    prefix + "Caused by: java.lang.ClassNotFoundException: Didn't find class \"" +
                    missing + "\" on path: DexPathList[[zip file \"" + path + "\"]]\n")
        descriptor = "L" + missing.replace(".", "/") + ";"
        graph = {"definitions": frozenset({descriptor}),
                 "dex_origin": {descriptor: "SECONDARY_OEM_DEX"},
                 "manifest_providers": frozenset({provider})}
        good = classify_crash(log(apk), candidate_graph=graph, installed_apk_path=apk)
        self.assertEqual(good["exception_category"], "CLASS_LOADER_MISSING")
        self.assertEqual(good["provider_instantiation_relation"], "PROVIDER_DEPENDENCY_OR_REFLECTION")
        self.assertEqual(good["missing_class_defined_in_candidate_dex"], "PRESENT")
        self.assertEqual(good["missing_class_dex_location"], "SECONDARY_OEM_DEX")
        self.assertEqual(good["loader_apk_path_relation"], "INSTALLED_CANDIDATE_INCLUDED")
        self.assertEqual(good["provider_declared_in_candidate_manifest"], "DECLARED")
        self.assertTrue(good["provider_wrapper_seen"])
        other = classify_crash(log("/data/app/another.app/base.apk"),
                               candidate_graph=graph, installed_apk_path=apk)
        self.assertEqual(other["loader_apk_path_relation"], "OTHER_DEX_SOURCE")
        self.assertEqual(good["provider_defined_in_candidate_dex"], "ABSENT")
        for value in (missing, provider, apk, "another.app"):
            self.assertNotIn(value, str(good))
            self.assertNotIn(value, str(other))

    def test_classloader_path_absent_is_not_assumed_to_be_installed_apk(self):
        provider = "com.yeelight.internal.SomeProvider"
        log = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
               ", PID: 123\njava.lang.RuntimeException: Unable to get provider " +
               provider + ": error\nat android.app.ActivityThread.installContentProviders\n" +
               "Caused by: java.lang.ClassNotFoundException: " + provider + "\n")
        result = classify_crash(
            log, candidate_graph={"definitions": frozenset(),
                                  "dex_origin": {},
                                  "manifest_providers": frozenset({provider})},
            installed_apk_path="/data/app/~~safe/io.lxsdd.yeelightdj-abc==/base.apk")
        self.assertEqual(result["loader_apk_path_relation"], "NO_APK_PATH_IN_EXCEPTION")
        self.assertEqual(result["missing_class_dex_location"], "ABSENT")
        self.assertTrue(result["provider_wrapper_seen"])
        self.assertEqual(result["provider_instantiation_relation"], "DIRECT_PROVIDER_CLASS")

    def test_foreign_log_tag_cannot_forge_an_installed_classloader_path(self):
        apk = "/data/app/~~safe/io.lxsdd.yeelightdj-abc==/base.apk"
        log = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE +
               ", PID: 10\njava.lang.RuntimeException: missing\n" +
               "at android.app.ActivityThread.installContentProviders\n" +
               "Caused by: java.lang.ClassNotFoundException: com.yeelight.internal.Missing\n" +
               "11-12 11:42:04.231  1111  1111 E OtherTag: " +
               "on path: DexPathList[[zip file \"" + apk + "\"]]\n")
        report = classify_crash(log, installed_apk_path=apk)
        self.assertEqual(report["loader_apk_path_relation"], "NO_APK_PATH_IN_EXCEPTION")
        self.assertFalse(report["provider_wrapper_seen"])


if __name__ == "__main__":
    unittest.main()
