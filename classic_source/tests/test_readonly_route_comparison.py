"""Synthetic protected root-cause wrapper regressions; no real OEM bytes."""
import contextlib
from io import StringIO
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_ci import readonly_route_comparison as module


def original():
    return {
        "status":"UNATTESTED_RECEIPT_DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS",
        "diagnostic_only": True,
        "actual_classloader_verified":False,
        "installed_split_set_verified":False,
        "private_build_produced":False,
        "missing_classes_waived":0,
        "original_structure":"PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
        "original_missing_activities":3,
        "original_unresolved_parent_edges":6,
        "declared_shared_library_count":1,
    }


def routes():
    return {
        "missing_activity_count":3,
        "missing_exported_buckets":{"explicit_true":0,"explicit_false":1,
                                    "inherited":2,"unresolved_resource":0},
        "missing_with_intent_filter":1,
        "missing_intent_filter_total":2,
        "missing_main_launcher_filters":1,
        "missing_data_filters":1,
        "missing_explicit_permission":1,
        "missing_implicit_exported_with_filter":1,
        "alias_targets_missing_activity":1,
        "application_enabled_bucket":"inherited",
        "application_has_code_bucket":"inherited",
        "declared_library_count":1,
        "library_required_buckets":{"required_true":0,"required_false":1,
                                    "inherited":0,"unresolved_resource":0},
        "unresolved_parent_edge_count":6,
        "unresolved_parent_distinct_types":4,
        "unresolved_owner_distinct_types":5,
        "unresolved_owner_in_manifest":1,
        "runtime_verified":False,
        "missing_classes_waived":0,
    }



def embedded():
    return {
        "embedded_dex_containers":1,
        "embedded_nested_zip_containers":1,
        "embedded_nested_dex_containers":1,
        "embedded_native_elf_containers":2,
        "embedded_other_entries_scanned":15,
        "uninspected_nested_archives":0,
        "embedded_missing_activity_candidate_count":2,
        "embedded_missing_parent_candidate_count":3,
        "embedded_duplicate_original_definition_count":1,
        "code_candidates_prove_runtime_loader":False,
        "embedded_inventory_status":"CANDIDATE_INVENTORY_ONLY_RUNTIME_UNVERIFIED",
    }


class ProtectedRouteTests(unittest.TestCase):
    def test_positive_uses_real_pinned_gate_and_proves_nothing_about_loader(self):
        with patch.object(module,"diagnose_baseline",return_value=original()) as gate, \
             patch.object(module,"inspect_original_routes",return_value=routes()) as raw, \
             patch.object(module,"inspect_pinned_embedded",return_value=embedded()) as nested:
            report=module.diagnose("private-fixture.apk","synthetic-receipt")
        gate.assert_called_once_with("private-fixture.apk","synthetic-receipt")
        raw.assert_called_once_with("private-fixture.apk")
        nested.assert_called_once_with("private-fixture.apk")
        self.assertEqual(report["missing_activity_exported_buckets"]["inherited"],2)
        self.assertEqual(report["declared_library_required_buckets"]["required_false"],1)
        self.assertEqual(report["missing_activity_route_counts"]["main_launcher_filters"],1)
        self.assertEqual(report["unresolved_parent_distinct_types"],4)
        self.assertEqual(report["classloader_missing_waivers"],0)
        self.assertFalse(report["manifest_context_proves_classloader"])
        self.assertFalse(report["actual_classloader_verified"])
        self.assertEqual(report["embedded_code_candidate_inventory"]["embedded_missing_activity_candidate_count"],2)
        self.assertFalse(report["embedded_code_sources_classloader_verified"])
        self.assertTrue(report["embedded_inventory_complete"])

    def test_deny_unverified_graph_counters_and_fake_runtime(self):
        changes=(
            lambda x:x.update({"missing_activity_count":2}),
            lambda x:x["missing_exported_buckets"].update({"inherited":1}),
            lambda x:x["missing_exported_buckets"].update({"unknown_key":1}),
            lambda x:x["missing_exported_buckets"].update({"explicit_true":True}),
            lambda x:x.update({"missing_intent_filter_total":0}),
            lambda x:x.update({"missing_main_launcher_filters":3}),
            lambda x:x.update({"missing_data_filters":3}),
            lambda x:x.update({"missing_explicit_permission":5}),
            lambda x:x.update({"missing_implicit_exported_with_filter":3}),
            lambda x:x.update({"declared_library_count":0}),
            lambda x:x["library_required_buckets"].update({"required_false":0}),
            lambda x:x.update({"unresolved_parent_edge_count":5}),
            lambda x:x.update({"unresolved_parent_distinct_types":7}),
            lambda x:x.update({"unresolved_owner_in_manifest":6}),
            lambda x:x.update({"runtime_verified":True}),
            lambda x:x.update({"missing_classes_waived":1}),
            lambda x:x.update({"application_has_code_bucket":"private_value"}),
        )
        for mutate in changes:
            case=routes()
            mutate(case)
            with self.subTest(line=mutate.__code__.co_firstlineno), \
                 self.assertRaises(module.RouteDiagnosticBlocked):
                module.guarded_context(case,original())

    def test_reject_false_baseline_success_or_private_build(self):
        for key,value in (
            ("diagnostic_only",False),
            ("actual_classloader_verified",True),
            ("private_build_produced",True),
            ("missing_classes_waived",1),
            ("original_unresolved_parent_edges",2),
            ("original_structure","SOME_UNVERIFIED_PRIVATE_STATUS"),
        ):
            source=original()
            source[key]=value
            with self.subTest(key=key),self.assertRaises(module.RouteDiagnosticBlocked):
                module.guarded_context(routes(),source)

    def test_output_never_echoes_private_values(self):
        input_routes=routes()
        input_routes["private_component"]="Lsecret/MyManufacturerActivity;"
        input_routes["private_library"]="manufacturer-library"
        report=module.guarded_context(input_routes,original())
        data=json.dumps(report)
        self.assertNotIn("manufacturer",data)
        self.assertNotIn("Lsecret",data)
        self.assertFalse(report["manifest_context_proves_classloader"])


    def test_embedded_inconsistencies_and_loader_elevation_denied(self):
        changes=(
            lambda x:x.update({"embedded_missing_activity_candidate_count":4}),
            lambda x:x.update({"embedded_missing_parent_candidate_count":7}),
            lambda x:x.update({"uninspected_nested_archives":2}),
            lambda x:x.update({"embedded_dex_containers":True}),
            lambda x:x.update({"embedded_inventory_status":"VERIFIED_CLASSLOADER"}),
            lambda x:x.update({"code_candidates_prove_runtime_loader":True}),
        )
        for mutate in changes:
            sample=embedded()
            mutate(sample)
            with self.subTest(line=mutate.__code__.co_firstlineno), \
                 self.assertRaises(module.RouteDiagnosticBlocked):
                module.guarded_embedded(sample,original())

    def test_uninspected_nested_archive_cannot_claim_complete(self):
        sample=embedded()
        sample["uninspected_nested_archives"]=1
        report=module.guarded_embedded(sample,original())
        self.assertFalse(report["embedded_inventory_complete"])
        self.assertFalse(report["embedded_code_sources_classloader_verified"])

    def test_private_nested_strings_never_appear_in_output(self):
        sample=embedded()
        sample["private_dex_name"]="secret/OriginalClass.dex"
        serialized=json.dumps(module.guarded_embedded(sample,original()))
        self.assertNotIn("OriginalClass",serialized)
        self.assertNotIn("secret/",serialized)

    def test_cli_blocks_bad_args_without_calling_oem(self):
        with patch.object(sys,"argv",["readonly_route_comparison.py","unexpected"]), \
             patch.object(module,"diagnose_baseline",side_effect=AssertionError("unexpected read")), \
             contextlib.redirect_stdout(StringIO()) as captured:
            result=module.main()
        self.assertEqual(result,2)
        self.assertEqual(json.loads(captured.getvalue()),
                         {"status":"BLOCKED","reason":"PRIVATE_ROUTE_DIAGNOSIS_BLOCKED"})


if __name__=="__main__":
    unittest.main()
