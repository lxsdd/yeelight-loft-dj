"""Positive/negative synthetic provenance tests; no real OEM payloads."""
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from activity_provenance import (
    ANDROID, index_activity_metadata, summarize_missing_activities, ProvenanceError)

PKG="test.example"
def node(tag,**attrs):
    return {"tag":tag,"attributes":{ANDROID+k:v for k,v in attrs.items()}}

def fixture():
    parsed={"elements":[
        {"tag":"manifest","attributes":{"package":PKG}},
        node("activity",name=".AbsentDisabled",enabled="false",splitName="private_split"),
        node("activity",name=".AbsentEnabled",enabled="true",process=":private"),
        node("activity",name=".AbsentUnknown",enabled="@type01:00000001"),
        node("activity",name=".Present"),
        node("activity-alias",name=".AliasNotAClass",targetActivity=".Present")]}
    idx=index_activity_metadata(parsed,PKG)
    defs={"Ltest/example/Present;":("Ljava/lang/Object;",)}
    return parsed,idx,defs

class ActivityProvenanceTest(unittest.TestCase):
    def test_disjoint_buckets_never_waive(self):
        _,idx,defs=fixture()
        r=summarize_missing_activities(idx,defs,set(idx))
        self.assertEqual(r["missing_count"],3)
        self.assertEqual(r["declared_enabled_by_missing"],
                         {"explicit_false":1,"explicit_true":1,
                          "inherited":0,"unresolved_resource":1})
        self.assertEqual(r["split_labeled_missing_count"],1)
        self.assertEqual(r["process_labeled_missing_count"],1)
        self.assertEqual(r["runtime"],"NOT_TESTED")
        self.assertEqual(r["missing_classes_waived"],0)

    def test_alias_is_not_a_java_class(self):
        _,idx,_=fixture()
        self.assertEqual(len(idx),4)
        self.assertNotIn("Ltest/example/AliasNotAClass;",idx)

    def test_no_private_names_or_descriptors_in_report(self):
        _,idx,defs=fixture()
        s=json.dumps(summarize_missing_activities(idx,defs,set(idx)),sort_keys=True)
        for token in ("AbsentEnabled","AbsentDisabled","AbsentUnknown",
                      "test/example","private_split",":private","AliasNotAClass"):
            self.assertNotIn(token,s)

    def test_manifest_drift_blocks(self):
        _,idx,defs=fixture()
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_MANIFEST_DRIFT"):
            summarize_missing_activities(idx,defs,set(idx)-{"Ltest/example/AbsentDisabled;"})

    def test_duplicate_and_corrupt_metadata_block(self):
        parsed,idx,defs=fixture()
        parsed["elements"].append(node("activity",name=".Present"))
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_DUPLICATE"):
            index_activity_metadata(parsed,PKG)
        idx["Ltest/example/AbsentEnabled;"]["split_labeled"]="true"
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_BAD_METADATA"):
            summarize_missing_activities(idx,defs,set(idx))

    def test_all_classes_resolved_is_zero_only_when_present(self):
        _,idx,defs=fixture()
        defs.update({key:("Ljava/lang/Object;",) for key in idx})
        r=summarize_missing_activities(idx,defs,set(idx))
        self.assertEqual(r["missing_count"],0)
        self.assertEqual(r["split_labeled_missing_count"],0)
        self.assertEqual(r["missing_classes_waived"],0)

    def test_missing_enabled_flag_inherits_but_is_never_waived(self):
        idx=index_activity_metadata({"elements":[node("activity",name=".Unknown")]},PKG)
        r=summarize_missing_activities(idx,{},set(idx))
        self.assertEqual(r["declared_enabled_by_missing"]["inherited"],1)
        self.assertEqual(r["missing_count"],1)

    def test_missing_name_or_bad_document_blocks(self):
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_BAD_NAME"):
            index_activity_metadata({"elements":[node("activity",enabled="true")]},PKG)
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_BAD_DOCUMENT"):
            index_activity_metadata({"elements":None},PKG)
        with self.assertRaisesRegex(ProvenanceError,"ACTIVITY_PROVENANCE_BAD_ELEMENT"):
            index_activity_metadata({"elements":[None]},PKG)

    def test_non_boolean_encoded_enabled_is_unresolved(self):
        parsed={"elements":[node("activity",name=".A",enabled="@type12:00000000")]}
        idx=index_activity_metadata(parsed,PKG)
        r=summarize_missing_activities(idx,{},set(idx))
        self.assertEqual(r["declared_enabled_by_missing"]["unresolved_resource"],1)

    def test_process_or_split_hint_is_not_classpath_evidence(self):
        idx=index_activity_metadata({"elements":[node(
            "activity",name=".Missing",enabled="false",splitName="feature_x",process=":other")]},PKG)
        r=summarize_missing_activities(idx,{},set(idx))
        self.assertEqual(r["missing_count"],1)
        self.assertEqual(r["missing_classes_waived"],0)
        self.assertEqual(r["split_labeled_missing_count"],1)
        self.assertEqual(r["process_labeled_missing_count"],1)

if __name__=="__main__":unittest.main()
