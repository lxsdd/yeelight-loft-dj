"""Synthetic closed-schema OEM UI evidence emission and scope regression."""
import json
from pathlib import Path
import tempfile
import unittest

from private_ci.emit_oem_ui_evidence import (
    ROLES, REFERENCE_COUNTS, sanitized, emit, EvidenceBlocked
)

def receipt():
    entries={}
    for key in ROLES:
        entries[key]={
            "binding_state": "UNMAPPED_NOT_PROVEN",
            "production_hook_approved": False,
            "original_layout_variants": 0,
            "unique_original_class_definitions": 0,
            "manifest_activity_definitions": 0,
            "source_reference_counts": {k:0 for k in REFERENCE_COUNTS}
        }
    entries["music"].update({
        "binding_state":"STATIC_LAYOUT_AND_CLASS_CANDIDATES",
        "original_layout_variants":2,
        "unique_original_class_definitions":1,
        "manifest_activity_definitions":1,
        "source_reference_counts": {k:3 for k in REFERENCE_COUNTS},
    })
    return {
        "status": "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
        "package": "io.lxsdd.yeelightdj",
        "signed": False, "installed": False,
        "identity_control_offline_only": False,
        "original_ui_reuse_inventory": {
            "status":"ORIGINAL_UI_REUSE_CANDIDATES_STATIC_ONLY_NOT_RUNTIME_VERIFIED",
            "source":"PINNED_ORIGINAL_DECODED_BY_EXISTING_PRIVATE_BUILD",
            "private_symbols_emitted":False,
            "original_apk_or_smali_published":False,
            "original_ui_methods_rewritten":False,
            "runtime_ui_navigation_verified":False,
            "original_behavior_compatibility_verified":False,
            "manifest_activity_count":185,
            "decoded_layout_count":350,
            "decoded_smali_file_count":5000,
            "roles":entries,
        },
        "raw_oem_string":"DO_NOT_EXPORT_privateuser@example.net",
        "original_secret":"SHOULD_NOT_APPEAR_IN_LOGS"
    }

class OemEvidenceTests(unittest.TestCase):
    def test_only_safe_allowlisted_fields_are_copied(self):
        obj=receipt()
        safe=sanitized(obj)
        self.assertEqual(safe["status"],"OEM_UI_REUSE_STATIC_COUNTS_ONLY")
        self.assertEqual(safe["roles"]["music"]["manifest_activity_definitions"],1)
        self.assertEqual(safe["roles"]["music"]["reference_counts"]["click_handlers"],3)
        self.assertFalse(safe["actual_original_ui_runtime_proven"])
        self.assertFalse(safe["oem_names_text_secrets_emitted"])
        raw=json.dumps(safe)
        for forbidden in ("DO_NOT_EXPORT","example.net","original_secret",
                          "raw_oem_string","OriginalClass","token"):
            self.assertNotIn(forbidden,raw)

    def test_invalid_receipts_never_output_partial_sensitive_state(self):
        cases=[]
        for key,value in (("status","BOGUS"),("signed",True),("installed",True),
                          ("identity_control_offline_only",True),
                          ("package","com.yeelight.cherry")):
            c=receipt();c[key]=value;cases.append(c)
        c=receipt();c["original_ui_reuse_inventory"]["roles"]["music"]["production_hook_approved"]=True;cases.append(c)
        c=receipt();c["original_ui_reuse_inventory"]["roles"]["music"]["source_reference_counts"]["click_handlers"]="1";cases.append(c)
        c=receipt();c["original_ui_reuse_inventory"]["roles"]["music"]["binding_state"]="BLESSED";cases.append(c)
        c=receipt();c["original_ui_reuse_inventory"]["roles"].pop("music");cases.append(c)
        c=receipt();c["original_ui_reuse_inventory"]["runtime_ui_navigation_verified"]=True;cases.append(c)
        for case in cases:
            with self.subTest(value=case.get("status")):
                with self.assertRaises(EvidenceBlocked):
                    sanitized(case)

    def test_owner_guarded_exact_path_and_no_live_connection(self):
        with tempfile.TemporaryDirectory() as t:
            path=Path(t)/"private-output/classic-loft-dj-UNSIGNED.apk.preflight.json"
            path.parent.mkdir()
            path.write_text(json.dumps(receipt()),encoding="utf8")
            env={"RUNNER_TEMP":t,"GITHUB_REPOSITORY":"lxsdd/yeelight-loft-dj",
                 "GITHUB_EVENT_NAME":"workflow_dispatch","GITHUB_ACTOR":"lxsdd",
                 "GITHUB_REF":"refs/heads/main","RUNNER_ENVIRONMENT":"github-hosted"}
            self.assertEqual(emit(path,env)["decoded_original_smali_files"],5000)
            bad=dict(env);bad["GITHUB_ACTOR"]="non-owner"
            with self.assertRaisesRegex(EvidenceBlocked,"UI_EVIDENCE_OWNER_SCOPE_REQUIRED"):
                emit(path,bad)
            with self.assertRaisesRegex(EvidenceBlocked,"UI_EVIDENCE_OWNER_SCOPE_REQUIRED"):
                emit(path.with_name("different.preflight.json"),env)
            path.unlink()
            with self.assertRaisesRegex(EvidenceBlocked,"UI_EVIDENCE_OWNER_SCOPE_REQUIRED"):
                emit(path,env)

    def test_protected_script_emits_only_after_verified_private_receipt(self):
        script=(Path(__file__).resolve().parents[1]/"private_ci/run_android17_emulator.sh").read_text("utf8")
        entry="classic_source/private_ci/emit_oem_ui_evidence.py"
        self.assertIn(entry,script)
        self.assertLess(script.index(entry),script.index("kvm_scope_acquire"))
        self.assertIn('classic-loft-dj-UNSIGNED.apk.preflight.json',script)
        self.assertNotIn("upload-artifact",script)
        self.assertNotIn("gh release",script)

if __name__=="__main__":
    unittest.main()
