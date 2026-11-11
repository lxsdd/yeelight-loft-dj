#!/usr/bin/env python3
"""Protected, SHA-pinned OEM read-only root-cause details.

Wraps existing validated Vault-vs-Pixel receipt and Classpath diagnosis.
Every output uses a fixed field/enum with bounded counts only. No OEM
identifiers, path names, raw manifest values, hashes, or files leave the runner.
This is NOT an APK build pass or ClassLoader runtime attestation.
"""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from private_ci.readonly_vault_compare import diagnose as diagnose_baseline, MAX_RECEIPT
from manifest_route_provenance import inspect_original_routes

EXPORTED = ("explicit_true", "explicit_false", "inherited", "unresolved_resource")
LIBRARY = ("required_true", "required_false", "inherited", "unresolved_resource")


class RouteDiagnosticBlocked(ValueError):
    """Never expose underlying error or private value in an error code."""


def need(ok):
    if not ok:
        raise RouteDiagnosticBlocked("PRIVATE_ROUTE_DIAGNOSIS_BLOCKED")


def number(record, key):
    n = record.get(key)
    need(type(n) is int and 0 <= n <= 1000000)
    return n


def buckets(record, keys):
    need(type(record) is dict and set(record) == set(keys))
    return {key: number(record, key) for key in keys}


def guarded_context(route, baseline):
    """Reconcile independently parsed private manifest and original DEX totals."""
    need(type(route) is dict and type(baseline) is dict and
         baseline.get("diagnostic_only") is True and
         baseline.get("actual_classloader_verified") is False and
         baseline.get("installed_split_set_verified") is False and
         baseline.get("private_build_produced") is False and
         baseline.get("missing_classes_waived") == 0 and
         route.get("runtime_verified") is False and
         route.get("missing_classes_waived") == 0 and
         baseline.get("original_structure") in (
             "PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
             "PRIVATE_ORIGINAL_SUPPORT_MANIFEST_CLASS_MISSING_BLOCKED",
             "PRIVATE_ORIGINAL_ALIAS_TARGET_UNDECLARED_BLOCKED",
             "PRIVATE_ORIGINAL_BASELINE_UNRESOLVED_PREBUILD_BLOCKED",
             "PRIVATE_ORIGINAL_BASELINE_STRUCTURAL_READY_RUNTIME_NOT_TESTED"
         ))
    missing = number(baseline, "original_missing_activities")
    parents = number(baseline, "original_unresolved_parent_edges")
    libraries = number(baseline, "declared_shared_library_count")
    keys = (
        "missing_activity_count", "missing_with_intent_filter",
        "missing_intent_filter_total", "missing_main_launcher_filters",
        "missing_data_filters", "missing_explicit_permission",
        "missing_implicit_exported_with_filter", "alias_targets_missing_activity",
        "declared_library_count", "unresolved_parent_edge_count",
        "unresolved_parent_distinct_types", "unresolved_owner_distinct_types",
        "unresolved_owner_in_manifest",
    )
    c = {key: number(route, key) for key in keys}
    exported = buckets(route.get("missing_exported_buckets"), EXPORTED)
    lib = buckets(route.get("library_required_buckets"), LIBRARY)
    enabled = route.get("application_enabled_bucket")
    has_code = route.get("application_has_code_bucket")
    need(type(enabled) is str and enabled in EXPORTED and
         type(has_code) is str and has_code in EXPORTED)
    need(c["missing_activity_count"] == missing and
         c["unresolved_parent_edge_count"] == parents and
         c["declared_library_count"] == libraries and
         sum(exported.values()) == missing and sum(lib.values()) == libraries and
         c["missing_with_intent_filter"] <= missing and
         c["missing_intent_filter_total"] >= c["missing_with_intent_filter"] and
         c["missing_main_launcher_filters"] <= c["missing_intent_filter_total"] and
         c["missing_data_filters"] <= c["missing_intent_filter_total"] and
         c["missing_explicit_permission"] <= missing and
         c["missing_implicit_exported_with_filter"] <=
             min(c["missing_with_intent_filter"], exported["inherited"]) and
         c["unresolved_parent_distinct_types"] <= parents and
         c["unresolved_owner_distinct_types"] <= parents and
         c["unresolved_owner_in_manifest"] <= c["unresolved_owner_distinct_types"])
    return {
        "missing_activity_route_counts": {
            "with_intent_filter": c["missing_with_intent_filter"],
            "intent_filter_count": c["missing_intent_filter_total"],
            "main_launcher_filters": c["missing_main_launcher_filters"],
            "data_filters": c["missing_data_filters"],
            "permission_declared": c["missing_explicit_permission"],
            "implicit_exported_with_filter": c["missing_implicit_exported_with_filter"],
            "aliases_targeting_missing": c["alias_targets_missing_activity"],
        },
        "missing_activity_exported_buckets": exported,
        "application_enabled_attribute": enabled,
        "application_has_code_attribute": has_code,
        "declared_library_required_buckets": lib,
        "unresolved_parent_distinct_types": c["unresolved_parent_distinct_types"],
        "unresolved_owner_distinct_types": c["unresolved_owner_distinct_types"],
        "unresolved_owner_in_manifest": c["unresolved_owner_in_manifest"],
        "manifest_context_proves_classloader": False,
        "protected_original_runtime_tested": False,
        "classloader_missing_waivers": 0,
    }


def diagnose(original_path, receipt):
    # SHA and original-APK structural checks are mandatory BEFORE this extra pass.
    original = diagnose_baseline(original_path, receipt)
    context = guarded_context(inspect_original_routes(original_path), original)
    return dict(original, **context)


def main():
    try:
        if len(sys.argv) != 3 or sys.argv[1] != "--original":
            raise RouteDiagnosticBlocked("PRIVATE_ROUTE_DIAGNOSIS_BLOCKED")
        receipt = sys.stdin.read(MAX_RECEIPT + 1)
        report = diagnose(sys.argv[2], receipt)
    except Exception:
        print(json.dumps({"status":"BLOCKED", "reason":"PRIVATE_ROUTE_DIAGNOSIS_BLOCKED"},
                         sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
