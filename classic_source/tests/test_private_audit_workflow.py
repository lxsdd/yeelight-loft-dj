"""Safety gates for private Android original tests, payload parity and workflow order."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import run_real_oem_tests
import private_payload_parity

ROOT=Path(__file__).resolve().parents[2]

class TestPrivatePayloadParity(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.a=Path(self.tmp.name)/"unsigned.apk"
        self.b=Path(self.tmp.name)/"signed.apk"
    def apk(self,at,dex=b"TEST-DEX",extra=True):
        with ZipFile(at,"w") as z:
            z.writestr("AndroidManifest.xml",b"MANIFEST")
            z.writestr("classes.dex",dex)
            z.writestr("classes2.dex",b"OEM")
            z.writestr("classes3.dex",b"DJ")
            if extra:z.writestr("META-INF/TEST.SF",b"TEST-SIGNATURE")
    def test_signing_metadata_differences_allowed(self):
        self.apk(self.a,extra=False)
        self.apk(self.b,extra=True)
        report=private_payload_parity.parity(self.a,self.b)
        self.assertEqual(report["status"],"SIGNED_STARTUP_PAYLOAD_IDENTICAL_NOT_RUNTIME_TESTED")
        self.assertEqual(report["audited_entries"],4)
    def test_mutated_dex_denied(self):
        self.apk(self.a,extra=False)
        self.apk(self.b,dex=b"DIFFERENT")
        with self.assertRaisesRegex(private_payload_parity.Refused,"SIGNED_STARTUP_PAYLOAD_CHANGED"):
            private_payload_parity.parity(self.a,self.b)
    def test_missing_signed_dex_denied(self):
        self.apk(self.a,extra=False)
        with ZipFile(self.b,"w") as z:
            z.writestr("AndroidManifest.xml",b"MANIFEST")
            z.writestr("classes.dex",b"TEST-DEX")
            z.writestr("classes2.dex",b"OEM")
        with self.assertRaisesRegex(private_payload_parity.Refused,"STARTUP_PAYLOAD_SET_CHANGED"):
            private_payload_parity.parity(self.a,self.b)

class TestMandatoryRealOemGate(unittest.TestCase):
    def test_no_oem_variable_refused(self):
        with patch.dict("os.environ",{"CLASSIC_ORIGINAL_APK":""}):
            with self.assertRaisesRegex(RuntimeError,"PINNED_REAL_OEM_PATH_NOT_SET"):
                run_real_oem_tests.run_tests()
    def test_missing_oem_file_refused(self):
        with patch.dict("os.environ",{"CLASSIC_ORIGINAL_APK":"/missing-real-oem-file.apk"}):
            with self.assertRaisesRegex(RuntimeError,"PINNED_REAL_OEM_FILE_MISSING"):
                run_real_oem_tests.run_tests()
    def test_fails_closed_on_fake_file(self):
        with tempfile.TemporaryDirectory() as path:
            candidate=Path(path)/"fake-original.apk"
            candidate.write_bytes(b"fake")
            with patch.dict("os.environ",{"CLASSIC_ORIGINAL_APK":str(candidate)}):
                with self.assertRaisesRegex(RuntimeError,"PINNED_REAL_OEM_SHA256_MISMATCH"):
                    run_real_oem_tests.run_tests()

class TestWorkflowContract(unittest.TestCase):
    def test_audit_path_owner_only_and_never_signs_or_uploads(self):
        text=(ROOT/".github/workflows/classic-private-startup-audit.yml").read_text()
        self.assertIn("workflow_dispatch:",text)
        self.assertNotIn("\n  pull_request:",text)
        self.assertNotIn("\n  push:",text)
        self.assertIn("classic-private-build",text)
        self.assertIn("github.actor == 'lxsdd'",text)
        self.assertIn("run_real_oem_tests.py",text)
        self.assertIn("private_startup_audit.py",text)
        self.assertIn("--original-only",text)
        self.assertLess(text.index("run_real_oem_tests.py"),text.index("--original-only"))
        self.assertLess(text.index("--original-only"),text.index("Build PRIVATE unsigned candidate"))
        self.assertEqual(text.count("private_startup_audit.py"),2)
        self.assertNotIn("private_test_sign.py",text)
        self.assertNotIn("publish_private_signed.sh",text)
        self.assertNotIn("upload-artifact",text)
    def test_signed_route_requires_real_oem_and_final_signed_class_audits(self):
        text=(ROOT/".github/workflows/classic-private-test-signed.yml").read_text()
        self.assertLess(text.index("Download exact original"),text.index("run_real_oem_tests.py"))
        self.assertLess(text.index("run_real_oem_tests.py"),text.index("Build and verify private unsigned"))
        self.assertLess(text.index("private_startup_audit.py"),text.index("Sign one private test APK"))
        self.assertLess(text.index("private_payload_parity.py"),text.index("Send TEST-SIGNED APK"))
        self.assertEqual(text.count("private_startup_audit.py"),2)
if __name__=="__main__":unittest.main()
