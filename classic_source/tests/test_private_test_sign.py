"""Source-only safety regressions: no apktool/keys/Android SDK required."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SRC=Path(__file__).resolve().parents[1]/'private_test_sign.py'
spec=importlib.util.spec_from_file_location('private_test_sign',SRC)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class TestPrivateTestSigning(unittest.TestCase):
    def test_refuses_non_classic_apk_before_any_tool_used(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);src=root/'original.apk';src.write_bytes(b'not a classic APK')
            report=root/'preflight.json';report.write_text('{}')
            with self.assertRaises(mod.Refused):
                mod.sign(src,report,root/mod.OUTPUT_NAME,root)
    def test_refuses_structural_only_report_missing_package(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);src=root/mod.INPUT_NAME;src.write_bytes(b'A'*1100)
            report=root/'preflight.json';report.write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False,'installed':False,'apk_sha256':mod.sha256(src)
            }))
            with self.assertRaises(mod.Refused):
                mod.sign(src,report,root/mod.OUTPUT_NAME,root)
    def test_refuses_mismatching_preflight_digest(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);src=root/mod.INPUT_NAME;src.write_bytes(b'A'*1100)
            report=root/'preflight.json';report.write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False,'installed':False,'package':mod.PACKAGE,'apk_sha256':'0'*64
            }))
            with self.assertRaises(mod.Refused):
                mod.sign(src,report,root/mod.OUTPUT_NAME,root)
    def test_private_test_signing_positive_mock_has_no_persistent_key_or_plaintext_secret(self):
        """End-to-end state transition with simulated Android SDK tools; not a real signed APK."""
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            unsigned=root/mod.INPUT_NAME
            unsigned.write_bytes(b'ANDROID-UNSIGNED-SYNTHETIC-TEST'*90)
            receipt=root/'unsigned.preflight.json'
            receipt.write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False,'installed':False,'package':mod.PACKAGE,
                'apk_sha256':mod.sha256(unsigned)
            }))
            sdk=root/'sdk';tools=sdk/'build-tools/36.0.0';tools.mkdir(parents=True)
            for name in ('apksigner','zipalign'): (tools/name).write_text('mock')
            signed=root/mod.OUTPUT_NAME
            seen=[]
            def pretend(args,*,env=None):
                args=[str(a) for a in args];seen.append(args)
                assert env.get('CLASSIC_EPHEMERAL_SIGN_PASS')
                if '-genkeypair' in args: Path(args[args.index('-keystore')+1]).write_bytes(b'private-key-in-memory-only')
                elif Path(args[0]).name=='zipalign' and '-c' not in args:
                    Path(args[-1]).write_bytes(Path(args[-2]).read_bytes())
                elif Path(args[0]).name=='apksigner' and args[1]=='sign':
                    Path(args[args.index('--out')+1]).write_bytes(b'cryptographically-fake-signed\n'+Path(args[-1]).read_bytes())
            with patch.object(mod.shutil,'which',return_value='/usr/bin/keytool'):
                with patch.object(mod,'run',side_effect=pretend):
                    record=mod.sign(unsigned,receipt,signed,sdk)
            self.assertTrue(signed.is_file())
            self.assertTrue(record['signed'])
            self.assertFalse(record['installed'])
            self.assertEqual(record['package'],mod.PACKAGE)
            self.assertEqual(record['apk_sha256'],mod.sha256(signed))
            self.assertEqual(record['signature'],'EPHEMERAL_UNIQUE_TEST_KEY_NOT_SUITABLE_FOR_APP_UPDATES')
            self.assertIn('sign', [a[1] for a in seen if Path(a[0]).name=='apksigner'])
            self.assertIn('verify', [a[1] for a in seen if Path(a[0]).name=='apksigner'])
            alignment = [a for a in seen if Path(a[0]).name == 'zipalign']
            self.assertEqual(alignment[0][1:5], ['-f', '-P', '16', '4'])
            self.assertEqual(alignment[1][1:5], ['-c', '-P', '16', '4'])
            self.assertEqual(record['native_zip_alignment_bytes'], 16384)
            self.assertTrue(record['signed_zip_alignment_verified'])
            self.assertFalse(any(p.suffix in ('.p12','.jks') for p in root.rglob('*')))
            self.assertNotIn('CLASSIC_EPHEMERAL_SIGN_PASS',signed.with_name(signed.name+'.test-signing.json').read_text())

    def test_failed_final_alignment_never_copies_output_or_receipt(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / mod.INPUT_NAME
            source.write_bytes(b'synthetic' * 200)
            preflight = root / 'preflight.json'
            preflight.write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False, 'installed':False, 'package':mod.PACKAGE,
                'apk_sha256':mod.sha256(source)}))
            output = root / mod.OUTPUT_NAME
            def run(args, *, env=None):
                if str(args[0]).endswith('zipalign') and '-c' in args:
                    raise subprocess.CalledProcessError(1, ['zipalign'])
                if 'sign' in args:
                    Path(args[args.index('--out') + 1]).write_bytes(b'signed' * 300)
            with patch.object(mod, 'tool', side_effect=lambda sdk, name: name), \
                    patch.object(mod.shutil, 'which', return_value='keytool'), \
                    patch.object(mod, 'run', side_effect=run), \
                    self.assertRaises(subprocess.CalledProcessError):
                mod.sign(source, preflight, output, root)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_name(output.name + '.test-signing.json').exists())

    def test_refuses_prior_existing_signed_output(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);src=root/mod.INPUT_NAME;src.write_bytes(b'A'*1100)
            report=root/'preflight.json';report.write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False,'installed':False,'package':mod.PACKAGE,'apk_sha256':mod.sha256(src)
            }))
            output=root/mod.OUTPUT_NAME;output.write_bytes(b'foreign')
            with self.assertRaises(mod.Refused):
                mod.sign(src,report,output,root)

if __name__=='__main__':unittest.main()
