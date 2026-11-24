#!/usr/bin/env python3
"""One protected no-network package-only Classic startup ablation.

Source is the audited identity control with ONLY manifest package changed.
Runs after real clone failure, original comparison and identity comparison.
Never labels the candidate functional and never publishes private values.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import inspect_original, digest_file
from private_original_baseline import (
    ORIGINAL, CLONE, SERIAL, BaselineBlocked, require, adb, run_adb,
    installed_path, verify_installed_hash, offline_guard,
)
from clone_launcher_selection import select_installed_launcher, CloneLauncherBlocked
from android17_start_smoke import classify_crash, _classic_foreground

NAME = "classic-loft-dj-PACKAGE-TEST-SIGNED.apk"
UNSIGNED = "classic-loft-dj-PACKAGE-UNSIGNED.apk"
STATUS = "PRIVATE_CLASSIC_PACKAGE_ONLY_CONTROL_DIAGNOSTIC_ONLY"

def scope(original, candidate, *, serial, approved, env=None):
    e = os.environ if env is None else env
    temp = e.get("RUNNER_TEMP")
    require(approved is True and serial == SERIAL and bool(temp)
            and e.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj"
            and e.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and e.get("GITHUB_REF") == "refs/heads/main"
            and e.get("GITHUB_ACTOR") == "lxsdd"
            and e.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and not e.get("GH_TOKEN") and not e.get("CLASSIC_VAULT_TOKEN"),
            "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED")
    root = Path(temp)
    require(original == root / "private-original/com.yeelight.cherry_3.5.4.apk"
            and candidate == root / "private-output" / NAME
            and all(p.is_file() and not p.is_symlink()
                    for p in (original, candidate)),
            "PACKAGE_ONLY_PRIVATE_PATH_DENIED")
    unsigned = candidate.with_name(UNSIGNED)
    report = unsigned.with_suffix(unsigned.suffix + ".preflight.json")
    receipt = candidate.with_name(candidate.name + ".test-signing.json")
    require(all(p.is_file() and not p.is_symlink() and p.stat().st_size < 50000
                for p in (report, receipt)) and unsigned.is_file(),
            "PACKAGE_ONLY_RECEIPTS_MISSING")
    try:
        a = json.loads(report.read_text("utf-8"))
        b = json.loads(receipt.read_text("utf-8"))
    except (ValueError, OSError, UnicodeError):
        raise BaselineBlocked("PACKAGE_ONLY_RECEIPTS_INVALID") from None
    require(a.get("status") == "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED"
            and a.get("package") == CLONE
            and a.get("package_only_control_offline_only") is True
            and a.get("identity_control_offline_only") is False
            and a.get("only_binary_manifest_package_changed") is True
            and a.get("dex_reassembly_mode") == "raw"
            and a.get("original_oem_dex_intentionally_changed") is False
            and all(v == 0 for v in a.get("controlled_authority_string_remaps",{}).values())
            and a.get("apk_sha256") == digest_file(unsigned)
            and b.get("package") == CLONE and b.get("package_only_control_offline_only") is True
            and b.get("signed") is True and b.get("installed") is False
            and b.get("signature") == "EPHEMERAL_UNIQUE_TEST_KEY_NOT_SUITABLE_FOR_APP_UPDATES"
            and b.get("unsigned_sha256") == digest_file(unsigned)
            and b.get("apk_sha256") == digest_file(candidate)
            and b.get("source_build_report_sha256") == digest_file(report),
            "PACKAGE_ONLY_AUDIT_BINDING_FAILED")
    inspect_original(original)
    return digest_file(candidate)

def classify_result(code, launch, snapshot, crash):
    valid = (code == 0 and
             bool(re.search(r"^Status:\s*ok\s*$", launch, re.M)) and
             bool(re.search(r"^Complete\s*$", launch, re.M)) and
             bool(re.search(r"^Activity:\s*" + re.escape(CLONE) + r"/", launch, re.M)))
    foreground = valid and _classic_foreground(snapshot)[0]
    state = ("NULL_DEREFERENCE" if crash["target_crash"] and
             crash["exception_category"] == "NULL_DEREFERENCE" else
             "CLASS_LOADER_MISSING" if crash["target_crash"] and
             crash["exception_category"] == "CLASS_LOADER_MISSING" else
             "OTHER_TARGET_FATAL" if crash["target_crash"] or crash["unattributed_fatal"]
             else "RESUMED_NO_TARGET_FATAL" if foreground else "NOT_RESUMED")
    return {"status": STATUS, "observed_start_state": state,
            "observed_window_ms": 3000,
            "oem_dex_and_authorities_preserved": True,
            "only_manifest_package_ablation": True,
            "classic_functionally_accepted": False,
            "native_loft_dj_functionally_accepted": False,
            "physical_device_tested": False,
            "raw_private_payload_published": False}

def probe(original, candidate, *, serial, approved, pause=time.sleep):
    digest = scope(original, candidate, serial=serial, approved=approved)
    offline_guard()
    require(installed_path(ORIGINAL) is None and installed_path(CLONE) is None,
            "PACKAGE_ONLY_INSTALL_COLLISION")
    try:
        code, _ = run_adb(["install", "--no-streaming", str(candidate)],
                          timeout=90, allow_failure=True)
        if code:
            return {"status": STATUS, "observed_start_state": "INSTALL_REJECTED",
                    "classic_functionally_accepted": False,
                    "native_loft_dj_functionally_accepted": False,
                    "raw_private_payload_published": False}
        verify_installed_hash(CLONE, digest)
        offline_guard()
        intent = ["-a", "android.intent.action.MAIN", "-c",
                  "android.intent.category.LAUNCHER", "-p", CLONE]
        q = adb(["shell", "cmd", "package", "query-activities",
                 "--components", "--user", "0", *intent])
        r = adb(["shell", "cmd", "package", "resolve-activity",
                 "--components", "--user", "0", *intent])
        launcher = select_installed_launcher(q, r, package=CLONE)
        offline_guard()
        adb(["logcat", "-b", "crash", "-c"])
        adb(["shell", "am", "force-stop", CLONE])
        code, launch = run_adb(
            ["shell", "am", "start", "-W", "-n", CLONE + "/" + launcher],
            timeout=35, allow_failure=True)
        pause(3)
        snapshot = adb(["shell", "dumpsys", "activity", "activities"])
        logcat = adb(["logcat", "-b", "crash", "-d"])
        return classify_result(code, launch, snapshot, classify_crash(logcat, package=CLONE))
    finally:
        if installed_path(CLONE) is not None:
            adb(["shell", "am", "force-stop", CLONE])
            adb(["uninstall", CLONE], timeout=45)
            require(installed_path(CLONE) is None, "PACKAGE_ONLY_UNINSTALL_INCOMPLETE")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--serial", required=True)
    p.add_argument("--explicit-owner-approved-private-emulator", action="store_true")
    a = p.parse_args()
    try:
        print(json.dumps(probe(a.original, a.candidate, serial=a.serial,
                               approved=a.explicit_owner_approved_private_emulator),
                         sort_keys=True))
        return 0
    except (BaselineBlocked, CloneLauncherBlocked, OSError, ValueError, TypeError):
        print(json.dumps({"status": "PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED",
                          "code": "PACKAGE_ONLY_GUARD_OR_RUNTIME_FAILED"}))
        return 2
    except Exception:
        print(json.dumps({"status": "PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED",
                          "code": "PACKAGE_ONLY_UNEXPECTED_ERROR"}))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
