import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from build_private_unsigned import verify_unsigned, verify_final_firebase_boundary
from classic_first import GateFailure, NEW_PACKAGE

class FakeRun:
    def __init__(self, badging):
        self.returncode = 0
        self.stdout = "package: name='" + badging + "' versionCode='35402'\n"

class FinalVerification(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.orig = self.root/'orig.apk'
        self.final = self.root/'unsigned.apk'
        self.bridge = self.root/'bridge.dex'
        self.bridge.write_bytes(b'dex\n039\0' + b'B'*160)
        self.make_output({})
    def make_output(self, extras):
        original = {'AndroidManifest.xml':b'origmanifest', 'classes.dex':b'original-one', 'classes2.dex':b'original-two'}
        with zipfile.ZipFile(self.orig,'w') as z:
            for k,v in original.items(): z.writestr(k,v)
        output = dict(original)
        output['AndroidManifest.xml'] = b'fork manifest'
        output['classes3.dex'] = self.bridge.read_bytes()
        output.update(extras)
        with zipfile.ZipFile(self.final,'w') as z:
            for k,v in output.items(): z.writestr(k,v)
    def verified(self, badging=NEW_PACKAGE):
        with patch('build_private_unsigned.subprocess.run',return_value=FakeRun(badging)):
            return verify_unsigned(self.final, self.orig, self.bridge, 'fake-aapt')
    def test_unsigned_payload_matches_original_executables_and_sidecar(self):
        r = self.verified()
        self.assertEqual(r['status'], 'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED')
        self.assertEqual(r['oem_dex_verified'], ['classes.dex','classes2.dex'])
        self.assertEqual(r['sidecar_dex'], 'classes3.dex')
        self.assertFalse(r['installed'])
    def test_original_dex_mutation_rejected(self):
        self.make_output({'classes.dex':b'wrong-original'})
        with self.assertRaisesRegex(GateFailure, 'OEM_DEX_TRANSFORM_MISMATCH'):
            self.verified()
    def test_stale_signature_rejected(self):
        self.make_output({'META-INF/FOO.RSA':b'old-signature'})
        with self.assertRaisesRegex(GateFailure,'STALE_SIGNATURE'):
            self.verified()
    def test_unexpected_final_package_rejected(self):
        with self.assertRaisesRegex(GateFailure,'NEW_PACKAGE_NOT_IN_FINAL'):
            self.verified('com.yeelight.cherry')
    def test_missing_extra_dex_rejected(self):
        self.make_output({'classes3.dex': b'different-bytecode'})
        with self.assertRaisesRegex(GateFailure,'SIDECAR_DEX_MISMATCH'):
            self.verified()

class FirebaseFinalManifestGate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.candidate = Path(self.temp.name)/'test.apk'
        with zipfile.ZipFile(self.candidate, 'w') as z:
            z.writestr('AndroidManifest.xml', b'synthetic')

    def model(self, count, firebase=False, authority=False):
        ns = '{http://schemas.android.com/apk/res/android}'
        return {'elements': [{'tag': 'provider', 'attributes': {
            ns+'name': 'com.google.firebase.provider.FirebaseInitProvider' if firebase and i == count-1 else f'com.yeelight.cherry.P{i}',
            ns+'authorities': 'io.lxsdd.yeelightdj.firebaseinitprovider' if authority and i == count-1 else f'io.lxsdd.yeelightdj.p{i}'}}
            for i in range(count)]}

    def test_six_other_original_providers_preserved(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(6)):
            r = verify_final_firebase_boundary(self.candidate)
        self.assertEqual(r['status'], 'PRIVATE_FIREBASE_AUTO_INIT_DISABLED')
        self.assertEqual(r['android_startup'], 'NOT_TESTED')

    def test_firebase_class_reintroduced_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(6, firebase=True)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_FIREBASE_INIT_REINTRODUCED'):
                verify_final_firebase_boundary(self.candidate)

    def test_firebase_authority_reintroduced_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(6, authority=True)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_FIREBASE_AUTHORITY_REINTRODUCED'):
                verify_final_firebase_boundary(self.candidate)

    def test_wrong_original_provider_count_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(5)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_PROVIDER_COUNT_NOT_SIX'):
                verify_final_firebase_boundary(self.candidate)

    def test_corrupt_final_binary_xml_rejected(self):
        with self.assertRaisesRegex(GateFailure, 'FINAL_BINARY_MANIFEST_INVALID'):
            verify_final_firebase_boundary(self.candidate)

if __name__ == '__main__': unittest.main()
