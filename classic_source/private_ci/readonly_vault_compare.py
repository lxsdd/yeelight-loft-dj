#!/usr/bin/env python3
"""Private, owner-dispatched READ-ONLY Classic APK receipt/classpath diagnosis.

This executable is a diagnosis, never a pass gate for OEM startup or runtime.
Only fixed status tokens and bounded aggregate integers reach stdout. It
never prints APK paths, hashes, private descriptors, device identifiers,
manifest strings, raw receipts, exceptions or original APK contents.
"""
import json
from pathlib import Path
import sys

# The public-source CLI is executed by path from the repository root.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from classic_first import ORIGINAL_SHA256
from private_ci.apk_variant_parity import (
    MAX_RECEIPT, PIN, VariantBlocked, compare, fingerprint, fingerprint_receipt,
)
from private_startup_audit import original_prebuild_report

ALLOWED_ORIGINAL_STATES = frozenset({
    "PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
    "PRIVATE_ORIGINAL_SUPPORT_MANIFEST_CLASS_MISSING_BLOCKED",
    "PRIVATE_ORIGINAL_ALIAS_TARGET_UNDECLARED_BLOCKED",
    "PRIVATE_ORIGINAL_BASELINE_UNRESOLVED_PREBUILD_BLOCKED",
    "PRIVATE_ORIGINAL_BASELINE_STRUCTURAL_READY_RUNTIME_NOT_TESTED",
})
ALLOWED_COMPARE_STATES = frozenset({
    "IDENTICAL_ARCHIVE", "DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS",
    "DEX_EQUAL_MANIFEST_DIFFERS", "DEX_PAYLOAD_DIFFERS", "DEX_LAYOUT_DIFFERS",
})


class ReadonlyBlocked(ValueError):
    """Details are suppressed even if an underlying parser raises."""


def _need(condition):
    if not condition:
        raise ReadonlyBlocked("READONLY_DIAGNOSTIC_UNVERIFIED")


def _nonnegative(record, name):
    value = record.get(name)
    _need(type(value) is int and 0 <= value <= 1000000)
    return value


def _device_pin(receipt):
    """The read-only owner secret must contain its own installed-base pin.

    That pin is still UNATTESTED, not independently verified by a device or
    Play Store. It is never written to disk, printed, or sent as a CLI argument.
    """
    _need(isinstance(receipt, str) and 0 < len(receipt) <= MAX_RECEIPT)
    pins = [line.split("=", 1)[1] for line in receipt.splitlines()
            if line.startswith("INSTALLED_BASE_SHA256=")]
    _need(len(pins) == 1 and PIN.fullmatch(pins[0]) is not None)
    return pins[0]


def diagnose(original_path, receipt):
    """No files other than the pinned vault original are opened or modified."""
    device_pin = _device_pin(receipt)
    device = fingerprint_receipt(receipt, device_pin)
    # Vault SHA is a fixed source policy pin. Verify it before decoding ZIP.
    original = fingerprint(original_path, ORIGINAL_SHA256)
    parity = compare(device, original)
    _need(parity.get("status") in ALLOWED_COMPARE_STATES)
    original_report = original_prebuild_report(Path(original_path))
    _need(isinstance(original_report, dict) and
          original_report.get("status") in ALLOWED_ORIGINAL_STATES and
          original_report.get("real_oem_read") is True and
          original_report.get("runtime") == "NOT_TESTED")
    activity = original_report.get("original_missing_activity_provenance")
    classpath = original_report.get("original_classpath_provenance")
    _need(isinstance(activity, dict) and isinstance(classpath, dict) and
          classpath.get("installed_split_set_verified") is False and
          classpath.get("actual_classloader_verified") is False and
          classpath.get("missing_classes_waived") == 0 and
          classpath.get("runtime") == "NOT_TESTED" and
          activity.get("missing_classes_waived") == 0)
    _need(original_report.get("manifest_missing_by_role") is not None)
    missing_activities = _nonnegative(activity, "missing_count")
    required = _nonnegative(original_report, "manifest_missing_required_count")
    total_parents = _nonnegative(original_report, "original_total_missing_parent_edges")
    startup_parents = _nonnegative(original_report, "original_missing_startup_parent_edges")
    libraries = _nonnegative(original_report, "original_declared_shared_libraries")
    _need(startup_parents <= total_parents and missing_activities <= required and
          original_report["manifest_missing_by_role"].get("activity", 0) == missing_activities and
          _nonnegative(classpath, "original_missing_activity_count") == missing_activities and
          _nonnegative(classpath, "original_unresolved_direct_parent_edges") == total_parents)
    _need(parity.get("classes_verified") is False and
          parity.get("actual_classloader_verified") is False and
          parity.get("android_runtime_tested") is False)
    # Report only predefined keys and booleans/integers; no source-provided text
    # except enum-allowlisted status values. ALL actual OEM symbols remain private.
    return {
        "status": "UNATTESTED_RECEIPT_" + parity["status"],
        "diagnostic_only": True,
        "device_receipt_independently_attested": False,
        "archive_equal": parity["archive_equal"],
        "dex_layout_equal": parity["dex_layout_equal"],
        "dex_payloads_equal": parity["dex_payloads_equal"],
        "manifest_payload_equal": parity["manifest_payload_equal"],
        "original_structure": original_report["status"],
        "original_missing_activities": missing_activities,
        "original_missing_required_references": required,
        "original_unresolved_parent_edges": total_parents,
        "original_startup_parent_edges": startup_parents,
        "declared_shared_library_count": libraries,
        "missing_classes_waived": 0,
        "installed_split_set_verified": False,
        "actual_classloader_verified": False,
        "android_runtime_tested": False,
        "private_build_produced": False,
        "device_access_performed": False,
    }


def main():
    # No receipt file or argv path allowed; the owner secret is sent via stdin.
    try:
        if len(sys.argv) != 3 or sys.argv[1] != "--original":
            raise ReadonlyBlocked("READONLY_DIAGNOSTIC_UNVERIFIED")
        receipt = sys.stdin.read(MAX_RECEIPT + 1)
        report = diagnose(sys.argv[2], receipt)
    except Exception:
        # Do not echo even fixed-code underlying error: third-party errors may
        # contain private ZIP filenames, class descriptors or exception values.
        print(json.dumps({"status": "BLOCKED", "reason": "READONLY_DIAGNOSTIC_UNVERIFIED"}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
