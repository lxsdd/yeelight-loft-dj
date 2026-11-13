"""Synthetic original-vs-clone startup comparison: no OEM APK or ADB."""
import json
from pathlib import Path
import hashlib
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
import private_original_baseline as m

CLONE = m.CLONE
ORIGINAL = m.ORIGINAL


def fatal(package, missing):
    return ("FATAL EXCEPTION: main\n" +
            "Process: " + package + ", PID: 123\n" +
            "java.lang.RuntimeException: Unable to get provider Private\n" +
            "    at android.app.ActivityThread.installContentProviders\n" +
            "Caused by: java.lang.ClassNotFoundException: Didn't find class \"" +
            missing + "\" on path: DexPathList[private]\n")


class OriginalBaselineRegression(unittest.TestCase):
    def test_original_diagnostic_is_not_a_clone_pass_or_hardware_acceptance(self):
        clone = {"target_crash": True, "exception_category": "CLASS_LOADER_MISSING",
                 "failure_stage": "PROVIDER_INIT"}
        original = {"target_crash": True, "exception_category": "OTHER_FATAL_RUNTIME",
                    "failure_stage": "PROVIDER_INIT"}
        result = m.compare_results(clone, "com.yeelight.Hidden", original, None, False)
        self.assertEqual(result["runtime_relation"],
                         "ORIGINAL_DIFFERENT_OR_UNRESOLVED_CRASH")
        self.assertEqual(result["missing_class_identity_relation"], "UNAVAILABLE")
        self.assertFalse(result["original_launcher_resumed"])
        self.assertFalse(result["original_functionally_accepted"])

    def test_target_scoped_class_equality_never_serializes_private_names(self):
        a = "com.yeelight.internal.HiddenDependency"
        log = (fatal("foreign.app", "com.yeelight.internal.ForeignSecret") +
               fatal(CLONE, a))
        self.assertEqual(m.target_missing_class(log, CLONE), a)
        self.assertIsNone(m.target_missing_class(log, ORIGINAL))
        self.assertEqual(m.target_missing_class(fatal(ORIGINAL, a), ORIGINAL), a)
        clone = {"target_crash": True, "exception_category": "CLASS_LOADER_MISSING",
                 "failure_stage": "PROVIDER_INIT"}
        original = {"target_crash": True, "exception_category": "CLASS_LOADER_MISSING",
                    "failure_stage": "PROVIDER_INIT"}
        same = m.compare_results(clone, a, original, a, False)
        self.assertEqual(same["runtime_relation"], "BOTH_TARGET_CLASS_NOT_FOUND")
        self.assertEqual(same["missing_class_identity_relation"], "SAME")
        distinct = m.compare_results(clone, a, original,
                                     "com.yeelight.internal.OtherSecret", False)
        self.assertEqual(distinct["missing_class_identity_relation"], "DIFFERENT")
        for private in ("HiddenDependency", "OtherSecret", "ForeignSecret"):
            self.assertNotIn(private, json.dumps(same) + json.dumps(distinct))
        self.assertFalse(same["original_functionally_accepted"])
        self.assertFalse(same["clone_functionally_accepted"])

    def test_clone_only_crash_is_not_full_original_functional_acceptance(self):
        clone = {"target_crash": True, "exception_category": "CLASS_LOADER_MISSING",
                 "failure_stage": "PROVIDER_INIT"}
        no_crash = {"target_crash": False, "exception_category": "NONE",
                    "failure_stage": "UNDETERMINED"}
        r = m.compare_results(clone, "com.yeelight.Hidden", no_crash, None, True)
        self.assertEqual(r["runtime_relation"],
                         "CLONE_ONLY_CLASS_LOADER_CRASH_IN_OBSERVED_WINDOW")
        self.assertFalse(r["original_functionally_accepted"])
        inconclusive = m.compare_results(clone, None, no_crash, None, False)
        self.assertEqual(inconclusive["runtime_relation"],
                         "ORIGINAL_NOT_RESUMED_NO_TARGET_CRASH")
        refused = m.report_original_install_refusal(clone)
        self.assertEqual(refused["runtime_relation"], "INSUFFICIENT_EVIDENCE")
        self.assertFalse(refused["original_launcher_resumed"])

    def test_non_classloader_clone_or_bad_input_cannot_enable_comparison(self):
        no = {"target_crash": False, "exception_category": "NONE",
              "failure_stage": "UNDETERMINED"}
        with self.assertRaisesRegex(m.BaselineBlocked,
                                    "BASELINE_COMPARISON_INPUT_INVALID"):
            m.compare_results(no, "com.yeelight.Private", no, None, True)
        for invalid in (b"binary", "X" * (m.MAX_ADB + 1)):
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "BASELINE_BAD_CRASH_INPUT"):
                m.target_missing_class(invalid, CLONE)
        with self.assertRaisesRegex(m.BaselineBlocked, "BASELINE_BAD_CRASH_INPUT"):
            m.target_missing_class("", "foreign.app")

    def test_owner_scope_and_pinned_files_fail_before_any_device_action(self):
        env = {"RUNNER_TEMP": "/tmp/runner", "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj",
               "GITHUB_ACTOR": "lxsdd", "GITHUB_REF": "refs/heads/main",
               "GITHUB_EVENT_NAME": "workflow_dispatch",
               "RUNNER_ENVIRONMENT": "github-hosted"}
        stock = Path("/tmp/runner/private-original/com.yeelight.cherry_3.5.4.apk")
        clone = Path("/tmp/runner/private-output/classic-loft-dj-TEST-SIGNED.apk")
        for bad in (dict(env, GITHUB_ACTOR="intruder"),
                    dict(env, GITHUB_REF="refs/heads/topic"),
                    dict(env, GITHUB_EVENT_NAME="pull_request"),
                    dict(env, GH_TOKEN="secret"), dict(env, RUNNER_ENVIRONMENT="local")):
            with self.subTest(bad=bad), self.assertRaises(m.BaselineBlocked):
                m.require_scope("emulator-5554", stock, clone, True, bad)
        with self.assertRaisesRegex(m.BaselineBlocked,
                                    "BASELINE_OWNER_SCOPE_REQUIRED"):
            m.require_scope("physical-phone", stock, clone, True, env)
        with self.assertRaisesRegex(m.BaselineBlocked,
                                    "BASELINE_OWNER_SCOPE_REQUIRED"):
            m.require_scope("emulator-5554", stock, clone, False, env)
        with self.assertRaisesRegex(m.BaselineBlocked,
                                    "BASELINE_PRIVATE_APK_SCOPE_INVALID"):
            m.require_scope("emulator-5554", stock, Path("/other.apk"), True, env)

    def test_positive_real_path_scope_with_real_files_and_symlink_denial(self):
        # Path(...) is PosixPath/WindowsPath, never type(...) is Path. This
        # exact owner runner gate must accept legitimate pinned regular files.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stock = root / "private-original/com.yeelight.cherry_3.5.4.apk"
            clone = root / "private-output/classic-loft-dj-TEST-SIGNED.apk"
            stock.parent.mkdir()
            clone.parent.mkdir()
            stock.write_bytes(b"synthetic original")
            clone.write_bytes(b"synthetic candidate")
            env = {"RUNNER_TEMP": temp,
                   "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj",
                   "GITHUB_ACTOR": "lxsdd",
                   "GITHUB_REF": "refs/heads/main",
                   "GITHUB_EVENT_NAME": "workflow_dispatch",
                   "RUNNER_ENVIRONMENT": "github-hosted"}
            self.assertIsNone(m.require_scope("emulator-5554", stock, clone, True, env))
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "^BASELINE_PRIVATE_APK_SCOPE_INVALID$"):
                m.require_scope("emulator-5554", str(stock), clone, True, env)
            old_content = stock.read_bytes()
            stock.unlink()
            target = root / "target.bin"
            target.write_bytes(old_content)
            stock.symlink_to(target)
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "^BASELINE_PRIVATE_APK_SCOPE_INVALID$"):
                m.require_scope("emulator-5554", stock, clone, True, env)
            stock.unlink()
            stock.write_bytes(old_content)
            clone.unlink()
            clone.symlink_to(target)
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "^BASELINE_PRIVATE_APK_SCOPE_INVALID$"):
                m.require_scope("emulator-5554", stock, clone, True, env)

    def test_full_mocked_owner_runner_original_launch_and_cleanup_sequence(self):
        """Exercise production CLI logic, not only pure helper functions.

        The synthetic emulator starts with only a signed clone installed;
        its target crash is real-shaped, and the original must be installed,
        hash-checked, launched once, observed, uninstalled, then still FAIL.
        """
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stock = root / "private-original/com.yeelight.cherry_3.5.4.apk"
            clone = root / "private-output/classic-loft-dj-TEST-SIGNED.apk"
            stock.parent.mkdir()
            clone.parent.mkdir()
            stock.write_bytes(b"synthetic pinned OEM fixture, never used as OEM")
            clone.write_bytes(b"synthetic signed candidate")
            clone_pin = hashlib.sha256(clone.read_bytes()).hexdigest()
            clone_path = "/data/app/syntheticclone/base.apk"
            original_path = "/data/app/syntheticoriginal/base.apk"
            original_component = ORIGINAL + "/com.yeelight.cherry.MainActivity"
            original_short = ORIGINAL + "/.MainActivity"
            state = {"clone": True, "original": False, "original_launched": False}
            events = []
            env = {"RUNNER_TEMP": temp, "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj",
                   "GITHUB_ACTOR": "lxsdd", "GITHUB_REF": "refs/heads/main",
                   "GITHUB_EVENT_NAME": "workflow_dispatch",
                   "RUNNER_ENVIRONMENT": "github-hosted"}

            def fake_adb(args, *, timeout=30):
                events.append(tuple(args))
                key = tuple(args)
                offline = {
                    ("shell", "getprop", "ro.kernel.qemu"): "1",
                    ("shell", "getprop", "ro.build.version.release"): "17",
                    ("shell", "id", "-u"): "0",
                    ("shell", "settings", "get", "global", "airplane_mode_on"): "1",
                    ("shell", "settings", "get", "global", "wifi_on"): "0",
                    ("shell", "settings", "get", "global", "mobile_data"): "0",
                    ("shell", "ip", "-4", "route", "show"): "",
                    ("shell", "ip", "-6", "route", "show"): "",
                }
                if key in offline:
                    return offline[key]
                if len(key) == 8 and key[:7] == (
                        "shell", "cmd", "package", "list", "packages", "--user", "0"):
                    if key[7] == CLONE:
                        return "package:" + CLONE if state["clone"] else ""
                    if key[7] == ORIGINAL:
                        return "package:" + ORIGINAL if state["original"] else ""
                    raise AssertionError("Foreign package filter")
                if key == ("shell", "pm", "path", CLONE):
                    self.assertTrue(state["clone"])
                    return "package:" + clone_path
                if key == ("shell", "pm", "path", ORIGINAL):
                    self.assertTrue(state["original"])
                    return "package:" + original_path
                if key == ("shell", "sha256sum", clone_path):
                    return clone_pin + "  " + clone_path
                if key == ("shell", "sha256sum", original_path):
                    return m.ORIGINAL_SHA256 + "  " + original_path
                if key == ("logcat", "-b", "crash", "-d"):
                    if state["original_launched"]:
                        return fatal(ORIGINAL, "com.yeelight.internal.HiddenDependency")
                    return fatal(CLONE, "com.yeelight.internal.HiddenDependency")
                if key == ("shell", "am", "force-stop", CLONE):
                    return ""
                if key == ("uninstall", CLONE):
                    state["clone"] = False
                    return "Success"
                if key == ("shell", "am", "force-stop", ORIGINAL):
                    return ""
                if key == ("uninstall", ORIGINAL):
                    state["original"] = False
                    return "Success"
                if key == ("logcat", "-b", "crash", "-c"):
                    return ""
                if key == ("shell", "dumpsys", "activity", "activities"):
                    return ("topResumedActivity=ActivityRecord{abc u0 "
                            "com.yeelight.cherry/.MainActivity t1}")
                if key[:4] == ("shell", "cmd", "package", "query-activities"):
                    return original_short
                if key[:4] == ("shell", "cmd", "package", "resolve-activity"):
                    return original_short
                raise AssertionError("Unexpected synthetic adb request " + repr(key))

            def fake_run_adb(args, *, timeout=30, allow_failure=False):
                events.append(tuple(args))
                if args == ["install", "--no-streaming", str(stock)]:
                    self.assertFalse(state["clone"])
                    self.assertFalse(state["original"])
                    state["original"] = True
                    return 0, "Success"
                if args == ["shell", "am", "start", "-W", "-n", original_component]:
                    self.assertTrue(state["original"])
                    self.assertFalse(state["original_launched"])
                    state["original_launched"] = True
                    return (0, "Status: ok\nActivity: " + original_component + "\nComplete")
                raise AssertionError("Unexpected synthetic run_adb " + repr(args))

            with mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(m, "inspect_original") as audit, \
                 mock.patch.object(m, "adb", side_effect=fake_adb), \
                 mock.patch.object(m, "run_adb", side_effect=fake_run_adb):
                report = m.compare_private_emulator(
                    stock, clone, serial="emulator-5554", approved=True,
                    pause=lambda seconds: self.assertEqual(seconds, 3))
            audit.assert_called_once_with(stock)
            self.assertEqual(report["runtime_relation"], "BOTH_TARGET_CLASS_NOT_FOUND")
            self.assertEqual(report["missing_class_identity_relation"], "SAME")
            self.assertFalse(report["original_functionally_accepted"])
            self.assertFalse(report["clone_functionally_accepted"])
            self.assertNotIn("HiddenDependency", json.dumps(report))
            self.assertFalse(state["clone"])
            self.assertFalse(state["original"])
            self.assertTrue(state["original_launched"])
            clone_remove = events.index(("uninstall", CLONE))
            original_install = events.index(("install", "--no-streaming", str(stock)))
            original_launch = events.index(
                ("shell", "am", "start", "-W", "-n", original_component))
            original_remove = events.index(("uninstall", ORIGINAL))
            self.assertLess(clone_remove, original_install)
            self.assertLess(original_install, original_launch)
            self.assertLess(original_launch, original_remove)
            self.assertEqual(events.count(
                ("shell", "am", "start", "-W", "-n", original_component)), 1)
            self.assertEqual(events.count(("uninstall", ORIGINAL)), 1)

    def test_absent_original_does_not_call_pm_path(self):
        queries = []
        def absent(args, *, timeout=30):
            queries.append(tuple(args))
            if args == ["shell", "cmd", "package", "list", "packages",
                        "--user", "0", ORIGINAL]:
                return ""
            raise AssertionError("Absent package must not be queried by pm path")
        with mock.patch.object(m, "adb", side_effect=absent):
            self.assertIsNone(m.installed_path(ORIGINAL))
        self.assertEqual(queries, [("shell", "cmd", "package", "list", "packages",
                                   "--user", "0", ORIGINAL)])
        for listing in ("package:com.yeelight.cherry.other",
                        "package:" + ORIGINAL + "\npackage:foreign",
                        "Error: not authorized", "package:"):
            with self.subTest(listing=listing):
                with mock.patch.object(m, "adb", return_value=listing):
                    with self.assertRaisesRegex(
                            m.BaselineBlocked, "^BASELINE_PACKAGE_LIST_INVALID$"):
                        m.installed_path(ORIGINAL)
        with mock.patch.object(m, "adb", return_value="package:" + ORIGINAL):
            with self.assertRaisesRegex(
                    m.BaselineBlocked, "^BASELINE_INSTALLED_APK_INVALID$"):
                m.installed_path(ORIGINAL)

    def test_failed_adb_reports_only_fixed_phase(self):
        phases = [
            (["shell", "cmd", "package", "list", "packages", "--user", "0",
              "com.yeelight.cherry"], "BASELINE_PACKAGE_LIST_ADB_FAILED"),
            (["shell", "pm", "path", "com.yeelight.cherry"],
             "BASELINE_INSTALLED_PATH_ADB_FAILED"),
            (["shell", "sha256sum", "/data/app/PrivateSecret/base.apk"],
             "BASELINE_INSTALLED_HASH_ADB_FAILED"),
            (["shell", "ip", "-6", "route", "show"], "BASELINE_ISOLATION_ADB_FAILED"),
            (["logcat", "-b", "crash", "-d"], "BASELINE_CRASH_BUFFER_ADB_FAILED"),
            (["shell", "cmd", "package", "resolve-activity", "PrivateSecret"],
             "BASELINE_LAUNCHER_QUERY_ADB_FAILED"),
            (["shell", "dumpsys", "activity", "activities"],
             "BASELINE_ACTIVITY_SNAPSHOT_ADB_FAILED"),
            (["uninstall", ORIGINAL], "BASELINE_CLEANUP_ADB_FAILED"),
        ]
        response = mock.Mock(returncode=1, stdout=b"PrivateSecret",
                             stderr=b"PrivateSecret")
        with mock.patch.object(m.subprocess, "run", return_value=response):
            for args, code in phases:
                with self.subTest(args=args), self.assertRaisesRegex(
                        m.BaselineBlocked, "^" + code + "$") as cm:
                    m.run_adb(args)
                self.assertNotIn("PrivateSecret", str(cm.exception))
            status, stdout = m.run_adb(
                ["shell", "pm", "path", ORIGINAL], allow_failure=True)
            self.assertEqual(status, 1)
            self.assertEqual(stdout, "PrivateSecret")

    def test_original_short_component_is_normalized_before_real_launch(self):
        short = ORIGINAL + "/.MainActivity"
        full = ORIGINAL + "/com.yeelight.cherry.MainActivity"
        self.assertEqual(m.resolve_original_launcher(short, short),
                         "com.yeelight.cherry.MainActivity")
        self.assertEqual(m.resolve_original_launcher(short, full),
                         "com.yeelight.cherry.MainActivity")
        for bad_query, bad_resolved, reason in (
            ("No activities found", short, "NO_INSTALLED_LAUNCHER"),
            (short, "No activity found", "RESOLVED_LAUNCHER_MISSING"),
            (short, ORIGINAL + "/.Unlisted", "RESOLVED_LAUNCHER_MISMATCH"),
            (ORIGINAL + "/.Bad..Class", short, "INSTALLED_LAUNCHER_UNTRUSTED"),
            ("com.other/.Hidden", short, "INSTALLED_LAUNCHER_UNTRUSTED"),
        ):
            with self.subTest(reason=reason), self.assertRaisesRegex(
                    m.BaselineBlocked, "^BASELINE_ORIGINAL_LAUNCHER_" + reason + "$") as err:
                m.resolve_original_launcher(bad_query, bad_resolved)
            self.assertNotIn("Hidden", str(err.exception))
        # No exact class/Activity/PackageManager bytes enter fixed errors.
        with mock.patch.object(m, "select_installed_launcher",
                               side_effect=m.CloneLauncherBlocked("PrivateSecret")):
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "^BASELINE_ORIGINAL_LAUNCHER_PRIVATE_INPUT_INVALID$"):
                m.resolve_original_launcher(short, short)

    def test_original_foreground_only_current_global_original_oem_activity(self):
        ok = "topResumedActivity=ActivityRecord{abc u0 com.yeelight.cherry/.MainActivity t1}"
        other = "topResumedActivity=ActivityRecord{abc u0 io.lxsdd.yeelightdj/.MainActivity t1}"
        self.assertTrue(m.original_foreground(ok))
        self.assertFalse(m.original_foreground(other))
        self.assertFalse(m.original_foreground(ok + "\n" + other))
        self.assertFalse(m.original_foreground("mResumedActivity=" + ok))
        self.assertFalse(m.original_foreground(""))

    def test_offline_guard_blocks_routes_and_bad_api_before_install(self):
        def allowed(args, *, timeout=30):
            last = args[-1]
            if last == "ro.kernel.qemu":
                return "1"
            if last == "ro.build.version.release":
                return "17"
            if last == "-u":
                return "0"
            if last in ("airplane_mode_on",):
                return "1"
            if last in ("wifi_on", "mobile_data"):
                return "0"
            if last == "show":
                return ""
            raise AssertionError(args)
        with mock.patch.object(m, "adb", side_effect=allowed):
            m.offline_guard()
        def routed(args, *, timeout=30):
            if args[-1] == "show" and "-4" in args:
                return "default via 10.0.2.2 dev eth0"
            return allowed(args, timeout=timeout)
        with mock.patch.object(m, "adb", side_effect=routed):
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "BASELINE_NETWORK_NOT_ISOLATED"):
                m.offline_guard()
        with mock.patch.object(m, "adb", side_effect=lambda args, **k:
                               "16" if args[-1] == "ro.build.version.release"
                               else allowed(args, **k)):
            with self.assertRaisesRegex(m.BaselineBlocked,
                                        "BASELINE_NOT_ANDROID17"):
                m.offline_guard()

    def test_npe_original_differential_never_becomes_acceptance(self):
        clone = {"target_crash": True, "exception_category": "NULL_DEREFERENCE",
                 "failure_stage": "UNDETERMINED"}
        ok = {"target_crash": False, "exception_category": "NONE",
              "failure_stage": "UNDETERMINED"}
        r = m.compare_results(clone, None, ok, None, True, "METHOD_TEXT_IDENTICAL")
        self.assertEqual(r["runtime_relation"],
                         "CLONE_ONLY_NULL_DEREFERENCE_IN_OBSERVED_WINDOW")
        self.assertEqual(r["null_method_smali_relation"], "METHOD_TEXT_IDENTICAL")
        self.assertEqual(r["missing_class_identity_relation"], "NOT_APPLICABLE")
        self.assertFalse(r["clone_functionally_accepted"])
        self.assertFalse(r["original_functionally_accepted"])
        both = m.compare_results(
            clone, None, {"target_crash": True, "exception_category": "NULL_DEREFERENCE",
                          "failure_stage": "UNDETERMINED"}, None, False)
        self.assertEqual(both["runtime_relation"],
                         "BOTH_NULL_DEREFERENCES_CAUSE_NOT_ESTABLISHED")
        other = m.compare_results(
            clone, None, {"target_crash": True, "exception_category": "METHOD_LINKAGE",
                          "failure_stage": "UNDETERMINED"}, None, False)
        self.assertEqual(other["runtime_relation"],
                         "ORIGINAL_DIFFERENT_OR_UNRESOLVED_CRASH")
        self.assertEqual(m.compare_results(clone, None, ok, None, False)["runtime_relation"],
                         "ORIGINAL_NOT_RESUMED_NO_TARGET_CRASH")
        for bad in ("PrivateSecret", "", 42, None):
            with self.subTest(bad=bad), self.assertRaisesRegex(
                    m.BaselineBlocked, "^BASELINE_NULL_RELATION_INVALID$"):
                m.compare_results(clone, None, ok, None, True, bad)

    def test_private_npe_caller_mock_exercises_original_after_crash(self):
        # This is a production-route guard: an NPE must reach the same
        # owner-only original comparison rather than a NOT_APPLICABLE shortcut.
        source = (Path(__file__).resolve().parents[1] /
                  "private_ci/private_original_baseline.py").read_text("utf-8")
        self.assertIn('"NULL_DEREFERENCE"', source)
        self.assertIn("compare_null_callsite(", source)
        self.assertIn("original, candidate, clone_log", source)
        self.assertIn("report_receiver=True", source)
        self.assertIn("uninstall\", CLONE", source)
        self.assertIn("original_unmodified_and_hash_verified", source)
        self.assertIn("clone_functionally_accepted\": False", source)

    def test_npe_receiver_origin_report_never_overrides_cloned_failure(self):
        c={"target_crash":True,"exception_category":"NULL_DEREFERENCE",
           "failure_stage":"UNDETERMINED"}
        o={"target_crash":False,"exception_category":"NONE",
           "failure_stage":"UNDETERMINED"}
        result=m.compare_results(c,None,o,None,True,"METHOD_TEXT_IDENTICAL",
                                 "INSTANCE_FIELD_LOAD")
        self.assertEqual(result["null_receiver_source"],"INSTANCE_FIELD_LOAD")
        self.assertEqual(result["null_method_smali_relation"],"METHOD_TEXT_IDENTICAL")
        self.assertEqual(result["runtime_relation"],
                         "CLONE_ONLY_NULL_DEREFERENCE_IN_OBSERVED_WINDOW")
        self.assertFalse(result["clone_functionally_accepted"])
        self.assertFalse(result["original_functionally_accepted"])
        with self.assertRaisesRegex(m.BaselineBlocked,"BASELINE_NULL_RECEIVER_INVALID"):
            m.compare_results(c,None,o,None,True,"METHOD_TEXT_IDENTICAL","SECRET_NAME")
        cl={"target_crash":True,"exception_category":"CLASS_LOADER_MISSING",
            "failure_stage":"PROVIDER_INIT"}
        self.assertEqual(m.compare_results(cl,None,o,None,False)
                         ["null_receiver_source"], "NOT_APPLICABLE")



if __name__ == "__main__":
    unittest.main()
