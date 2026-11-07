import hashlib
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import classic_first as x

MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yeelight.cherry">
  <uses-sdk android:minSdkVersion="19" android:targetSdkVersion="33" />
  <permission android:name="com.yeelight.cherry.PERMISSION" android:protectionLevel="signature" />
  <uses-permission android:name="com.yeelight.cherry.PERMISSION"/>
  <application android:name=".ClassicApp" android:appComponentFactory=".Factories" android:allowBackup="false">
    <activity android:name=".MainActivity" android:exported="true"/>
    <activity-alias android:name=".Alias" android:targetActivity=".MainActivity" android:exported="false"/>
    <service android:name="com.yeelight.cherry.existing.Sync" android:exported="false"/>
    <provider android:name=".Provider" android:authorities="com.yeelight.cherry.device;com.yeelight.cherry.landevice" android:permission="com.yeelight.cherry.PERMISSION" android:exported="false"/>
    <provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="com.yeelight.cherry.firebaseinitprovider" android:exported="false"/>
  </application>
</manifest>'''
LAYOUT = '''<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 android:layout_width="match_parent" android:layout_height="match_parent">
 <TextView android:id="@+id/old" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Original Classic"/>
</LinearLayout>'''
DEX = b'dex\n039\0' + b'X'*200 + (b'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjActivity;'
        + b'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjEntryView;'
        + b'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjService;'
        + b'Lio/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime;')

class ClassicTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.decoded = self.base / 'decoded'
        self.decoded.mkdir()
        (self.decoded / 'res/layout').mkdir(parents=True)
        (self.decoded / 'AndroidManifest.xml').write_text(MANIFEST)
        (self.decoded / 'res/layout/activity_main_new.xml').write_text(LAYOUT)
        (self.decoded / 'classes.dex').write_bytes(b'one')
        (self.decoded / 'classes2.dex').write_bytes(b'two')
        (self.decoded / 'assets').mkdir()
        (self.decoded / 'assets/YeelightColor4.xml').write_bytes(b'profile')
        self.dex = self.base / 'bridge.dex'
        self.dex.write_bytes(DEX)
        self.original = self.base / 'original.apk'
        self.make_zip()
        self.pin = patch.object(x, 'ORIGINAL_SHA256', x.digest_file(self.original))
        self.pin.start()
        self.addCleanup(self.pin.stop)

    def make_zip(self, changes=None):
        files = {'AndroidManifest.xml': b'binary axml', 'classes.dex': b'one', 'classes2.dex': b'two', 'assets/YeelightColor4.xml': b'profile'}
        files.update(changes or {})
        with zipfile.ZipFile(self.original, 'w') as z:
            for key, value in files.items():
                z.writestr(key, value)

    def test_original_inventory_is_verified(self):
        result = x.inspect_original(self.original)
        self.assertEqual(result['dex_count'], 2)
        self.assertTrue(result['original_read_only'])

    def test_foreign_apk_rejected(self):
        with patch.object(x, 'ORIGINAL_SHA256', '0'*64):
            with self.assertRaisesRegex(x.GateFailure, 'SHA256_MISMATCH'):
                x.inspect_original(self.original)

    def test_zip_slip_rejected(self):
        self.make_zip({'../escaped': b'bad'})
        with patch.object(x, 'ORIGINAL_SHA256', x.digest_file(self.original)):
            with self.assertRaisesRegex(x.GateFailure, 'ZIP_ENTRY_PATH'):
                x.inspect_original(self.original)

    def test_duplicate_zip_rejected(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            self.make_zip({'classes.dex': b'changed'})
            with zipfile.ZipFile(self.original, 'a') as z:
                z.writestr('classes.dex', b'duplicate')
        with patch.object(x, 'ORIGINAL_SHA256', x.digest_file(self.original)):
            with self.assertRaisesRegex(x.GateFailure, 'DUPLICATE'):
                x.inspect_original(self.original)

    def test_manifest_preserves_original_component_code_and_renames_authorities(self):
        info = x.patch_manifest(self.decoded / 'AndroidManifest.xml')
        doc = ET.parse(self.decoded / 'AndroidManifest.xml').getroot()
        app = doc.find('application')
        self.assertEqual(doc.get('package'), x.NEW_PACKAGE)
        self.assertEqual(app.get(x.A('name')), 'com.yeelight.cherry.ClassicApp')
        self.assertEqual(app.get(x.A('appComponentFactory')), 'com.yeelight.cherry.Factories')
        self.assertEqual(app.find('activity').get(x.A('name')), 'com.yeelight.cherry.MainActivity')
        alias = app.find('activity-alias')
        self.assertEqual(alias.get(x.A('targetActivity')), 'com.yeelight.cherry.MainActivity')
        self.assertEqual(app.find('provider').get(x.A('authorities')),
            x.NEW_PACKAGE + '.device;' + x.NEW_PACKAGE + '.landevice')
        self.assertEqual(doc.find('permission').get(x.A('name')), x.NEW_PACKAGE + '.PERMISSION')
        self.assertEqual(app.find('provider').get(x.A('permission')), x.NEW_PACKAGE + '.PERMISSION')
        self.assertEqual(doc.find('uses-sdk').get(x.A('minSdkVersion')), '23')
        self.assertTrue(any(p.get(x.A('name')) == x.BRIDGE_ACTIVITY and p.get(x.A('exported')) == 'false' for p in app.findall('activity')))
        self.assertTrue(any(s.get(x.A('name')) == x.BRIDGE_SERVICE and s.get(x.A('exported')) == 'false' for s in app.findall('service')))
        self.assertTrue(any(p.get(x.A('name')) == 'android.permission.FOREGROUND_SERVICE_MICROPHONE' for p in doc.findall('uses-permission')))
        self.assertEqual(len(info['providers']), 1)
        self.assertEqual(info['firebase_auto_init'], 'DISABLED_FOR_PRIVATE_CLONE')
        self.assertEqual(len(app.findall('provider')), 1)
        self.assertFalse(any(p.get(x.A('name')) == 'com.google.firebase.provider.FirebaseInitProvider' for p in app.findall('provider')))

    def test_firebase_provider_missing_refused_without_mutation(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('    <provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="com.yeelight.cherry.firebaseinitprovider" android:exported="false"/>\n', ''))
        prior = p.read_bytes()
        with self.assertRaisesRegex(x.GateFailure, 'FIREBASE_INIT_PROVIDER_NOT_UNIQUE'):
            x.patch_manifest(p)
        self.assertEqual(p.read_bytes(), prior)

    def test_firebase_authority_mismatch_refused_without_mutation(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('com.yeelight.cherry.firebaseinitprovider', 'foreign.firebaseinitprovider'))
        prior = p.read_bytes()
        with self.assertRaisesRegex(x.GateFailure, 'FIREBASE_INIT_AUTHORITY_MISMATCH'):
            x.patch_manifest(p)
        self.assertEqual(p.read_bytes(), prior)

    def test_duplicate_firebase_provider_refused(self):
        p = self.decoded / 'AndroidManifest.xml'
        duplicate = '    <provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="com.yeelight.cherry.firebaseinitprovider" android:exported="false"/>\n'
        p.write_text(MANIFEST.replace(duplicate, duplicate + duplicate))
        with self.assertRaisesRegex(x.GateFailure, 'FIREBASE_INIT_PROVIDER_NOT_UNIQUE'):
            x.patch_manifest(p)

    def test_exported_firebase_provider_refused(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('com.yeelight.cherry.firebaseinitprovider" android:exported="false"',
                                      'com.yeelight.cherry.firebaseinitprovider" android:exported="true"'))
        with self.assertRaisesRegex(x.GateFailure, 'FIREBASE_INIT_UNEXPECTED_EXPORT'):
            x.patch_manifest(p)

    def test_unknown_authority_fails_closed(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('com.yeelight.cherry.device;com.yeelight.cherry.landevice', 'com.example.thirdparty'))
        with self.assertRaisesRegex(x.GateFailure, 'AUTHORITY_NOT_OWNED'):
            x.patch_manifest(p)
        self.assertIn('com.yeelight.cherry', p.read_text())

    def test_shared_user_id_fails_closed(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('package="com.yeelight.cherry"', 'package="com.yeelight.cherry" android:sharedUserId="com.yeelight.cherry"'))
        with self.assertRaisesRegex(x.GateFailure, 'SHARED_USER_ID'):
            x.patch_manifest(p)

    def test_layout_preserves_original_root_and_child_indices(self):
        p = self.decoded / 'res/layout/activity_main_new.xml'
        before = ET.parse(p).getroot()
        original_attributes = dict(before.attrib)
        original_children = [ET.tostring(child) for child in before]
        x.patch_layout(p)
        root = ET.parse(p).getroot()
        self.assertEqual(root.tag, 'LinearLayout')
        self.assertEqual(root.attrib, original_attributes)
        self.assertEqual([ET.tostring(child) for child in root[:-1]], original_children)
        self.assertEqual(root[0].get(x.A('text')), 'Original Classic')
        self.assertEqual(root[-1].tag, x.BRIDGE_BUTTON)
        self.assertEqual(root[-1].get(x.A('layout_gravity')), 'end')
        self.assertEqual(root[-1].get(x.A('contentDescription')), 'Loft DJ öffnen')
        with self.assertRaisesRegex(x.GateFailure, 'LAYOUT_ALREADY_PATCHED'):
            x.patch_layout(p)

    def test_layout_rejects_unverified_root_without_modifying_it(self):
        p = self.decoded / 'res/layout/activity_main_new.xml'
        wrong = p.read_text().replace('<LinearLayout ', '<FrameLayout ').replace(
            '</LinearLayout>', '</FrameLayout>')
        p.write_text(wrong)
        original_bytes = p.read_bytes()
        with self.assertRaisesRegex(x.GateFailure, 'CLASSIC_LAYOUT_ROOT_CHANGED_FAIL_CLOSED'):
            x.patch_layout(p)
        self.assertEqual(p.read_bytes(), original_bytes)

    def test_patch_is_atomic_and_does_not_touch_source(self):
        baseline = x.digest_file(self.decoded / 'AndroidManifest.xml')
        lbase = x.digest_file(self.decoded / 'res/layout/activity_main_new.xml')
        out = self.base / 'out'
        result = x.prepare(self.original, self.decoded, self.dex, out)
        self.assertEqual(result['status'], 'DECODED_CLONE_PREPARED_NOT_COMPILED_OR_HARDWARE_TESTED')
        self.assertTrue((out / 'classes3.dex').exists())
        self.assertEqual(x.digest_file(self.decoded / 'AndroidManifest.xml'), baseline)
        self.assertEqual(x.digest_file(self.decoded / 'res/layout/activity_main_new.xml'), lbase)
        self.assertFalse((out / 'signed.apk').exists())
        self.assertEqual(result['device_commands'], 0)
        with self.assertRaisesRegex(x.GateFailure, 'OUTPUT_ALREADY_EXISTS'):
            x.prepare(self.original, self.decoded, self.dex, out)

    def test_tampered_decoded_original_dex_is_rejected(self):
        (self.decoded/'classes2.dex').write_bytes(b'tampered')
        with self.assertRaisesRegex(x.GateFailure, 'DECODED_ORIGINAL_BYTES_MISMATCH'):
            x.prepare(self.original, self.decoded, self.dex, self.base/'out')
        self.assertFalse((self.base/'out').exists())

    def test_tampered_decoded_original_device_profile_is_rejected(self):
        (self.decoded/'assets/YeelightColor4.xml').write_bytes(b'tampered-profile')
        with self.assertRaisesRegex(x.GateFailure, 'DECODED_ORIGINAL_BYTES_MISMATCH'):
            x.prepare(self.original, self.decoded, self.dex, self.base/'out')

    def test_missing_dex_class_fails_without_output(self):
        self.dex.write_bytes(b'dex\n039\0' + b'0'*200)
        out = self.base / 'out'
        with self.assertRaisesRegex(x.GateFailure, 'MISSING_REQUIRED_CLASS'):
            x.prepare(self.original, self.decoded, self.dex, out)
        self.assertFalse(out.exists())

    def test_missing_layout_fails_without_output(self):
        (self.decoded / 'res/layout/activity_main_new.xml').unlink()
        out = self.base / 'out'
        with self.assertRaisesRegex(x.GateFailure, 'MAIN_NAVIGATION_LAYOUT_NOT_FOUND'):
            x.prepare(self.original, self.decoded, self.dex, out)
        self.assertFalse(out.exists())

    def test_foreign_decoded_package_rejected(self):
        p = self.decoded / 'AndroidManifest.xml'
        p.write_text(MANIFEST.replace('package="com.yeelight.cherry"', 'package="example.unrelated"'))
        with self.assertRaisesRegex(x.GateFailure, 'DECODED_PACKAGE_NOT_ORIGINAL'):
            x.prepare(self.original, self.decoded, self.dex, self.base / 'out')

    def test_symlinked_decoded_file_rejected(self):
        (self.decoded / 'link').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(x.GateFailure, 'SYMLINK'):
            x.prepare(self.original, self.decoded, self.dex, self.base / 'out')

    def test_notification_reopen_and_stop_components_in_original_source(self):
        java = (Path(__file__).resolve().parent.parent / 'bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java').read_text()
        self.assertIn('PendingIntent.getActivity', java)
        self.assertIn('PendingIntent.getService', java)
        self.assertIn('FLAG_IMMUTABLE', java)
        self.assertIn('FLAG_ACTIVITY_CLEAR_TOP', java)
        self.assertIn('void finishSession()', java)
        self.assertIn('START_NOT_STICKY', java)
        self.assertNotIn('set_rgb(', java)
        self.assertNotIn('set_music(', java)

if __name__ == '__main__':
    unittest.main()
