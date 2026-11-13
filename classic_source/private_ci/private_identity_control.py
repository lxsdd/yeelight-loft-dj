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
from android17_start_smoke import classify_crash

SIGNED = "classic-loft-dj-IDENTITY-TEST-SIGNED.apk"
UNSIGNED = "classic-loft-dj-IDENTITY-UNSIGNED.apk"
MAX_REPORT = 50000


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


def probe(original: Path, candidate: Path, *, serial: str, approved: bool,
          pause=time.sleep):
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
        return evaluate_launch(code,launch,snapshot,
                               classify_crash(logcat,package=ORIGINAL))
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
    args=parser.parse_args(argv)
    try:
        report=probe(args.original,args.candidate,serial=args.serial,
                     approved=args.explicit_owner_approved_private_emulator)
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
