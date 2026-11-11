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
from embedded_code_inventory import inspect_pinned_embedded
from dex_invoke_provenance import inspect_original_invokes

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



EMBEDDED_STATUS = "CANDIDATE_INVENTORY_ONLY_RUNTIME_UNVERIFIED"
EMBEDDED_KEYS = (
    "embedded_dex_containers", "embedded_nested_zip_containers",
    "embedded_nested_dex_containers", "embedded_native_elf_containers",
    "embedded_other_entries_scanned", "uninspected_nested_archives",
    "embedded_missing_activity_candidate_count",
    "embedded_missing_parent_candidate_count",
    "embedded_duplicate_original_definition_count",
)


def guarded_embedded(embedded, baseline):
    """Nested byte candidate is not Android ClassLoader/source installation."""
    need(type(embedded) is dict and embedded.get("embedded_inventory_status") ==
         EMBEDDED_STATUS and embedded.get("code_candidates_prove_runtime_loader") is False)
    counts = {key: number(embedded, key) for key in EMBEDDED_KEYS}
    need(counts["embedded_missing_activity_candidate_count"] <=
         number(baseline, "original_missing_activities") and
         counts["embedded_missing_parent_candidate_count"] <=
         number(baseline, "original_unresolved_parent_edges") and
         counts["uninspected_nested_archives"] <=
         counts["embedded_nested_zip_containers"])
    return {
        "embedded_code_candidate_inventory": counts,
        "embedded_code_sources_classloader_verified": False,
        "embedded_inventory_complete":
            counts["uninspected_nested_archives"] == 0,
    }



# Only these aggregate counts are permitted in public runner logs.
INVOKE_UNIQUE = (
    "activity_type_ids_count", "parent_type_ids_count",
    "activity_method_id_owners_count", "parent_method_id_owners_count",
    "activity_field_id_owners_count", "parent_field_id_owners_count",
    "activity_invoke_targets_count", "parent_invoke_targets_count",
    "activity_type_instruction_targets_count", "parent_type_instruction_targets_count",
    "activity_field_access_owners_count", "parent_field_access_owners_count",
)


def guarded_invokes(raw, baseline, context):
    """Class/method-use evidence is NOT dynamic reachability or a waiver."""
    need(type(raw) is dict and type(baseline) is dict and type(context) is dict
         and raw.get("dex_invoke_inventory_complete") is True
         and raw.get("dynamic_and_reflective_reachability_proven") is False
         and raw.get("absent_callsites_prove_dead_code") is False
         and raw.get("actual_classloader_verified") is False
         and raw.get("missing_classes_waived") == 0)
    totals = {key: number(raw, key) for key in INVOKE_UNIQUE}
    activity = number(baseline, "original_missing_activities")
    parents = number(context, "unresolved_parent_distinct_types")
    owners = number(context, "unresolved_owner_distinct_types")
    for label, value in totals.items():
        need(value <= (parents if label.startswith("parent_") else activity))
    for suffix in ("method_id_owners", "field_id_owners",
                   "invoke_targets", "type_instruction_targets",
                   "field_access_owners"):
        need(totals["activity_" + suffix + "_count"] <=
             totals["activity_type_ids_count"] and
             totals["parent_" + suffix + "_count"] <=
             totals["parent_type_ids_count"])
    need(totals["parent_type_ids_count"] == parents and
         totals["activity_invoke_targets_count"] <=
             totals["activity_method_id_owners_count"] and
         totals["parent_invoke_targets_count"] <=
             totals["parent_method_id_owners_count"] and
         totals["activity_field_access_owners_count"] <=
             totals["activity_field_id_owners_count"] and
         totals["parent_field_access_owners_count"] <=
             totals["parent_field_id_owners_count"])
    reached = number(raw, "unresolved_parent_gap_owner_manifest_call_reached_count")
    methods = number(raw, "code_items_scanned")
    units = raw.get("instructions_scanned")
    need(type(units) is int and methods > 0 and methods <= units <= 8000000 and
         reached <= owners)
    sites = {key: number(raw, key) for key in (
        "dynamic_invoke_sites", "missing_activity_invoke_sites",
        "missing_parent_invoke_sites")}
    need(sum(sites.values()) <= units and
         (sites["missing_activity_invoke_sites"] == 0 or
          totals["activity_invoke_targets_count"] > 0) and
         (sites["missing_parent_invoke_sites"] == 0 or
          totals["parent_invoke_targets_count"] > 0))
    return {
        "dex_invocation_and_type_inventory": dict(
            totals, unresolved_parent_gap_owner_manifest_call_reached_count=reached,
            code_items_scanned=methods, instructions_scanned=units, **sites),
        "dex_invoke_inventory_complete": True,
        "direct_call_graph_is_runtime_reachability_proof": False,
        "absence_of_static_references_proves_dead_component": False,
        "dynamic_reflective_resolution_verified": False,
        "dex_evidence_classloader_verified": False,
        "dex_evidence_missing_class_waivers": 0,
    }

def diagnose(original_path, receipt):
    # SHA and original-APK structural checks are mandatory BEFORE this extra pass.
    original = diagnose_baseline(original_path, receipt)
    context = guarded_context(inspect_original_routes(original_path), original)
    embedded = guarded_embedded(inspect_pinned_embedded(original_path), original)
    invoked = guarded_invokes(inspect_original_invokes(original_path), original, context)
    return dict(original, **context, **embedded, **invoked)


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
