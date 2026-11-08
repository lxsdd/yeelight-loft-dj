"""Offline negative/positive tests for the PRIVATE real-APK startup audit."""
from collections import Counter
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import hashlib
import struct
import unittest
from unittest.mock import patch
import zlib
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import private_startup_audit as audit

ROOT="com.yeelight.cherry"
BASE="Ljava/lang/Object;"

def model():
    names=["com.google.firebase.provider.FirebaseInitProvider",
           "com.yeelight.yeelib.data.DeviceInteractionProvider",
           "com.yeelight.yeelib.data.DeviceBrowserProvider",
           "com.yeelight.cherry.OtherOne",
           "com.yeelight.cherry.OtherTwo",
           "com.yeelight.cherry.OtherThree",
           "com.yeelight.cherry.OtherFour"]
    all_activities=["com.yeelight.cherry.Activity%d"%i for i in range(185)]
    original=Counter({("provider",n):1 for n in names})
    original.update({("activity",n):1 for n in all_activities})
    original[("application","com.yeelight.cherry.App")]=1
    clone=original.copy()
    for n in audit.REMOVED: del clone[("provider",n)]
    for n in audit.ADDED: clone[n]+=1
    orig_desc={"L"+n.replace(".","/")+";" for n in names+all_activities+["com.yeelight.cherry.App"]}
    removed_desc={"L"+n.replace(".","/")+";" for n in audit.REMOVED}
    clone_desc=(orig_desc - removed_desc) | {"L"+n.replace(".","/")+";" for _,n in audit.ADDED}
    orefs=Counter({(kind,"L"+name.replace(".","/")+";"):n
                   for (kind,name),n in original.items()})
    crefs=Counter({(kind,"L"+name.replace(".","/")+";"):n
                   for (kind,name),n in clone.items()})
    o={"package":audit.OLD,"components":original,"entrypoints":orig_desc,
       "manifest_references":orefs,"declared_shared_libraries":0,
       "definitions":{d:[BASE] for d in orig_desc},"dex_count":2}
    c={"package":audit.NEW,"components":clone,"entrypoints":clone_desc,
       "manifest_references":crefs,"declared_shared_libraries":0,
       "definitions":{d:[BASE] for d in orig_desc | clone_desc},"dex_count":3}
    return o,c

def fake_dex():
    """Real minimal DEX header, string/type/class tables and valid SHA/Adler32."""
    types=["Ljava/lang/Object;","Lcom/example/Base;","Lcom/example/Child;"]
    sn=len(types);tn=len(types);cn=2
    strings_start=112+sn*4+tn*4+cn*32
    blob=bytearray(strings_start)
    blob[0:8]=b"dex\n035\0"
    struct.pack_into("<I",blob,56,sn);struct.pack_into("<I",blob,60,112)
    struct.pack_into("<I",blob,64,tn);struct.pack_into("<I",blob,68,112+sn*4)
    struct.pack_into("<I",blob,96,cn);struct.pack_into("<I",blob,100,112+sn*4+tn*4)
    for i,t in enumerate(types):
        struct.pack_into("<I",blob,112+i*4,len(blob))
        blob.append(len(t));blob.extend(t.encode("ascii"));blob.append(0)
        struct.pack_into("<I",blob,112+sn*4+i*4,i)
    c=112+sn*4+tn*4
    struct.pack_into("<IIIIIIII",blob,c,1,0,0,0,0xffffffff,0,0,0)
    struct.pack_into("<IIIIIIII",blob,c+32,2,0,1,0,0xffffffff,0,0,0)
    struct.pack_into("<I",blob,32,len(blob))
    blob[12:32]=hashlib.sha1(blob[32:]).digest()
    struct.pack_into("<I",blob,8,zlib.adler32(blob[12:])&0xffffffff)
    return bytes(blob)

class TestDexIndex(unittest.TestCase):
    def test_real_binary_class_defs_and_superclasses(self):
        classes=audit.dex_index(fake_dex())
        self.assertEqual(classes["Lcom/example/Base;"],["Ljava/lang/Object;"])
        self.assertEqual(classes["Lcom/example/Child;"],["Lcom/example/Base;"])
    def test_modified_dex_payload_rejected(self):
        wrong=bytearray(fake_dex());wrong[-2]^=4
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_HASH"):
            audit.dex_index(bytes(wrong))
    def test_malformed_header_rejected(self):
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_HEADER"):
            audit.dex_index(b"not dex")
    def test_unresolved_parent_cannot_pass_as_class_def(self):
        o,c=model()
        d=next(iter(c["entrypoints"]))
        c["definitions"][d]=["Lvendor/missing/Super;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_CLASS_OR_SUPER_MISSING"):
            audit.reachable(c)
    def test_cycle_rejected(self):
        o,c=model();d=next(iter(c["entrypoints"]));c["definitions"][d]=[d]
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_INHERITANCE_CYCLE"):
            audit.reachable(c)

class TestPrivatePairs(unittest.TestCase):
    def checked(self,o,c):
        with patch.object(audit,"inspect_original"),patch.object(audit,"inspect_apk",side_effect=[o,c]):
            return audit.audit_pair(Path("not-real-original.apk"),Path("not-real-clone.apk"))
    def test_full_manifest_and_both_hierarchies_pass(self):
        o,c=model();info=self.checked(o,c)
        self.assertEqual(info["status"],"PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED")
        self.assertEqual(info["original_dex"],2)
        self.assertEqual(info["clone_dex"],3)
        self.assertEqual(info["runtime"],"NOT_TESTED")
        self.assertEqual(info["original_total_unresolved_parent_edges"],0)
        self.assertTrue(audit.qualify_report(info))
    def test_missing_oem_device_browser_provider_refused(self):
        o,c=model()
        descriptor="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"].pop(descriptor)
        with self.assertRaisesRegex(audit.AuditBlocked,"RETAINED_ORIGINAL_ENTRYPOINT_CLASS_MISSING"):
            self.checked(o,c)
    def test_no_provider_deletion_guess_accepted(self):
        o,c=model();del c["components"][("provider","com.yeelight.yeelib.data.DeviceBrowserProvider")]
        with self.assertRaisesRegex(audit.AuditBlocked,"CLONE_COMPONENT_DRIFT"):
            self.checked(o,c)
    def test_new_unapproved_component_refused(self):
        o,c=model();c["components"][("service","third.party.Unreviewed")]=1
        with self.assertRaisesRegex(audit.AuditBlocked,"CLONE_COMPONENT_DRIFT"):
            self.checked(o,c)
    def test_wrong_original_provider_count_rejected(self):
        o,c=model();del o["components"][("provider","com.yeelight.cherry.OtherOne")]
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_COMPONENT_COUNTS"):
            self.checked(o,c)
    def test_not_all_classes_present_in_clone(self):
        o,c=model()
        c["definitions"].pop("Lcom/yeelight/yeelib/data/DeviceBrowserProvider;")
        with self.assertRaisesRegex(audit.AuditBlocked,"OEM_DEX_GRAPH_DRIFT"):
            self.checked(o,c)
    def test_does_not_accept_more_or_fewer_dex_files(self):
        o,c=model();c["dex_count"]=4
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_SHAPE"):
            self.checked(o,c)

    def test_removed_oem_provider_class_can_be_absent_if_no_longer_referenced(self):
        o,c=model()
        for name in audit.REMOVED:
            descriptor="L"+name.replace(".","/")+";"
            o["definitions"].pop(descriptor)
            c["definitions"].pop(descriptor)
        info=self.checked(o,c)
        self.assertEqual(info["status"],"PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED")
    def test_removed_provider_cannot_remain_referenced_in_clone_manifest(self):
        o,c=model()
        descriptor="L"+audit.REMOVED[0].replace(".","/")+";"
        c["entrypoints"].add(descriptor)
        with self.assertRaisesRegex(audit.AuditBlocked,"REMOVED_PROVIDER_ENTRYPOINT_DRIFT"):
            self.checked(o,c)
    def test_unexpected_missing_retained_manifest_entry_is_rejected(self):
        o,c=model()
        descriptor="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        c["entrypoints"].remove(descriptor)
        with self.assertRaisesRegex(audit.AuditBlocked,"REMOVED_PROVIDER_ENTRYPOINT_DRIFT"):
            self.checked(o,c)
    def test_unexpected_extra_clone_manifest_entry_is_rejected(self):
        o,c=model()
        c["entrypoints"].add("Lunreviewed/Extra;")
        with self.assertRaisesRegex(audit.AuditBlocked,"ADDED_BRIDGE_ENTRYPOINT_DRIFT"):
            self.checked(o,c)
    def test_original_retained_parent_gap_is_reported_not_assumed_fixed(self):
        o,c=model()
        descriptor="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"][descriptor]=["Lvendor/unavailable/Parent;"]
        c["definitions"][descriptor]=["Lvendor/unavailable/Parent;"]
        result=self.checked(o,c)
        self.assertEqual(result["original_baseline_unresolved_parent_edges"],1)
        self.assertEqual(result["status"],"PRIVATE_STATIC_PARITY_WITH_UNRESOLVED_OEM_BASELINE_RUNTIME_NOT_TESTED")
        self.assertEqual(result["original_total_unresolved_parent_edges"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_BASELINE_PARENT_UNVERIFIED"):
            audit.qualify_report(result)
    def test_clone_sidecar_parent_remains_mandatory(self):
        o,c=model()
        descriptor="L"+audit.ADDED[0][1].replace(".","/")+";"
        c["definitions"][descriptor]=["Lvendor/unavailable/Parent;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"CANDIDATE_UNRESOLVED_PARENT_DRIFT"):
            self.checked(o,c)
    def test_removed_provider_as_retained_super_is_still_required(self):
        o,c=model()
        removed="L"+audit.REMOVED[0].replace(".","/")+";"
        retained="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"].pop(removed)
        c["definitions"].pop(removed)
        o["definitions"][retained]=[removed]
        c["definitions"][retained]=[removed]
        with self.assertRaisesRegex(audit.AuditBlocked,"RETAINED_INHERITS_REMOVED_PROVIDER"):
            self.checked(o,c)
    def test_oem_class_graph_mutation_remains_blocked(self):
        o,c=model()
        descriptor="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        c["definitions"][descriptor]=["Ljava/lang/String;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"OEM_DEX_GRAPH_DRIFT"):
            self.checked(o,c)

    def test_new_dj_class_cannot_be_missing_from_dex(self):
        o,c=model()
        dj="L"+audit.ADDED[0][1].replace(".","/")+";"
        c["definitions"].pop(dj)
        with self.assertRaisesRegex(audit.AuditBlocked,"CANDIDATE_ENTRYPOINT_CLASS_MISSING"):
            self.checked(o,c)

    def test_clone_cannot_introduce_missing_nonplatform_parent(self):
        o,c=model()
        dj="L"+audit.ADDED[0][1].replace(".","/")+";"
        c["definitions"][dj]=["Lvendor/clone/OnlyMissing;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"CANDIDATE_UNRESOLVED_PARENT_DRIFT"):
            self.checked(o,c)

    def test_new_dj_cannot_inherit_original_baseline_gap(self):
        o,c=model()
        original="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        missing="Lvendor/unknown/Baseline;"
        dj="L"+audit.ADDED[0][1].replace(".","/")+";"
        o["definitions"][original]=[missing]
        c["definitions"][original]=[missing]
        c["definitions"][dj]=[original]
        with self.assertRaisesRegex(audit.AuditBlocked,"BRIDGE_ENTRYPOINT_OR_PARENT_MISSING"):
            self.checked(o,c)

    def test_unresolved_parent_count_deduplicates_identical_edges(self):
        o,c=model()
        original="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"][original]=["Lvendor/unknown/Baseline;","Lvendor/unknown/Baseline;"]
        c["definitions"][original]=o["definitions"][original][:]
        result=self.checked(o,c)
        self.assertEqual(result["original_baseline_unresolved_parent_edges"],1)

    def test_missing_retained_activity_start_class_always_fatal(self):
        o,c=model()
        d="Lcom/yeelight/cherry/Activity0;"
        o["definitions"].pop(d)
        c["definitions"].pop(d)
        with self.assertRaisesRegex(audit.AuditBlocked,"RETAINED_ORIGINAL_ENTRYPOINT_CLASS_MISSING"):
            self.checked(o,c)

    def test_nonstartup_oem_missing_parent_is_still_unqualified(self):
        o,c=model()
        dormant="Lvendor/legacy/Dormant;"
        o["definitions"][dormant]=["Lvendor/external/OnlyAtRuntime;"]
        c["definitions"][dormant]=["Lvendor/external/OnlyAtRuntime;"]
        report=self.checked(o,c)
        self.assertEqual(report["original_baseline_unresolved_parent_edges"],0)
        self.assertEqual(report["original_total_unresolved_parent_edges"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_BASELINE_PARENT_UNVERIFIED"):
            audit.qualify_report(report)

    def test_nonentrypoint_sidecar_missing_parent_is_fatal(self):
        o,c=model()
        c["definitions"]["Lloftdj/NewHiddenSidecar;"]=["Lvendor/NotBundled;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED"):
            self.checked(o,c)

    def test_unresolved_interface_is_baseline_risk_not_green(self):
        o,c=model()
        owner="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        unknown="Lvendor/legacy/UnknownInterface;"
        o["definitions"][owner]=[BASE,unknown]
        c["definitions"][owner]=[BASE,unknown]
        report=self.checked(o,c)
        self.assertEqual(report["original_total_unresolved_parent_edges"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_BASELINE_PARENT_UNVERIFIED"):
            audit.qualify_report(report)

    def test_report_gate_denies_false_pass_flags(self):
        o,c=model();report=self.checked(o,c)
        for tamper in (
            {"original_total_unresolved_parent_edges":1},
            {"original_baseline_unresolved_parent_edges":1},
            {"real_oem_read":False},
            {"runtime":"TESTED"},
            {"status":"UNREVIEWED"},
        ):
            invalid=dict(report,**tamper)
            with self.subTest(tamper=tamper),self.assertRaisesRegex(audit.AuditBlocked,"PRIVATE_REPORT_INVALID"):
                audit.qualify_report(invalid)

    def test_cli_returns_nonzero_on_sanitized_unresolved_baseline(self):
        o,c=model()
        target="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"][target]=["Lvendor/private/DoNotPrint;"]
        c["definitions"][target]=["Lvendor/private/DoNotPrint;"]
        report=self.checked(o,c)
        out,err=StringIO(),StringIO()
        with (patch.object(audit,"audit_pair",return_value=report),
              patch("sys.argv",["audit","--original","dummy-original.apk","--candidate","dummy-clone.apk"]),
              redirect_stdout(out),redirect_stderr(err)):
            exit_code=audit.main()
        self.assertEqual(exit_code,2)
        self.assertIn("ORIGINAL_BASELINE_PARENT_UNVERIFIED",err.getvalue())
        self.assertIn('"original_total_unresolved_parent_edges": 1',out.getvalue())
        self.assertNotIn("DoNotPrint",out.getvalue()+err.getvalue())

    def test_cli_returns_zero_only_for_complete_static_graph(self):
        o,c=model();report=self.checked(o,c)
        with (patch.object(audit,"audit_pair",return_value=report),
              patch("sys.argv",["audit","--original","dummy-original.apk","--candidate","dummy-clone.apk"]),
              redirect_stdout(StringIO()),redirect_stderr(StringIO())):
            self.assertEqual(audit.main(),0)

class TestOriginalOnlyPrebuild(unittest.TestCase):
    def report(self,original):
        with patch.object(audit,"inspect_original"), \
             patch.object(audit,"inspect_apk",return_value=original):
            return audit.original_prebuild_report(Path("synthetic-original.apk"))

    def test_clean_original_needs_no_clone_build(self):
        original,_=model()
        report=self.report(original)
        self.assertEqual(report["original_total_missing_parent_edges"],0)
        self.assertEqual(report["original_declared_removed_providers"],2)
        self.assertEqual(report["runtime"],"NOT_TESTED")
        self.assertTrue(audit.qualify_original_prebuild(report))

    def test_dormant_original_missing_parent_fails_prebuild(self):
        original,_=model()
        original["definitions"]["Lsynthetic/dormant/Only;"]=["Lsynthetic/unbundled/Parent;"]
        report=self.report(original)
        self.assertEqual(report["original_missing_startup_parent_edges"],0)
        self.assertEqual(report["original_total_missing_parent_edges"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_PARENT_UNVERIFIED"):
            audit.qualify_original_prebuild(report)

    def test_missing_removed_provider_class_is_only_exactly_exempted(self):
        original,_=model()
        for name in audit.REMOVED:
            original["definitions"].pop("L"+name.replace(".","/")+";")
        report=self.report(original)
        self.assertEqual(report["status"],"PRIVATE_ORIGINAL_BASELINE_STRUCTURAL_READY_RUNTIME_NOT_TESTED")
        self.assertTrue(audit.qualify_original_prebuild(report))

    def test_missing_retained_root_blocks_before_any_clone_build(self):
        original,_=model()
        original["definitions"].pop("Lcom/yeelight/cherry/Activity0;")
        report=self.report(original)
        self.assertEqual(report["manifest_missing_by_role"],{"activity":1})
        self.assertEqual(report["manifest_missing_required_count"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED"):
            audit.qualify_original_prebuild(report)

    def test_original_provider_count_drift_is_rejected(self):
        original,_=model()
        del original["components"][("provider","com.yeelight.cherry.OtherFour")]
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_COMPONENT_COUNTS"):
            self.report(original)

    def test_ambiguous_prebuild_report_never_qualifies(self):
        original,_=model();report=self.report(original)
        variants=[
            {"status":"PRIVATE_ORIGINAL_BASELINE_UNRESOLVED_PREBUILD_BLOCKED"},
            {"original_total_missing_parent_edges":1},
            {"real_oem_read":False},
            {"runtime":"TESTED"},
            {"original_declared_removed_providers":1},
        ]
        for values in variants:
            with self.subTest(values=values), \
                 self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_REPORT_INVALID"):
                audit.qualify_original_prebuild(dict(report,**values))

    def test_original_only_cli_is_sanitized_and_failure_exits_2(self):
        original,_=model()
        original["definitions"]["Lsynthetic/dormant/Private;"]=["Lsynthetic/private/DoNotLog;"]
        prebuilt=self.report(original)
        with patch.object(audit,"original_prebuild_report",return_value=prebuilt), \
             patch("sys.argv",["audit","--original","synthetic.apk","--original-only"]), \
             redirect_stdout(StringIO()) as output,redirect_stderr(StringIO()) as errors:
            exitcode=audit.main()
        self.assertEqual(exitcode,2)
        self.assertIn("ORIGINAL_PREBUILD_PARENT_UNVERIFIED",errors.getvalue())
        self.assertIn('"original_total_missing_parent_edges": 1',output.getvalue())
        self.assertNotIn("DoNotLog",output.getvalue()+errors.getvalue())

    def test_original_only_cli_rejects_candidate_input(self):
        with patch("sys.argv",["audit","--original","synthetic.apk",
                              "--original-only","--candidate","not-allowed.apk"]), \
             redirect_stderr(StringIO()) as errors:
            self.assertEqual(audit.main(),2)
        self.assertIn("ORIGINAL_PREBUILD_CANDIDATE_FORBIDDEN",errors.getvalue())

class TestManifestReferenceRoles(unittest.TestCase):
    def node(self,tag,**values):
        return {"tag":tag,"attributes":{audit.A+k:v for k,v in values.items()}}

    def test_binary_manifest_roles_cover_alias_factory_navigation_and_library(self):
        p={"elements":[{"tag":"manifest","attributes":{"package":audit.OLD}},
            self.node("application",name=".App",appComponentFactory="android.app.AppComponentFactory",
                      backupAgent=".Backup",manageSpaceActivity=".Storage",
                      zygotePreloadName=".Preload"),
            self.node("activity",name=".Home",parentActivityName=".Parent"),
            self.node("activity",name=".Parent"),
            self.node("activity-alias",name=".QuickStart",targetActivity=".Home"),
            self.node("service",name=".Music"),
            self.node("receiver",name=".Wake"),
            self.node("provider",name=".StorageProvider"),
            self.node("instrumentation",name=".TestRunner"),
            self.node("uses-library",name="synthetic.required.library")]}
        pkg,components,entries,refs=audit.manifest_index(p)
        self.assertEqual(pkg,audit.OLD)
        self.assertIn(("activity-alias",audit.OLD+".QuickStart"),components)
        self.assertNotIn("Lcom/yeelight/cherry/QuickStart;",entries)
        self.assertEqual(refs[("targetActivity","Lcom/yeelight/cherry/Home;")],1)
        self.assertEqual(refs[("parentActivityName","Lcom/yeelight/cherry/Parent;")],1)
        self.assertEqual(refs[("appComponentFactory","Landroid/app/AppComponentFactory;")],1)
        self.assertEqual(refs[("backupAgent","Lcom/yeelight/cherry/Backup;")],1)
        self.assertEqual(refs[("manageSpaceActivity","Lcom/yeelight/cherry/Storage;")],1)
        self.assertEqual(refs[("zygotePreloadName","Lcom/yeelight/cherry/Preload;")],1)
        self.assertEqual(refs[("instrumentation","Lcom/yeelight/cherry/TestRunner;")],1)

    def test_alias_requires_a_target_but_not_a_dex_alias_class(self):
        parsed={"elements":[{"tag":"manifest","attributes":{"package":audit.OLD}},
                             self.node("activity-alias",name=".Broken")]}
        with self.assertRaisesRegex(audit.AuditBlocked,"MANIFEST_ALIAS_TARGET_MISSING"):
            audit.manifest_index(parsed)

    def test_default_application_does_not_imply_a_private_class(self):
        parsed={"elements":[{"tag":"manifest","attributes":{"package":audit.OLD}},
                             self.node("application")]}
        _,components,entries,refs=audit.manifest_index(parsed)
        self.assertIn(("application","<default>"),components)
        self.assertEqual(len(entries),0)
        self.assertEqual(len(refs),0)

    def test_vendor_prefixed_external_class_is_not_android_framework(self):
        original,_=model()
        desc="Landroidx/placeholder/NotInDex;"
        original["manifest_references"][("appComponentFactory",desc)]+=1
        report=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(report["missing_by_role"]["appComponentFactory"],1)
        self.assertNotIn("appComponentFactory",report["framework_by_role"])

    def test_platform_class_outside_oem_dex_is_not_misclassified_missing(self):
        original,_=model()
        desc="Landroid/app/AppComponentFactory;"
        original["manifest_references"][("appComponentFactory",desc)]+=1
        report=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(report["framework_by_role"],{"appComponentFactory":1})
        self.assertEqual(report["missing_required"],0)

    def test_multiple_unresolved_roles_are_all_counted_without_symbols(self):
        original,_=model()
        original["manifest_references"][("appComponentFactory","Lvendor/private/One;")]+=1
        original["manifest_references"][("backupAgent","Lvendor/private/Two;")]+=1
        original["manifest_references"][("parentActivityName","Lvendor/private/Three;")]+=1
        summary=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(summary["missing_required"],1)
        self.assertEqual(summary["missing_supporting"],2)
        self.assertEqual(summary["missing_by_role"],
                         {"appComponentFactory":1,"backupAgent":1,"parentActivityName":1})
        self.assertNotIn("Lvendor/",str({k:v for k,v in summary.items()
                                         if k!="resolved_startup_roots"}))

    def test_alias_symbol_not_mistaken_for_missing_implementation(self):
        original,_=model()
        original["manifest_references"][("activity-alias","Lprivate/synthetic/Quick;")]+=1
        original["manifest_references"][("targetActivity","Lcom/yeelight/cherry/Activity0;")]+=1
        summary=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(summary["missing_required"],0)
        self.assertEqual(summary["missing_supporting"],0)
        self.assertEqual(summary["undeclared_alias_targets"],0)

    def test_alias_target_must_point_to_manifest_declared_activity(self):
        original,_=model()
        d="Lcom/yeelight/cherry/NotAnActivity;"
        original["definitions"][d]=[BASE]
        original["manifest_references"][("activity-alias","Lsynthetic/Alias;")]+=1
        original["manifest_references"][("targetActivity",d)]+=1
        summary=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(summary["undeclared_alias_targets"],1)

    def test_missing_reference_metadata_is_not_silently_inferred(self):
        original,_=model()
        original.pop("manifest_references")
        with self.assertRaisesRegex(audit.AuditBlocked,"MANIFEST_REFERENCE_METADATA_REQUIRED"):
            audit.classify_manifest_references(original)

    def test_shared_library_declaration_cannot_whitelist_missing_vendor_class(self):
        original,_=model()
        original["declared_shared_libraries"]=3
        original["manifest_references"][("service","Lvendor/thirdparty/MissingService;")]+=1
        summary=audit.classify_manifest_references(original,exempt_providers=audit.REMOVED)
        self.assertEqual(summary["missing_required"],1)

class TestOriginalReferenceReports(unittest.TestCase):
    def report(self,original):
        with patch.object(audit,"inspect_original"), \
             patch.object(audit,"inspect_apk",return_value=original):
            return audit.original_prebuild_report(Path("synthetic-original.apk"))

    def test_report_collects_required_and_supporting_gaps_before_failing(self):
        original,_=model()
        original["manifest_references"][("appComponentFactory","Lvendor/HiddenFactory;")]+=1
        original["manifest_references"][("backupAgent","Lvendor/HiddenBackup;")]+=1
        result=self.report(original)
        self.assertEqual(result["manifest_missing_by_role"],
                         {"appComponentFactory":1,"backupAgent":1})
        self.assertEqual(result["manifest_missing_required_count"],1)
        self.assertEqual(result["manifest_missing_support_count"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED"):
            audit.qualify_original_prebuild(result)

    def test_only_optional_parent_missing_produces_separate_failure(self):
        original,_=model()
        original["manifest_references"][("parentActivityName","Lsynthetic/UnknownParent;")]+=1
        result=self.report(original)
        self.assertEqual(result["manifest_missing_support_count"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_SUPPORT_CLASS_UNVERIFIED"):
            audit.qualify_original_prebuild(result)

    def test_framework_factory_can_be_externally_resolved_without_static_class_def(self):
        original,_=model()
        original["manifest_references"][("appComponentFactory","Landroid/app/AppComponentFactory;")]+=1
        result=self.report(original)
        self.assertEqual(result["manifest_framework_by_role"],{"appComponentFactory":1})
        self.assertEqual(result["runtime"],"NOT_TESTED")
        self.assertTrue(audit.qualify_original_prebuild(result))

    def test_undeclared_alias_target_blocks_even_if_class_in_dex(self):
        original,_=model()
        d="Lsynthetic/HasDexButNoManifestActivity;"
        original["definitions"][d]=[BASE]
        original["manifest_references"][("activity-alias","Lsynthetic/Alias;")]+=1
        original["manifest_references"][("targetActivity",d)]+=1
        result=self.report(original)
        self.assertEqual(result["manifest_invalid_alias_target_count"],1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_ALIAS_TARGET_UNDECLARED"):
            audit.qualify_original_prebuild(result)

    def test_report_never_exposes_proprietary_class_names(self):
        original,_=model()
        d="Lprivate/OEM_SECRET_CLASS_NOT_FOR_LOGS;"
        original["manifest_references"][("backupAgent",d)]+=1
        report=self.report(original)
        output=__import__("json").dumps(report,sort_keys=True)
        self.assertNotIn("OEM_SECRET_CLASS",output)
        self.assertIn('"backupAgent": 1',output)

    def test_invalid_success_report_with_missing_category_is_blocked(self):
        original,_=model()
        report=self.report(original)
        altered=dict(report,status="PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
                     manifest_missing_required_count=1)
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_PREBUILD_REPORT_INVALID"):
            audit.qualify_original_prebuild(altered)

    def test_original_and_candidate_manifest_role_changes_rejected(self):
        o,c=model()
        c["manifest_references"][("backupAgent","Landroid/app/BackupAgent;")]+=1
        with patch.object(audit,"inspect_original"), \
             patch.object(audit,"inspect_apk",side_effect=[o,c]):
            with self.assertRaisesRegex(audit.AuditBlocked,"CLONE_MANIFEST_REFERENCE_ROLE_DRIFT"):
                audit.audit_pair(Path("synthetic-original.apk"),Path("synthetic-clone.apk"))

if __name__=="__main__":unittest.main()
