"""Owner-only private build failure telemetry: closed output and safe fallback."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from private_ci import private_build_diagnostics as diag
from private_ci.emit_oem_ui_evidence import sanitized, EvidenceBlocked

class PrivateBuildDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        diag.phase("PRECONDITION")

    def environment(self, root, summary):
        return {
            "RUNNER_TEMP": str(root),
            "GITHUB_STEP_SUMMARY": str(summary),
            "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_ACTOR": "lxsdd",
            "RUNNER_ENVIRONMENT": "github-hosted",
        }

    def test_owner_scoped_summary_emits_phase_only_without_exception_details(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            base=root/"_runner_file_commands"
            base.mkdir()
            summary=base/"step_summary_a1"
            env=self.environment(root, summary)
            diag.phase("OPTIONAL_UI_AUDIT")
            class UiAuditBlocked(ValueError): pass
            secret="email@example.com com.yeelight.cherry private:xxx"
            self.assertTrue(diag.record_failure(UiAuditBlocked(secret), env))
            output=summary.read_text("utf8")
            self.assertIn("OPTIONAL_UI_AUDIT",output)
            self.assertIn("ORIGINAL_UI_AUDIT",output)
            self.assertIn("UI_ANALYSIS_RUNTIME_UNAVAILABLE",output)
            for value in ("email@example.com", "com.yeelight.cherry", "xxx",str(root)):
                self.assertNotIn(value,output)
            self.assertTrue(diag.record_optional_ui_unavailable("UI_LAYOUT_NODE_LIMIT", env))
            self.assertIn("UI_LAYOUT_NODE_LIMIT",summary.read_text("utf8"))
            self.assertTrue(diag.record_failure(RuntimeError("credential=abc"),env))
            self.assertIn("UNEXPECTED_BUILD_EXCEPTION",summary.read_text("utf8"))
            self.assertNotIn("credential=abc",summary.read_text("utf8"))

    def test_wrong_actor_branch_repo_or_path_never_writes_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); sub=root/"_runner_file_commands";sub.mkdir()
            good=sub/"step_summary_test"
            env=self.environment(root,good)
            for key,value in [
                ("GITHUB_ACTOR","foreign"),
                ("GITHUB_REPOSITORY","untrusted/repo"),
                ("GITHUB_REF","refs/heads/untrusted"),
                ("GITHUB_EVENT_NAME","pull_request"),
                ("RUNNER_ENVIRONMENT","self-hosted"),
                ("GITHUB_STEP_SUMMARY",str(root/"otherfile")),
                ("GITHUB_STEP_SUMMARY","/tmp/stranger/step_summary_test")
            ]:
                bad=dict(env);bad[key]=value
                with self.subTest(field=key,value=value):
                    self.assertFalse(diag.record_failure(RuntimeError("secret"),bad))
                    self.assertFalse(diag.record_optional_ui_unavailable("UI_LAYOUT_NODE_LIMIT",bad))
            self.assertFalse(good.exists())

    def test_unrecognized_analyst_failure_cannot_claim_success_or_private_data(self):
        self.assertEqual(diag.safe_ui_reason("UI_XML_PARSE_INVALID"),"UI_XML_PARSE_INVALID")
        for value in ("error: private OriginalClass: token", None, 12, "UI_PWNED"):
            self.assertEqual(diag.safe_ui_reason(value),
                             "UI_ANALYSIS_RUNTIME_UNAVAILABLE")
        with self.assertRaises(ValueError):
            diag.phase("SOME_UNTRUSTED_STAGE")

    def test_explicit_unavailable_result_is_never_a_fabricated_roles_success(self):
        record={
            "status":"UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
            "package":"io.lxsdd.yeelightdj",
            "signed":False, "installed":False,
            "identity_control_offline_only":False,
            "original_ui_reuse_inventory":{
                "status":"ORIGINAL_UI_REUSE_AUDIT_UNAVAILABLE_NOT_ACCEPTED",
                "safe_reason":"UI_XML_PARSE_INVALID",
                "original_apk_or_smali_published":False,
                "private_symbols_emitted":False,
                "runtime_ui_navigation_verified":False,
                "original_behavior_compatibility_verified":False,
                "production_hook_approved":False,
            },
        }
        result=sanitized(record)
        self.assertEqual(result["status"],"OEM_UI_REUSE_ANALYSIS_UNAVAILABLE_NOT_ACCEPTED")
        self.assertEqual(result["reason"],"UI_XML_PARSE_INVALID")
        self.assertEqual(result["roles"],{})
        self.assertFalse(result["actual_original_ui_runtime_proven"])
        self.assertFalse(result["oem_names_text_secrets_emitted"])
        for invalid in (
            ("production_hook_approved", True),
            ("private_symbols_emitted",True),
            ("runtime_ui_navigation_verified",True),
            ("safe_reason","CREDENTIAL=private"),
        ):
            key,value=invalid
            clone=json.loads(json.dumps(record))
            clone["original_ui_reuse_inventory"][key]=value
            with self.subTest(field=key):
                with self.assertRaises(EvidenceBlocked):
                    sanitized(clone)
        clone=json.loads(json.dumps(record))
        clone["original_ui_reuse_inventory"]["roles"]={"music":"FALSE_ACCEPT"}
        with self.assertRaises(EvidenceBlocked):
            sanitized(clone)

    def test_builder_is_instrumented_without_waiving_parity_or_signed_checks(self):
        path=Path(__file__).resolve().parents[1]/"build_private_unsigned.py"
        code=path.read_text("utf8")
        self.assertIn("except (UiAuditBlocked, OSError, UnicodeError, ValueError, TypeError)",code)
        self.assertIn('"ORIGINAL_UI_REUSE_AUDIT_UNAVAILABLE_NOT_ACCEPTED"',code)
        self.assertIn("build_diagnostics.record_optional_ui_unavailable(safe_reason)",code)
        self.assertIn("build_diagnostics.record_failure(error)",code)
        self.assertIn("build_diagnostics.phase('OEM_SMALI_REASSEMBLY')",code)
        self.assertIn("stage_reassembled_oem(original, codeonly, staged)",code)
        self.assertIn("verify_unsigned(",code)
        self.assertIn("verify_final_component_classes(",code)
        self.assertLess(code.index("original_ui_reuse_audit(decoded, codeonly)"),
                        code.index("stage_reassembled_oem(original, codeonly, staged)"))

if __name__=="__main__":
    unittest.main()
