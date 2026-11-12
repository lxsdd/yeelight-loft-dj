"""Synthetic original-vs-clone startup comparison: no OEM APK or ADB."""
import json
from pathlib import Path
import sys
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


if __name__ == "__main__":
    unittest.main()
