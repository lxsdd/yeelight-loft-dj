#!/usr/bin/env python3
"""Closed-schema, sensitive-data-free UI evidence output in owner-only runner.

The original OEM APK/Smali, UI XML, labels, account data, original paths,
full preflight and device identifiers are NEVER printed or uploaded.
"""
from pathlib import Path
import json
import os
import sys

ROLES = ("navigation", "music", "scenes", "colors", "rooms", "groups", "devices")
STATES = frozenset((
    "STATIC_LAYOUT_AND_CLASS_CANDIDATES", "CLASS_ONLY_LAYOUT_UNMAPPED",
    "LAYOUT_ONLY_CODE_UNMAPPED", "UNMAPPED_NOT_PROVEN",
))
COUNTS = ("original_layout_variants", "unique_original_class_definitions",
          "manifest_activity_definitions")
REFERENCE_COUNTS = ("click_handlers", "listener_wiring", "view_inflation",
                    "navigation_calls")
MAX_READ = 750_000

class EvidenceBlocked(ValueError):
    pass

def ensure(ok, reason):
    if not ok:
        raise EvidenceBlocked(reason)

def bounded(value):
    ensure(type(value) is int and 0 <= value <= 100_000,
           "UI_EVIDENCE_COUNT_INVALID")
    return value

def safe_reason_code(value):
    # Accept only original UI audit's fixed source-defined reasons.
    from private_ci.private_build_diagnostics import safe_ui_reason
    return safe_ui_reason(value)

def sanitized(receipt):
    """Allowlist copy, never stringify private dictionary or exception."""
    ensure(isinstance(receipt, dict) and
           receipt.get("status") ==
           "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED" and
           receipt.get("signed") is False and
           receipt.get("installed") is False and
           receipt.get("identity_control_offline_only") is False and
           receipt.get("package") == "io.lxsdd.yeelightdj",
           "UI_EVIDENCE_UNQUALIFIED_PREBUILD")
    raw = receipt.get("original_ui_reuse_inventory")
    # A genuinely failed optional original UI analysis must remain
    # unqualified. Do not block OEM APK/runtime forensic checks for a
    # static UI inventory failure, and never synthesize role counters.
    if isinstance(raw, dict) and raw.get("status") == (
            "ORIGINAL_UI_REUSE_AUDIT_UNAVAILABLE_NOT_ACCEPTED"):
        from private_ci.private_build_diagnostics import safe_ui_reason
        ensure(
            raw.get("original_apk_or_smali_published") is False and
            raw.get("private_symbols_emitted") is False and
            raw.get("runtime_ui_navigation_verified") is False and
            raw.get("original_behavior_compatibility_verified") is False and
            raw.get("production_hook_approved") is False and
            raw.get("safe_reason") == safe_ui_reason(raw.get("safe_reason")) and
            "roles" not in raw,
            "UI_EVIDENCE_UNAVAILABLE_STATE_INVALID")
        return {
            "status": "OEM_UI_REUSE_ANALYSIS_UNAVAILABLE_NOT_ACCEPTED",
            "reason": safe_reason_code(raw["safe_reason"]),
            "actual_original_ui_runtime_proven": False,
            "oem_names_text_secrets_emitted": False,
            "roles": {},
        }
    ensure(isinstance(raw, dict) and raw.get("status") ==
           "ORIGINAL_UI_REUSE_CANDIDATES_STATIC_ONLY_NOT_RUNTIME_VERIFIED" and
           raw.get("source") == "PINNED_ORIGINAL_DECODED_BY_EXISTING_PRIVATE_BUILD" and
           raw.get("private_symbols_emitted") is False and
           raw.get("original_apk_or_smali_published") is False and
           raw.get("original_ui_methods_rewritten") is False and
           raw.get("runtime_ui_navigation_verified") is False and
           raw.get("original_behavior_compatibility_verified") is False and
           type(raw.get("roles")) is dict and
           set(raw["roles"]) == set(ROLES),
           "UI_EVIDENCE_ORIGINAL_AUDIT_NOT_QUALIFIED")
    result = {
        "status": "OEM_UI_REUSE_STATIC_COUNTS_ONLY",
        "actual_original_ui_runtime_proven": False,
        "original_activities_declared": bounded(raw.get("manifest_activity_count")),
        "decoded_original_layouts": bounded(raw.get("decoded_layout_count")),
        "decoded_original_smali_files": bounded(raw.get("decoded_smali_file_count")),
        "oem_names_text_secrets_emitted": False,
        "roles": {},
    }
    for role in ROLES:
        item = raw["roles"][role]
        ensure(type(item) is dict and item.get("binding_state") in STATES and
               item.get("production_hook_approved") is False and
               type(item.get("source_reference_counts")) is dict,
               "UI_EVIDENCE_ROLE_INVALID")
        result["roles"][role] = {
            "state": item["binding_state"],
            **{field: bounded(item.get(field)) for field in COUNTS},
            "reference_counts": {
                field: bounded(item["source_reference_counts"].get(field))
                for field in REFERENCE_COUNTS
            },
        }
    return result

def emit(path: Path, environ=None):
    env = os.environ if environ is None else environ
    temp = env.get("RUNNER_TEMP")
    ensure(type(temp) is str and bool(temp) and
           env.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj" and
           env.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
           env.get("GITHUB_ACTOR") == "lxsdd" and
           env.get("GITHUB_REF") == "refs/heads/main" and
           env.get("RUNNER_ENVIRONMENT") == "github-hosted" and
           isinstance(path, Path) and path ==
           Path(temp) / "private-output/classic-loft-dj-UNSIGNED.apk.preflight.json" and
           path.is_file() and not path.is_symlink() and
           path.stat().st_size <= MAX_READ,
           "UI_EVIDENCE_OWNER_SCOPE_REQUIRED")
    try:
        data = json.loads(path.read_text("utf8"))
    except (OSError, UnicodeError, ValueError):
        raise EvidenceBlocked("UI_EVIDENCE_RECEIPT_INVALID") from None
    return sanitized(data)

def main(argv=None):
    args=sys.argv[1:] if argv is None else argv
    try:
        ensure(len(args)==1, "UI_EVIDENCE_ARGUMENT_INVALID")
        value=emit(Path(args[0]))
    except (EvidenceBlocked, OSError, TypeError):
        print("CLASSIC_PRIVATE_UI_REUSE_EVIDENCE_BLOCKED")
        return 2
    # Only our newly constructed, strictly typed allowlist is serialized.
    print("CLASSIC_PRIVATE_ORIGINAL_UI_REUSE="+json.dumps(value,sort_keys=True))
    return 0

if __name__=="__main__":
    raise SystemExit(main())
