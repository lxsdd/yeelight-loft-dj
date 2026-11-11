#!/usr/bin/env python3
"""Owner-gated, disposable Android-17 *stock* Classic launch evidence.

The unchanged manufacturer-signed vault APK is installed by the protected
wrapper, NOT built or re-signed here. No real phone, LAN, login, media, OEM
identifiers or raw logs reach output. This is a stock baseline only, never
an acceptance of the modified Loft DJ clone or dormant OEM classes.
"""
import argparse
import json
import re
import subprocess
import sys
import time

PACKAGE = "com.yeelight.cherry"
SERIAL = "emulator-5554"
MAX_OUTPUT = 1_000_000
APK_PATH = re.compile(r"/data/app/[-A-Za-z0-9_@+~/.=]+/base\.apk\Z")
LAUNCHER = re.compile(r"com\.yeelight\.[A-Za-z0-9_.$]+\Z")
PIN = re.compile(r"[a-f0-9]{64}\Z")
EXCEPTION_KINDS = (
    ("NoClassDefFoundError", "MISSING_RUNTIME_DEFINITION"),
    ("ClassNotFoundException", "CLASS_LOADER_MISSING"),
    ("VerifyError", "DEX_VERIFICATION_FAILED"),
    ("UnsatisfiedLinkError", "NATIVE_LINK_FAILURE"),
    ("SecurityException", "PERMISSION_ERROR"),
    ("InflateException", "LAYOUT_INFLATION_ERROR"),
    ("ClassCastException", "TYPE_CONTRACT_ERROR"),
)


class StockBlocked(ValueError):
    """Fixed safe reason; never embed original component names or exception text."""


def need(valid, code):
    if not valid:
        raise StockBlocked(code)


def command(args, serial):
    need(serial == SERIAL, "STOCK_NOT_DISPOSABLE_EMULATOR")
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True,
                            text=True, timeout=35, check=False)
    need(result.returncode == 0, "STOCK_ADB_COMMAND_FAILED")
    need(len(result.stdout) <= MAX_OUTPUT and len(result.stderr) <= MAX_OUTPUT,
         "STOCK_ADB_UNBOUNDED_OUTPUT")
    return result.stdout


def offline_snapshot(adb, serial):
    """Must complete BEFORE install/launch. A fake phone must fail closed."""
    need(adb(["shell", "getprop", "ro.kernel.qemu"], serial).strip() == "1",
         "STOCK_NOT_DISPOSABLE_EMULATOR")
    need(adb(["shell", "getprop", "ro.build.version.sdk"], serial).strip() == "37",
         "STOCK_NOT_ANDROID17")
    need(adb(["shell", "settings", "get", "global", "airplane_mode_on"], serial).strip() == "1",
         "STOCK_EMULATOR_NOT_OFFLINE")
    for value in ("wifi_on", "mobile_data"):
        need(adb(["shell", "settings", "get", "global", value], serial).strip() == "0",
             "STOCK_EMULATOR_NOT_OFFLINE")
    for mode in ("-4", "-6"):
        routes = adb(["shell", "ip", mode, "route", "show", "table", "main"], serial)
        need(not routes.strip(), "STOCK_EMULATOR_ROUTE_NOT_ISOLATED")
    return True


def categorize_crash(raw):
    """Classify by process, never serialize logcat or private class symbols."""
    need(type(raw) is str and len(raw) <= MAX_OUTPUT, "STOCK_LOGCAT_UNVERIFIED")
    target = False
    ambiguous = False
    kind = "NONE"
    for lines in (raw.splitlines(),):
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" not in line:
                continue
            frame = "\n".join(lines[i + 1:i + 18])
            process = re.search(r"\bProcess:\s*([A-Za-z0-9_.:]+)\s*,\s*PID:", frame)
            if process is None:
                ambiguous = True
                continue
            if process.group(1) != PACKAGE and not process.group(1).startswith(PACKAGE + ":"):
                continue
            target = True
            kind = next((out for marker, out in EXCEPTION_KINDS if marker in frame),
                        "OTHER_FATAL_RUNTIME")
            break
    return {"target_crash": target, "unattributed_fatal": ambiguous,
            "exception_category": kind}


def launched(snapshot, activity):
    need(type(snapshot) is str and len(snapshot) <= MAX_OUTPUT,
         "STOCK_ACTIVITY_SNAPSHOT_UNVERIFIED")
    full = PACKAGE + "/" + activity
    short = PACKAGE + "/." + activity[len(PACKAGE) + 1:] if activity.startswith(PACKAGE + ".") else None
    for line in snapshot.splitlines():
        if not re.search(r"(?:mResumedActivity|topResumedActivity|ResumedActivity)\s*[:=]", line):
            continue
        if any(re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w.$])", line)
               for name in (full, short) if name):
            return True
    return False


def probe(*, serial, launcher, pinned_sha256, owner_approved=False,
          adb=command, pause=time.sleep):
    """No ADB call before explicit owner, package and exact fingerprint checks."""
    need(owner_approved is True, "STOCK_EXPLICIT_OWNER_APPROVAL_REQUIRED")
    need(serial == SERIAL, "STOCK_NOT_DISPOSABLE_EMULATOR")
    need(type(launcher) is str and LAUNCHER.fullmatch(launcher) is not None,
         "STOCK_LAUNCH_COMPONENT_INVALID")
    need(type(pinned_sha256) is str and PIN.fullmatch(pinned_sha256),
         "STOCK_PIN_UNVERIFIED")
    from classic_first import ORIGINAL_SHA256
    need(pinned_sha256 == ORIGINAL_SHA256, "STOCK_PIN_UNVERIFIED")
    offline_snapshot(adb, serial)
    paths = adb(["shell", "pm", "path", PACKAGE], serial).strip().splitlines()
    need(len(paths) == 1 and paths[0].startswith("package:"),
         "STOCK_INSTALLED_SPLIT_SET_UNVERIFIED")
    installed_path = paths[0][len("package:"):]
    need(APK_PATH.fullmatch(installed_path), "STOCK_INSTALLED_PATH_UNVERIFIED")
    stored = adb(["shell", "sha256sum", installed_path], serial).strip().split()
    need(len(stored) == 2 and stored[0] == pinned_sha256 and stored[1] == installed_path,
         "STOCK_INSTALLED_APK_HASH_MISMATCH")
    # Clear crash ring immediately before first stock launch; never print it.
    adb(["logcat", "-b", "crash", "-c"], serial)
    output = adb(["shell", "am", "start", "-W", "-n", PACKAGE + "/" + launcher], serial)
    need("Status: ok" in output and "Error:" not in output,
         "STOCK_ACTIVITY_START_FAILED")
    pause(3)
    observed = offline_snapshot(adb, serial)
    current = adb(["shell", "dumpsys", "activity", "activities"], serial)
    crash = categorize_crash(adb(["logcat", "-b", "crash", "-d"], serial))
    pid = adb(["shell", "pidof", PACKAGE], serial).strip()
    running = bool(re.fullmatch(r"[0-9]+(?:\s+[0-9]+)*", pid))
    visible = launched(current, launcher)
    safe = observed and running and visible and not crash["target_crash"] and not crash["unattributed_fatal"]
    return {
        "status": "STOCK_ORIGINAL_OFFLINE_LAUNCH_OBSERVED" if safe else
                  "STOCK_ORIGINAL_OFFLINE_LAUNCH_UNVERIFIED",
        "stock_original_apk_identity_verified": True,
        "installed_split_set_single_base_observed": True,
        "android_17_disposable_emulator_observed": True,
        "offline_before_and_after_launch": True,
        "stock_process_running": running,
        "stock_launcher_resumed": visible,
        "crash_summary": crash,
        "stock_launch_verified": bool(safe),
        "loft_dj_clone_built": False,
        "loft_dj_clone_tested": False,
        "all_oem_classes_linkable_proven": False,
        "missing_manifest_activities_safe_to_delete_proven": False,
        "missing_parent_types_resolved_proven": False,
        "physical_device_tested": False,
        "network_or_account_used": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--launcher", required=True)
    parser.add_argument("--pinned-sha256", required=True)
    parser.add_argument("--explicit-owner-approved-private-emulator", action="store_true")
    args = parser.parse_args()
    try:
        result = probe(serial=args.serial, launcher=args.launcher,
                       pinned_sha256=args.pinned_sha256,
                       owner_approved=args.explicit_owner_approved_private_emulator)
    except Exception:
        print(json.dumps({"status": "BLOCKED", "reason": "STOCK_ORIGINAL_EMULATOR_UNVERIFIED"},
                         sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result["stock_launch_verified"] else 2


if __name__ == "__main__":
    sys.exit(main())
