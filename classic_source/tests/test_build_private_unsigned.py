import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from build_private_unsigned import verify_unsigned, verify_final_firebase_boundary, verify_final_component_classes
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

    def test_five_retained_providers_in_private_clone(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(5)):
            r = verify_final_firebase_boundary(self.candidate)
        self.assertEqual(r['status'], 'PRIVATE_EARLY_STARTUP_PROVIDERS_RECONCILED')
        self.assertEqual(r['android_startup'], 'NOT_TESTED')

    def test_firebase_class_reintroduced_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(5, firebase=True)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_FIREBASE_INIT_REINTRODUCED'):
                verify_final_firebase_boundary(self.candidate)

    def test_firebase_authority_reintroduced_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(5, authority=True)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_FIREBASE_AUTHORITY_REINTRODUCED'):
                verify_final_firebase_boundary(self.candidate)

    def test_legacy_device_provider_reintroduced_is_rejected(self):
        names = [f'com.yeelight.cherry.P{i}' for i in range(4)] + ['com.yeelight.yeelib.data.DeviceInteractionProvider']
        with patch('build_private_unsigned.parse_binary_xml', return_value={
            'elements': [{'tag':'provider', 'attributes':{
                '{http://schemas.android.com/apk/res/android}name': n,
                '{http://schemas.android.com/apk/res/android}authorities': f'io.lxsdd.yeelightdj.p{i}'}}
                for i,n in enumerate(names)]}):
            with self.assertRaisesRegex(GateFailure, 'FINAL_MISSING_DEVICE_PROVIDER_REINTRODUCED'):
                verify_final_firebase_boundary(self.candidate)

    def test_wrong_original_provider_count_rejected(self):
        with patch('build_private_unsigned.parse_binary_xml', return_value=self.model(4)):
            with self.assertRaisesRegex(GateFailure, 'FINAL_PROVIDER_COUNT_NOT_FIVE'):
                verify_final_firebase_boundary(self.candidate)

    def test_corrupt_final_binary_xml_rejected(self):
        with self.assertRaisesRegex(GateFailure, 'FINAL_BINARY_MANIFEST_INVALID'):
            verify_final_firebase_boundary(self.candidate)

class PrivateStartupClassResolutionGate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.candidate = Path(self.temp.name) / "clone.apk"
        with zipfile.ZipFile(self.candidate, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic")
            for name in ("classes.dex","classes2.dex","classes3.dex"):
                archive.writestr(name, b"synthetic-dex")

    def manifest(self, missing=False, app_missing=False, providers=5):
        ns = "{http://schemas.android.com/apk/res/android}name"
        app = "com.yeelight.cherry.App"
        names = [f"com.yeelight.cherry.Provider{i}" for i in range(providers)]
        if missing:
            names[-1] = "com.yeelight.yeelib.data.DeviceInteractionProvider"
        elements = [{"tag": "application", "attributes": {ns: app}}]
        elements += [{"tag": "provider", "attributes": {ns: name}} for name in names]
        all_defined = {"L"+name.replace(".","/")+";" for name in names+[app]}
        if missing:
            all_defined.discard("L"+names[-1].replace(".","/")+";")
        if app_missing:
            all_defined.discard("L"+app.replace(".","/")+";")
        return {"elements":elements}, all_defined

    def test_all_retained_classes_are_defined(self):
        manifest, definitions = self.manifest()
        with patch('build_private_unsigned.parse_binary_xml', return_value=manifest), \
             patch('build_private_unsigned.dex_descriptors', side_effect=[definitions, set(), set()]):
            result = verify_final_component_classes(self.candidate)
        self.assertEqual(result['status'], 'FINAL_PROVIDER_AND_APPLICATION_CLASSES_PRESENT')
        self.assertEqual(result['verified_component_classes'], 6)
        self.assertEqual(result['android_startup'], 'NOT_TESTED')

    def test_missing_oem_provider_fails_before_signing(self):
        manifest, definitions = self.manifest(missing=True)
        with patch('build_private_unsigned.parse_binary_xml', return_value=manifest), \
             patch('build_private_unsigned.dex_descriptors', side_effect=[definitions, set(), set()]):
            with self.assertRaisesRegex(GateFailure, 'FINAL_APP_OR_PROVIDER_CLASS_NOT_IN_APK'):
                verify_final_component_classes(self.candidate)

    def test_missing_application_class_fails_before_signing(self):
        manifest, definitions = self.manifest(app_missing=True)
        with patch('build_private_unsigned.parse_binary_xml', return_value=manifest), \
             patch('build_private_unsigned.dex_descriptors', side_effect=[definitions, set(), set()]):
            with self.assertRaisesRegex(GateFailure, 'FINAL_APP_OR_PROVIDER_CLASS_NOT_IN_APK'):
                verify_final_component_classes(self.candidate)

    def test_duplicate_classes_across_dex_fail(self):
        manifest, definitions = self.manifest()
        with patch('build_private_unsigned.parse_binary_xml', return_value=manifest), \
             patch('build_private_unsigned.dex_descriptors', side_effect=[definitions, definitions, set()]):
            with self.assertRaisesRegex(GateFailure, 'FINAL_CLONE_DUPLICATE_DEX_CLASS'):
                verify_final_component_classes(self.candidate)

    def test_component_provider_count_changed_fails(self):
        manifest, definitions = self.manifest(providers=4)
        with patch('build_private_unsigned.parse_binary_xml', return_value=manifest), \
             patch('build_private_unsigned.dex_descriptors', side_effect=[definitions, set(), set()]):
            with self.assertRaisesRegex(GateFailure, 'FINAL_COMPONENT_DECLARATIONS_CHANGED'):
                verify_final_component_classes(self.candidate)

if __name__ == '__main__': unittest.main()
