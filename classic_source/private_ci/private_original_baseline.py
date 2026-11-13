#!/usr/bin/env python3
"""One owner-gated OFFLINE API37 original-vs-clone startup comparison.

Only after a verified clone class-loader or null-reference FATAL: inspect the
existing crash, compare the null callsite privately when relevant, uninstall
clone, then launch the pinned original once on the SAME isolated emulator.
No OEM class names, raw logs, paths, APKs or digests leave the runner.
No re-launch of the clone, retries, hardware, network or release actions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure, ORIGINAL_SHA256, inspect_original
from android17_start_smoke import (
    APK_PATH, GLOBAL_TOP, TOP_COMPONENT, ROOT_CAUSE, MISSING_QUOTED,
    MISSING_DIRECT, MAX_FATAL_BLOCK_LINES, _android_runtime_message,
    classify_crash,
)
from null_callsite_comparison import (compare_private as compare_null_callsite,
                                      RELATIONS as NULL_RELATIONS, NullSiteBlocked)
from clone_launcher_selection import (
    ORIGINAL, PACKAGE as CLONE, CloneLauncherBlocked, select_installed_launcher,
    REASONS as LAUNCHER_REASONS,
)

SERIAL = "emulator-5554"
MAX_ADB = 2_000_000
OEM_ACTIVITY = re.compile(r"com\.yeelight\.[A-Za-z_$][A-Za-z0-9_.$]*\Z")
PIN = re.compile(r"[0-9a-f]{64}\Z")


class BaselineBlocked(ValueError):
    """Only bounded, constant source-owned failure codes."""


def require(ok, code):
    if not ok:
        raise BaselineBlocked(code)


def require_scope(serial, original, candidate, approved, environ=None):
    env = os.environ if environ is None else environ
    tmp = env.get("RUNNER_TEMP")
    require(approved is True and serial == SERIAL and type(tmp) is str and bool(tmp)
            and env.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj"
            and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_ACTOR") == "lxsdd"
            and env.get("GITHUB_REF") == "refs/heads/main"
            and env.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and not env.get("GH_TOKEN") and not env.get("CLASSIC_VAULT_TOKEN"),
            "BASELINE_OWNER_SCOPE_REQUIRED")
    root = Path(tmp)
    require(isinstance(original, Path) and isinstance(candidate, Path) and
            original == root / "private-original/com.yeelight.cherry_3.5.4.apk"
            and candidate == root / "private-output/classic-loft-dj-TEST-SIGNED.apk"
            and all(p.is_file() and not p.is_symlink() for p in (original, candidate)),
            "BASELINE_PRIVATE_APK_SCOPE_INVALID")


def adb_failure_code(args):
    """Map failed ADB commands to fixed phases without OEM command text."""
    if args[:7] == ["shell", "cmd", "package", "list", "packages", "--user", "0"]:
        return "BASELINE_PACKAGE_LIST_ADB_FAILED"
    if args[:3] == ["shell", "pm", "path"]:
        return "BASELINE_INSTALLED_PATH_ADB_FAILED"
    if args[:2] == ["shell", "sha256sum"]:
        return "BASELINE_INSTALLED_HASH_ADB_FAILED"
    if args[:2] in (["shell", "getprop"], ["shell", "settings"],
                    ["shell", "ip"], ["shell", "id"]):
        return "BASELINE_ISOLATION_ADB_FAILED"
    if args[:3] == ["logcat", "-b", "crash"]:
        return "BASELINE_CRASH_BUFFER_ADB_FAILED"
    if args[:4] in (["shell", "cmd", "package", "query-activities"],
                    ["shell", "cmd", "package", "resolve-activity"]):
        return "BASELINE_LAUNCHER_QUERY_ADB_FAILED"
    if args[:3] == ["shell", "dumpsys", "activity"]:
        return "BASELINE_ACTIVITY_SNAPSHOT_ADB_FAILED"
    if args[:3] == ["shell", "am", "force-stop"] or args[:1] == ["uninstall"]:
        return "BASELINE_CLEANUP_ADB_FAILED"
    return "BASELINE_ADB_COMMAND_FAILED"


def run_adb(args, *, timeout=30, allow_failure=False):
    require(type(args) is list and all(type(s) is str for s in args),
            "BASELINE_ADB_ARGUMENTS_INVALID")
    try:
        p = subprocess.run(["adb", "-s", SERIAL, *args], stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise BaselineBlocked("BASELINE_ADB_UNAVAILABLE") from None
    require(len(p.stdout) <= MAX_ADB and len(p.stderr) <= MAX_ADB,
            "BASELINE_ADB_OUTPUT_OVERSIZED")
    if not allow_failure:
        require(p.returncode == 0, adb_failure_code(args))
    try:
        return p.returncode, p.stdout.decode("utf-8", errors="strict").strip()
    except UnicodeDecodeError:
        raise BaselineBlocked("BASELINE_ADB_OUTPUT_INVALID") from None


def adb(args, *, timeout=30):
    return run_adb(args, timeout=timeout)[1]


def installed_path(package):
    require(package in (ORIGINAL, CLONE), "BASELINE_PACKAGE_OUT_OF_SCOPE")
    # Android17 may exit with code 1 from pm path if a package is absent.
    # A successful PackageManager list must prove exact installation first.
    listed = adb(["shell", "cmd", "package", "list", "packages",
                  "--user", "0", package]).splitlines()
    require(listed in ([], ["package:" + package]),
            "BASELINE_PACKAGE_LIST_INVALID")
    if not listed:
        return None
    raw = adb(["shell", "pm", "path", package]).splitlines()
    require(len(raw) == 1 and raw[0].startswith("package:") and
            APK_PATH.fullmatch(raw[0][8:]), "BASELINE_INSTALLED_APK_INVALID")
    return raw[0][8:]


def verify_installed_hash(package, digest):
    require(type(digest) is str and PIN.fullmatch(digest),
            "BASELINE_APK_PIN_INVALID")
    path = installed_path(package)
    require(path is not None, "BASELINE_EXPECTED_PACKAGE_MISSING")
    parts = adb(["shell", "sha256sum", path]).split()
    require(len(parts) == 2 and parts[0] == digest and parts[1] == path,
            "BASELINE_INSTALLED_APK_HASH_MISMATCH")


def offline_guard():
    require(adb(["shell", "getprop", "ro.kernel.qemu"]) == "1",
            "BASELINE_NOT_AN_EMULATOR")
    release = adb(["shell", "getprop", "ro.build.version.release"])
    require(release == "17" or release.startswith("17."),
            "BASELINE_NOT_ANDROID17")
    require(adb(["shell", "id", "-u"]) == "0", "BASELINE_EMULATOR_NOT_ROOT")
    for key, expected in (("airplane_mode_on", "1"), ("wifi_on", "0"),
                          ("mobile_data", "0")):
        require(adb(["shell", "settings", "get", "global", key]) == expected,
                "BASELINE_NETWORK_NOT_ISOLATED")
    for version in ("-4", "-6"):
        routes = adb(["shell", "ip", version, "route", "show"])
        require(not any(" dev lo" not in line for line in routes.splitlines() if line),
                "BASELINE_NETWORK_NOT_ISOLATED")


def target_missing_class(logcat, package):
    """Only strict target AndroidRuntime FATAL causes; raw names stay in memory."""
    require(type(logcat) is str and len(logcat) <= MAX_ADB and
            package in (ORIGINAL, CLONE), "BASELINE_BAD_CRASH_INPUT")
    lines = logcat.splitlines()
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" not in _android_runtime_message(line):
            continue
        block = []
        for row in lines[i + 1:i + 1 + MAX_FATAL_BLOCK_LINES]:
            if "FATAL EXCEPTION" in _android_runtime_message(row):
                break
            block.append(row)
        process = re.search(r"\bProcess:\s*([A-Za-z0-9_.:]+)\s*,\s*PID:",
                            "\n".join(_android_runtime_message(s)
                                      for s in block[:14]))
        if process is None or not (process.group(1) == package or
                                   process.group(1).startswith(package + ":")):
            continue
        missing = None
        for row in block:
            message = _android_runtime_message(row)
            root = ROOT_CAUSE.match(message)
            if root is None or root.group(2).rsplit(".", 1)[-1] != "ClassNotFoundException":
                continue
            match = MISSING_QUOTED.search(message) or MISSING_DIRECT.search(message)
            if match is not None:
                missing = match.group(1)
        return missing
    return None


def original_foreground(snapshot):
    """Recognize only the CURRENT global top, not stale task history."""
    require(type(snapshot) is str and len(snapshot) <= MAX_ADB,
            "BASELINE_ACTIVITY_SNAPSHOT_INVALID")
    found = []
    for line in snapshot.splitlines():
        match = GLOBAL_TOP.fullmatch(line)
        if match is None:
            continue
        payload = match.group(1)
        if not payload.startswith("ActivityRecord{") or not payload.endswith("}"):
            return False
        parts = TOP_COMPONENT.findall(payload)
        if len(parts) != 1:
            return False
        pkg, cls = parts[0]
        if cls.startswith("."):
            cls = pkg + cls
        found.append((pkg, cls))
    return len(found) > 0 and len(set(found)) == 1 and found[0][0] == ORIGINAL and bool(
        OEM_ACTIVITY.fullmatch(found[0][1]))


def compare_results(clone_crash, clone_missing, original_crash,
                    original_missing, original_resumed,
                    null_method_relation="UNAVAILABLE"):
    """Closed vocabulary differential; never convert a crash into acceptance."""
    kind = (clone_crash.get("exception_category") if type(clone_crash) is dict else None)
    require(type(clone_crash) is dict and clone_crash.get("target_crash") is True
            and kind in ("CLASS_LOADER_MISSING", "NULL_DEREFERENCE")
            and type(original_crash) is dict and type(original_resumed) is bool,
            "BASELINE_COMPARISON_INPUT_INVALID")
    if kind == "NULL_DEREFERENCE":
        require(null_method_relation in NULL_RELATIONS,
                "BASELINE_NULL_RELATION_INVALID")
        relation = "NOT_APPLICABLE"
        if original_crash.get("target_crash"):
            result = ("BOTH_NULL_DEREFERENCES_CAUSE_NOT_ESTABLISHED" if
                      original_crash.get("exception_category") == "NULL_DEREFERENCE"
                      else "ORIGINAL_DIFFERENT_OR_UNRESOLVED_CRASH")
        elif original_resumed:
            result = "CLONE_ONLY_NULL_DEREFERENCE_IN_OBSERVED_WINDOW"
        else:
            result = "ORIGINAL_NOT_RESUMED_NO_TARGET_CRASH"
    else:
        relation = ("SAME" if clone_missing and original_missing and
                    clone_missing == original_missing else
                    "DIFFERENT" if clone_missing and original_missing else
                    "UNAVAILABLE")
        if original_crash.get("target_crash"):
            result = ("BOTH_TARGET_CLASS_NOT_FOUND" if
                      original_crash.get("exception_category") == "CLASS_LOADER_MISSING" and
                      relation == "SAME" else "ORIGINAL_DIFFERENT_OR_UNRESOLVED_CRASH")
        elif original_resumed:
            result = "CLONE_ONLY_CLASS_LOADER_CRASH_IN_OBSERVED_WINDOW"
        else:
            result = "ORIGINAL_NOT_RESUMED_NO_TARGET_CRASH"
    return {
        "status": "PRIVATE_ORIGINAL_COMPARISON_DIAGNOSTIC_ONLY",
        "runtime_relation": result,
        "missing_class_identity_relation": relation,
        "null_method_smali_relation": (null_method_relation if kind == "NULL_DEREFERENCE"
                                       else "NOT_APPLICABLE"),
        "null_site_diagnostic_not_root_cause_proof": True,
        "clone_exception_category": kind,
        "clone_failure_stage": clone_crash["failure_stage"],
        "original_exception_category": original_crash.get("exception_category", "NONE"),
        "original_failure_stage": original_crash.get("failure_stage", "UNDETERMINED"),
        "original_target_crash_observed": bool(original_crash.get("target_crash")),
        "original_launcher_resumed": original_resumed,
        "same_offline_api37_emulator": True,
        "original_unmodified_and_hash_verified": True,
        "original_functionally_accepted": False,
        "clone_functionally_accepted": False,
        "raw_private_payload_published": False,
    }



def report_original_install_refusal(clone_crash):
    return {
        "status": "PRIVATE_ORIGINAL_INSTALL_REFUSED_BASELINE_INCONCLUSIVE",
        "runtime_relation": "INSUFFICIENT_EVIDENCE",
        "original_target_crash_observed": False,
        "original_launcher_resumed": False,
        "clone_exception_category": clone_crash["exception_category"],
        "same_offline_api37_emulator": True,
        "original_functionally_accepted": False,
        "clone_functionally_accepted": False,
        "raw_private_payload_published": False,
    }


def resolve_original_launcher(queried, resolved):
    """Validate Android's absolute OR short ComponentName, with safe errors."""
    try:
        return select_installed_launcher(queried, resolved, package=ORIGINAL)
    except CloneLauncherBlocked as error:
        reason = str(error)
        if reason not in LAUNCHER_REASONS:
            reason = "PRIVATE_INPUT_INVALID"
        raise BaselineBlocked("BASELINE_ORIGINAL_LAUNCHER_" + reason) from None


def compare_private_emulator(original, candidate, *, serial, approved,
                             pause=time.sleep):
    require_scope(serial, original, candidate, approved)
    try:
        inspect_original(original)
    except (GateFailure, OSError, ValueError):
        raise BaselineBlocked("BASELINE_ORIGINAL_INTEGRITY_FAILED") from None
    with candidate.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    offline_guard()
    verify_installed_hash(CLONE, digest)
    require(installed_path(ORIGINAL) is None,
            "BASELINE_ORIGINAL_ALREADY_INSTALLED")
    # The candidate smoke has already performed exactly one launch. The crash
    # buffer still contains its target FATAL; do not launch the clone again.
    clone_log = adb(["logcat", "-b", "crash", "-d"])
    clone_crash = classify_crash(clone_log, package=CLONE)
    if not (clone_crash["target_crash"] and
            clone_crash["exception_category"] in ("CLASS_LOADER_MISSING",
                                                   "NULL_DEREFERENCE")):
        return {
            "status": "PRIVATE_ORIGINAL_BASELINE_NOT_APPLICABLE",
            "reason": "CLONE_SUPPORTED_FATAL_NOT_CONFIRMED",
            "original_app_started": False,
            "raw_private_payload_published": False,
        }
    clone_missing = (target_missing_class(clone_log, CLONE)
                     if clone_crash["exception_category"] == "CLASS_LOADER_MISSING"
                     else None)
    # Offline bytecode read-only comparison runs before uninstalling the clone.
    try:
        null_relation = (compare_null_callsite(original, candidate, clone_log,
                         Path(os.environ["RUNNER_TEMP"]))
                         if clone_crash["exception_category"] == "NULL_DEREFERENCE"
                         else "UNAVAILABLE")
    except NullSiteBlocked as exc:
        raise BaselineBlocked(str(exc)) from None

    # This is an isolated emulator only. Uninstall the test clone BEFORE the
    # original install; no overlapping providers, data, app IDs or signatures.
    adb(["shell", "am", "force-stop", CLONE])
    adb(["uninstall", CLONE], timeout=45)
    require(installed_path(CLONE) is None,
            "BASELINE_CLONE_UNINSTALL_INCOMPLETE")
    try:
        offline_guard()
        code, _output = run_adb(
            ["install", "--no-streaming", str(original)],
            timeout=90, allow_failure=True)
        if code != 0:
            return report_original_install_refusal(clone_crash)
        verify_installed_hash(ORIGINAL, ORIGINAL_SHA256)
        # Resolve the ORIGINAL package from Android PackageManager, not aapt
        # heuristics and never a synthetic DJ/native Activity.
        action = ["-a", "android.intent.action.MAIN", "-c",
                  "android.intent.category.LAUNCHER", "-p", ORIGINAL]
        queried = adb(["shell", "cmd", "package", "query-activities",
                       "--components", "--user", "0", *action])
        resolved = adb(["shell", "cmd", "package", "resolve-activity",
                        "--components", "--user", "0", *action])
        launcher = resolve_original_launcher(queried, resolved)
        offline_guard()
        adb(["logcat", "-b", "crash", "-c"])
        adb(["shell", "am", "force-stop", ORIGINAL])
        code, launch = run_adb(
            ["shell", "am", "start", "-W", "-n", ORIGINAL + "/" + launcher],
            timeout=35, allow_failure=True)
        pause(3)
        snapshot = adb(["shell", "dumpsys", "activity", "activities"])
        logcat = adb(["logcat", "-b", "crash", "-d"])
        original_crash = classify_crash(logcat, package=ORIGINAL)
        original_missing = (target_missing_class(logcat, ORIGINAL)
                            if clone_crash["exception_category"] == "CLASS_LOADER_MISSING"
                            else None)
        activity_reported = re.search(r"^Activity:\s*(.+?)\s*$", launch, re.M)
        valid_am = (code == 0 and bool(re.search(r"^Status:\s*ok\s*$", launch, re.M))
                    and bool(re.search(r"^Complete\s*$", launch, re.M))
                    and activity_reported is not None
                    and activity_reported.group(1).startswith(ORIGINAL + "/"))
        resumed = bool(valid_am and original_foreground(snapshot) and
                       not original_crash["target_crash"] and
                       not original_crash["unattributed_fatal"])
        return compare_results(clone_crash, clone_missing, original_crash,
                               original_missing, resumed, null_relation)
    finally:
        # Always remove the original and any emulator app data. Nothing may
        # survive outside this disposable emulator runner or its cleanup trap.
        if installed_path(ORIGINAL) is not None:
            adb(["shell", "am", "force-stop", ORIGINAL])
            adb(["uninstall", ORIGINAL], timeout=45)
            require(installed_path(ORIGINAL) is None,
                    "BASELINE_ORIGINAL_UNINSTALL_INCOMPLETE")


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--serial", required=True)
    p.add_argument("--explicit-owner-approved-private-emulator",
                   action="store_true")
    args = p.parse_args(argv)
    try:
        report = compare_private_emulator(
            args.original, args.candidate, serial=args.serial,
            approved=args.explicit_owner_approved_private_emulator)
    except BaselineBlocked as e:
        print(json.dumps({"status": "PRIVATE_ORIGINAL_BASELINE_BLOCKED",
                          "code": str(e)}, sort_keys=True))
        return 2
    except Exception:
        # No details from private OEM code, adb, paths or APK objects escape.
        print(json.dumps({"status": "PRIVATE_ORIGINAL_BASELINE_BLOCKED",
                          "code": "BASELINE_UNEXPECTED_ERROR"}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
