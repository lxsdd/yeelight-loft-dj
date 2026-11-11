"""Strict synthetic manifest and ClassLoader origin regression, no OEM bytes."""
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from manifest_route_provenance import RouteBlocked, ANDROID, classify_routes, hierarchy_from_binary

def node(tag, parent, **values):
    return {"tag":tag, "parent":parent,
            "attrs":{ANDROID + k:v for k,v in values.items()}}

def synthetic():
    return [
        {"tag":"manifest","parent":None,"attrs":{"package":"org.synthetic"}},
        node("application",0),
        node("activity",1,name=".PrivateMissingA"),
        node("intent-filter",2),
        node("action",3,name="android.intent.action.MAIN"),
        node("category",3,name="android.intent.category.LAUNCHER"),
        node("data",3,host="private.example"),
        node("activity",1,name=".PrivateMissingB",exported="false",permission="org.secret.PERM"),
        node("activity",1,name=".PrivateMissingC"),
        node("activity",1,name=".Present"),
        node("activity-alias",1,name=".Alias",targetActivity=".PrivateMissingA"),
        node("uses-library",1,name="org.secret.library",required="false"),
    ]

def definitions():
    return {"Lorg/synthetic/Present;":("Lorg/synthetic/AbsentParent;",)}

def checked(rows=None, defs=None):
    return classify_routes(synthetic() if rows is None else rows, "org.synthetic",
                           definitions() if defs is None else defs,
                           system_prefixes=("Ljava/","Landroid/"))

class RouteContextTest(unittest.TestCase):
    def test_manifests_missing_owner_intent_exposure_and_library_requirement(self):
        result=checked()
        self.assertEqual(result["missing_activity_count"],3)
        self.assertEqual(result["missing_exported_buckets"],
                         {"explicit_false":1,"explicit_true":0,
                          "inherited":2,"unresolved_resource":0})
        self.assertEqual(result["missing_with_intent_filter"],1)
        self.assertEqual(result["missing_intent_filter_total"],1)
        self.assertEqual(result["missing_main_launcher_filters"],1)
        self.assertEqual(result["missing_data_filters"],1)
        self.assertEqual(result["missing_explicit_permission"],1)
        self.assertEqual(result["missing_implicit_exported_with_filter"],1)
        self.assertEqual(result["alias_targets_missing_activity"],1)
        self.assertEqual(result["application_enabled_bucket"],"inherited")
        self.assertEqual(result["application_has_code_bucket"],"inherited")
        self.assertEqual(result["declared_library_count"],1)
        self.assertEqual(result["library_required_buckets"],
                         {"required_true":0,"required_false":1,"inherited":0,"unresolved_resource":0})
        self.assertEqual(result["unresolved_parent_edge_count"],1)
        self.assertEqual(result["unresolved_parent_distinct_types"],1)
        self.assertEqual(result["unresolved_owner_distinct_types"],1)
        self.assertEqual(result["unresolved_owner_in_manifest"],1)
        self.assertFalse(result["runtime_verified"])
        self.assertEqual(result["missing_classes_waived"],0)
        output=json.dumps(result)
        for token in ("PrivateMissing","org.secret","private.example","Alias"):
            self.assertNotIn(token,output)

    def test_parent_route_and_exported_resources_do_not_waive_missing(self):
        rows=synthetic()
        rows[1]["attrs"][ANDROID+"enabled"]="false"
        rows[2]["attrs"][ANDROID+"exported"]="@type01:00000001"
        result=checked(rows)
        self.assertEqual(result["application_enabled_bucket"],"explicit_false")
        self.assertEqual(result["missing_exported_buckets"]["unresolved_resource"],1)
        self.assertEqual(result["missing_activity_count"],3)
        self.assertEqual(result["missing_classes_waived"],0)

    def test_all_defined_changes_counters_not_runtime_assertions(self):
        defs=definitions()
        defs.update({"Lorg/synthetic/PrivateMissingA;":("Ljava/lang/Object;",),
                     "Lorg/synthetic/PrivateMissingB;":("Ljava/lang/Object;",),
                     "Lorg/synthetic/PrivateMissingC;":("Ljava/lang/Object;",),
                     "Lorg/synthetic/AbsentParent;":("Ljava/lang/Object;",)})
        result=checked(defs=defs)
        self.assertEqual(result["missing_activity_count"],0)
        self.assertEqual(sum(result["missing_exported_buckets"].values()),0)
        self.assertEqual(result["unresolved_parent_edge_count"],0)
        self.assertFalse(result["runtime_verified"])

    def test_alias_undeclared_fails_closed(self):
        rows=synthetic()
        rows[10]["attrs"][ANDROID+"targetActivity"]=".Nonexistent"
        with self.assertRaises(RouteBlocked):
            checked(rows)

    def test_wrong_nesting_and_invalid_source_fails(self):
        for mutation in (
            lambda a: a[7].update(parent=3),
            lambda a: a[2].update(parent=None),
            lambda a: a[2]["attrs"].pop(ANDROID+"name"),
            lambda a: a[8]["attrs"].update({ANDROID+"name":".PrivateMissingA"}),
            lambda a: a[0].update(tag="activity"),
        ):
            rows=synthetic()
            mutation(rows)
            with self.subTest(mutation=mutation.__code__.co_firstlineno),self.assertRaises(RouteBlocked):
                checked(rows)

    def test_binary_axml_errors_are_sanitized(self):
        for data in (b"", b"notbinary", b"\x03\x00\x08\x00\x08\x00\x00\x00",
                     b"\x03\x00\x08\x00" + (b"\x00" * 32)):
            with self.subTest(size=len(data)),self.assertRaises(RouteBlocked):
                hierarchy_from_binary(data)

    def test_classpath_gap_count_is_distinct_per_edge(self):
        defs=definitions()
        defs["Lorg/synthetic/Other;"]=("Lorg/synthetic/AbsentParent;",)
        report=checked(defs=defs)
        self.assertEqual(report["unresolved_parent_edge_count"],2)
        self.assertEqual(report["unresolved_parent_distinct_types"],1)
        self.assertEqual(report["unresolved_owner_distinct_types"],2)
        self.assertEqual(report["unresolved_owner_in_manifest"],1)

if __name__=="__main__":
    unittest.main()
