#!/usr/bin/env python3
"""Owner-only isolated Android17 *identity-preserving* Classic startup control.

Tests a privately rebuilt version of the pinned OEM Classic with its original
applicationId, authority strings and OEM DEX bytes but the same two disabled
legacy providers, DJ sidecar and added layout as the failing fork. An additional
fresh ephemeral test certificate is used; no signing identity is published.
Only after the stock signed original's comparative run has been fully cleaned.
This discriminates an identity/authority/DEX *bundle*, NOT one variable.
Never installs on phones, uses network or performs hardware operations.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import ORIGINAL_SHA256, digest_file, inspect_original
from private_original_baseline import (
    ORIGINAL, CLONE, SERIAL, BaselineBlocked, require, adb, run_adb,
    installed_path, verify_installed_hash, offline_guard,
    resolve_original_launcher, original_foreground,
)
from android17_start_smoke import (
    classify_crash, classify_visible_ui, entry_button_coordinates,
    SmokeBlocked, DJ_ACTIVITY, GLOBAL_TOP, TOP_COMPONENT,
)

SIGNED = "classic-loft-dj-IDENTITY-TEST-SIGNED.apk"
UNSIGNED = "classic-loft-dj-IDENTITY-UNSIGNED.apk"
MAX_REPORT = 50000



ENTRY_LOG_RE = re.compile(
    r"(?:^|\s)(?:I/)?ClassicDjProbe(?:\s*\(\d+\))?\s*:\s*"
    r"(ENTRY_PROVIDER_REGISTERED|ENTRY_ACTIVITY_RESUMED|"
    r"ENTRY_ROOT_UNAVAILABLE|ENTRY_ALREADY_PRESENT|"
    r"ENTRY_ADDED_TO_ACTIVITY|ENTRY_VIEW_LAID_OUT|"
    r"ENTRY_VIEW_NOT_LAID_OUT)\s*$"
)


def classify_private_entry_markers(logcat: str) -> dict:
    """Closed-vocabulary bridge-only telemetry, never publish raw logcat."""
    require(type(logcat) is str and len(logcat) <= 2_000_000,
            "IDENTITY_ENTRY_LOG_INVALID")
    observed = set()
    for row in logcat.splitlines()[:10000]:
        match = ENTRY_LOG_RE.search(row)
        if match is not None:
            observed.add(match.group(1))
    return {
        "status": ("PRIVATE_ENTRY_MARKERS_OBSERVED_NOT_UI_ACCEPTED" if observed
                   else "PRIVATE_ENTRY_MARKERS_ABSENT_NOT_ACCEPTED"),
        "provider_registered": "ENTRY_PROVIDER_REGISTERED" in observed,
        "activity_resumed": "ENTRY_ACTIVITY_RESUMED" in observed,
        "root_unavailable": "ENTRY_ROOT_UNAVAILABLE" in observed,
        "entry_added_to_activity": "ENTRY_ADDED_TO_ACTIVITY" in observed,
        "entry_already_present": "ENTRY_ALREADY_PRESENT" in observed,
        "entry_view_laid_out": "ENTRY_VIEW_LAID_OUT" in observed,
        "entry_view_not_laid_out": "ENTRY_VIEW_NOT_LAID_OUT" in observed,
        "ui_visibility_proven": False,
        "raw_logcat_published": False,
    }



PRIVATE_UI_FILE = "/data/local/tmp/classic-dj-owner-only-ui.xml"


def capture_private_hierarchy(run=run_adb):
    """Read a bounded UI hierarchy from an ephemeral offline emulator file.

    Caller already checks pinned APK, exact owner and isolated emulator.
    Never publish the raw hierarchy; fail closed if removal cannot be proven.
    """
    try:
        code, _ = run(["shell", "rm", "-f", PRIVATE_UI_FILE],
                      timeout=15, allow_failure=True)
        require(code == 0, "IDENTITY_UI_TEMP_PREPARE_FAILED")
        code, _ = run(["shell", "uiautomator", "dump", PRIVATE_UI_FILE],
                      timeout=40, allow_failure=True)
        if code != 0:
            return None, "UIAUTOMATOR_DUMP_FAILED"
        code, raw = run(["shell", "cat", PRIVATE_UI_FILE],
                        timeout=20, allow_failure=True)
        if code != 0:
            return None, "UI_DUMP_FILE_UNREADABLE"
        if not isinstance(raw, str) or "<?xml" not in raw or "</hierarchy>" not in raw:
            return None, "UI_DUMP_HIERARCHY_MISSING"
        return raw, "UI_DUMP_FILE_OBSERVED_NOT_ACCEPTED"
    finally:
        code, _ = run(["shell", "rm", "-f", PRIVATE_UI_FILE],
                      timeout=15, allow_failure=True)
        require(code == 0, "IDENTITY_UI_TEMP_CLEANUP_FAILED")


def scope(original: Path, candidate: Path, *, serial: str, approved: bool,
          env=None):
    e = os.environ if env is None else env
    temp = e.get("RUNNER_TEMP")
    require(approved is True and serial == SERIAL and bool(temp)
            and e.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj"
            and e.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and e.get("GITHUB_ACTOR") == "lxsdd"
            and e.get("GITHUB_REF") == "refs/heads/main"
            and e.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and not e.get("GH_TOKEN") and not e.get("CLASSIC_VAULT_TOKEN"),
            "IDENTITY_CONTROL_OWNER_OFFLINE_SCOPE_REQUIRED")
    root = Path(temp)
    require(original == root/"private-original/com.yeelight.cherry_3.5.4.apk"
            and candidate == root/"private-output"/SIGNED
            and all(p.is_file() and not p.is_symlink()
                    for p in (original, candidate)),
            "IDENTITY_CONTROL_PRIVATE_PATH_DENIED")
    unsigned = root/"private-output"/UNSIGNED
    receipt = candidate.with_name(candidate.name+".test-signing.json")
    preflight = unsigned.with_suffix(unsigned.suffix+".preflight.json")
    require(all(p.is_file() and not p.is_symlink()
                and p.stat().st_size <= MAX_REPORT
                for p in (receipt, preflight))
            and unsigned.is_file() and not unsigned.is_symlink(),
            "IDENTITY_CONTROL_RECEIPTS_MISSING")
    try:
        signed_data = json.loads(receipt.read_text("utf-8"))
        unsigned_data = json.loads(preflight.read_text("utf-8"))
    except (OSError, ValueError, UnicodeError):
        raise BaselineBlocked("IDENTITY_CONTROL_RECEIPTS_INVALID") from None
    require(signed_data.get("identity_control_offline_only") is True
            and signed_data.get("package") == ORIGINAL
            and signed_data.get("signed") is True
            and signed_data.get("installed") is False
            and signed_data.get("signature") ==
                "EPHEMERAL_UNIQUE_TEST_KEY_NOT_SUITABLE_FOR_APP_UPDATES"
            and signed_data.get("apk_sha256") == digest_file(candidate)
            and unsigned_data.get("identity_control_offline_only") is True
            and unsigned_data.get("package") == ORIGINAL
            and unsigned_data.get("signed") is False
            and unsigned_data.get("installed") is False
            and unsigned_data.get("dex_reassembly_mode") == "raw"
            and unsigned_data.get("original_oem_dex_intentionally_changed") is False
            and all(value == 0 for value in
                    unsigned_data.get("controlled_authority_string_remaps",{}).values())
            and unsigned_data.get("apk_sha256") == digest_file(unsigned)
            and signed_data.get("unsigned_sha256") == digest_file(unsigned)
            and signed_data.get("source_build_report_sha256") == digest_file(preflight),
            "IDENTITY_CONTROL_NOT_AN_EXACT_AUDITED_BUILD")
    inspect_original(original)
    return digest_file(candidate)


def evaluate_launch(launch_code: int, launch: str, snapshot: str, crash: dict):
    """Pure closed-vocabulary classifier, cannot accept the product."""
    valid = (launch_code == 0 and
             bool(re.search(r"^Status:\s*ok\s*$",launch,re.M)) and
             bool(re.search(r"^Complete\s*$",launch,re.M)) and
             bool(re.search(r"^Activity:\s*"+re.escape(ORIGINAL)+r"/",launch,re.M)))
    resumed = (valid and original_foreground(snapshot)
               and not crash["target_crash"] and not crash["unattributed_fatal"])
    category = crash["exception_category"]
    state = ("NULL_DEREFERENCE" if crash["target_crash"] and
             category == "NULL_DEREFERENCE" else
             "CLASS_LOADER_MISSING" if crash["target_crash"] and
             category == "CLASS_LOADER_MISSING" else
             "OTHER_TARGET_FATAL" if crash["target_crash"] or
             crash["unattributed_fatal"] else
             "RESUMED_NO_TARGET_FATAL" if resumed else "NOT_RESUMED")
    return {"status": "PRIVATE_CLASSIC_IDENTITY_CONTROL_DIAGNOSTIC_ONLY",
            "identity_package_preserved": True,
            "oem_dex_bytes_preserved": True,
            "same_dj_bridge_and_provider_exclusions": True,
            "observed_window_ms": 3000,
            "observed_start_state": state,
            "native_loft_dj_functionally_accepted": False,
            "classic_functionally_accepted": False,
            "physical_device_tested": False,
            "raw_private_payload_published": False}


def identity_dj_resumed(snapshot: str) -> bool:
    """Only a unique global top-resumed native DJ activity inside OEM app."""
    if type(snapshot) is not str or len(snapshot) > 2_000_000:
        return False
    tops = []
    for row in snapshot.splitlines():
        match = GLOBAL_TOP.fullmatch(row)
        if not match:
            continue
        value = match.group(1)
        if not value.startswith("ActivityRecord{") or not value.endswith("}"):
            return False
        found = TOP_COMPONENT.findall(value)
        if len(found) != 1:
            return False
        package, component = found[0]
        if component.startswith("."):
            component = package + component
        tops.append((package, component))
    return len(tops) > 0 and set(tops) == {(ORIGINAL, DJ_ACTIVITY)}


def evaluate_identity_navigation(snapshot: str, crash: dict, ui_dump: str) -> dict:
    """Closed-vocabulary, non-accepting in-app DJ UI check; no OEM output."""
    state = ("NULL_DEREFERENCE" if crash["target_crash"] and
             crash["exception_category"] == "NULL_DEREFERENCE" else
             "OTHER_FATAL" if crash["target_crash"] or crash["unattributed_fatal"] else
             "DJ_NOT_RESUMED" if not identity_dj_resumed(snapshot) else
             "DJ_UI_NOT_VISIBLE")
    visible = False
    if state == "DJ_UI_NOT_VISIBLE":
        try:
            visible = classify_visible_ui(ui_dump, stage="native_dj")[
                "expected_native_ui_observed"]
        except SmokeBlocked:
            visible = False
        if visible:
            state = "DJ_NATIVE_CONTROLS_VISIBLE"
    return {"native_entry_navigation_tested": True,
            "native_dj_navigation_observed": bool(visible),
            "native_dj_observed_state": state,
            "native_loft_dj_functionally_accepted": False,
            "classic_functionally_accepted": False,
            "physical_device_tested": False,
            "raw_private_payload_published": False}


def probe(original: Path, candidate: Path, *, serial: str, approved: bool,
          pause=time.sleep, probe_native_ui=False):
    if type(probe_native_ui) is not bool:
        raise BaselineBlocked("IDENTITY_CONTROL_BAD_UI_MODE")
    digest = scope(original, candidate, serial=serial, approved=approved)
    offline_guard()
    require(installed_path(ORIGINAL) is None and
            installed_path(CLONE) is None,
            "IDENTITY_CONTROL_APP_COLLISION_DENIED")
    try:
        code, _ = run_adb(["install","--no-streaming",str(candidate)],
                          timeout=90,allow_failure=True)
        if code:
            return {"status":"PRIVATE_CLASSIC_IDENTITY_CONTROL_DIAGNOSTIC_ONLY",
                    "observed_start_state":"INSTALL_REJECTED",
                    "classic_functionally_accepted":False,
                    "native_loft_dj_functionally_accepted":False,
                    "raw_private_payload_published":False}
        verify_installed_hash(ORIGINAL,digest)
        offline_guard()
        intent=["-a","android.intent.action.MAIN",
                "-c","android.intent.category.LAUNCHER","-p",ORIGINAL]
        queried=adb(["shell","cmd","package","query-activities",
                     "--components","--user","0",*intent])
        resolved=adb(["shell","cmd","package","resolve-activity",
                      "--components","--user","0",*intent])
        launcher=resolve_original_launcher(queried,resolved)
        # No secondary launches or automatic retry. One bounded 3s run only.
        offline_guard()
        adb(["logcat","-b","crash","-c"])
        adb(["shell","am","force-stop",ORIGINAL])
        code,launch=run_adb(
            ["shell","am","start","-W","-n",ORIGINAL+"/"+launcher],
            timeout=35,allow_failure=True)
        pause(3)
        snapshot=adb(["shell","dumpsys","activity","activities"])
        logcat=adb(["logcat","-b","crash","-d"])
        report = evaluate_launch(code, launch, snapshot,
                                 classify_crash(logcat, package=ORIGINAL))
        report["native_entry_navigation_tested"] = False
        report["native_dj_navigation_observed"] = False
        report["native_dj_observed_state"] = "NOT_TESTED"
        if not probe_native_ui or report["observed_start_state"] != "RESUMED_NO_TARGET_FATAL":
            return report
        # Only the known in-Classic visible button may open the nonexported
        # DJ Activity. Never use am start against the embedded component.
        offline_guard()
        ui_xml, capture_code = capture_private_hierarchy()
        report["native_entry_hierarchy_capture"] = {
            "status": capture_code, "raw_hierarchy_published": False,
            "ui_visibility_proven": False,
        }
        # The provider exists only in this private identity-control build.
        # Its bounded, literal markers distinguish lifecycle/attach failure
        # from an Android accessibility hierarchy observation failure.
        # Optional telemetry never substitutes for a real UI tap or PASS.
        try:
            entry_logs = adb(["logcat", "-d", "-s", "ClassicDjProbe:I", "*:S"])
            report["native_entry_provider_observation"] = (
                classify_private_entry_markers(entry_logs))
        except (BaselineBlocked, OSError, ValueError, TypeError):
            report["native_entry_provider_observation"] = {
                "status": "PRIVATE_ENTRY_MARKERS_UNAVAILABLE_NOT_ACCEPTED",
                "ui_visibility_proven": False,
                "raw_logcat_published": False,
            }
        if ui_xml is None:
            report["native_dj_observed_state"] = "CLASSIC_UI_HIERARCHY_UNAVAILABLE"
            return report
        try:
            entry_visible = classify_visible_ui(ui_xml, stage="classic_entry")[
                "expected_native_ui_observed"]
        except SmokeBlocked:
            report["native_dj_observed_state"] = "CLASSIC_UI_HIERARCHY_UNAVAILABLE"
            return report
        if not entry_visible:
            report["native_dj_observed_state"] = "CLASSIC_DJ_ENTRY_NOT_VISIBLE"
            return report
        display = adb(["shell", "wm", "size"])
        try:
            x, y = entry_button_coordinates(ui_xml, display)
        except SmokeBlocked:
            report["native_dj_observed_state"] = "CLASSIC_DJ_ENTRY_COORDS_INVALID"
            return report
        offline_guard()
        adb(["logcat", "-b", "crash", "-c"])
        adb(["shell", "input", "tap", str(x), str(y)])
        pause(3)
        dj_snapshot = adb(["shell", "dumpsys", "activity", "activities"])
        dj_crashes = adb(["logcat", "-b", "crash", "-d"])
        dj_ui, dj_capture_code = capture_private_hierarchy()
        report["native_dj_hierarchy_capture"] = {
            "status": dj_capture_code, "raw_hierarchy_published": False,
            "ui_visibility_proven": False,
        }
        report.update(evaluate_identity_navigation(
            dj_snapshot, classify_crash(dj_crashes, package=ORIGINAL),
            dj_ui if dj_ui is not None else ""))
        return report
    finally:
        if installed_path(ORIGINAL) is not None:
            adb(["shell","am","force-stop",ORIGINAL])
            adb(["uninstall",ORIGINAL],timeout=45)
            require(installed_path(ORIGINAL) is None,
                    "IDENTITY_CONTROL_UNINSTALL_INCOMPLETE")


def main(argv=None):
    parser=argparse.ArgumentParser()
    parser.add_argument("--original",type=Path,required=True)
    parser.add_argument("--candidate",type=Path,required=True)
    parser.add_argument("--serial",required=True)
    parser.add_argument("--explicit-owner-approved-private-emulator",
                        action="store_true")
    parser.add_argument("--probe-native-ui", action="store_true",
                        help="owner-gated in-Classic nonexported DJ UI tap; no lamp control")
    args=parser.parse_args(argv)
    try:
        report=probe(args.original,args.candidate,serial=args.serial,
                     approved=args.explicit_owner_approved_private_emulator,
                     probe_native_ui=args.probe_native_ui)
    except (BaselineBlocked,OSError,ValueError,TypeError):
        print(json.dumps({"status":"PRIVATE_CLASSIC_IDENTITY_CONTROL_BLOCKED",
                          "code":"IDENTITY_CONTROL_GUARD_OR_RUNTIME_FAILED"}))
        return 2
    except Exception:
        print(json.dumps({"status":"PRIVATE_CLASSIC_IDENTITY_CONTROL_BLOCKED",
                          "code":"IDENTITY_CONTROL_UNEXPECTED_ERROR"}))
        return 2
    print(json.dumps(report,sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
