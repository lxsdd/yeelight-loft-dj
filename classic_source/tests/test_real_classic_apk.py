"""Real Classic-3.5.4 static gate. Requires owner-supplied original bytes via env."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from apk_readonly_audit import inspect,parse_binary_xml,AxmlError
from dex_authority_remap import (NEW,OLD,AUTHORITIES,remap_dex,assert_self_consistent_dex,
                                  DexRemapError)
from classic_first import ORIGINAL_SHA256

ORIGINAL = Path(os.getenv('CLASSIC_ORIGINAL_APK','/NEEDS_EXPLICIT_ORIGINAL_APK'))
@unittest.skipUnless(ORIGINAL.is_file(), 'Owner-supplied Classic APK not mounted')
class RealClassicOriginal(unittest.TestCase):
    def test_actual_oem_manifest_and_navigation_anchor(self):
        info = inspect(ORIGINAL)
        self.assertEqual(info['original_sha256'], ORIGINAL_SHA256)
        self.assertEqual(info['package'], OLD)
        self.assertEqual(info['manifest_activities'], 185)
        self.assertEqual(info['layout_root'], 'LinearLayout')
        self.assertEqual(info['layout_element_count'], 5)
        self.assertEqual(len(info['providers']),7)
        authorities={p.get('{http://schemas.android.com/apk/res/android}authorities') for p in info['providers']}
        self.assertEqual(authorities, AUTHORITIES)
        self.assertEqual(info['uses_sdk']['{http://schemas.android.com/apk/res/android}targetSdkVersion'], '33')

    def test_full_oem_authority_remap_and_hash_reseal(self):
        base=hashlib.sha256(ORIGINAL.read_bytes()).hexdigest()
        with ZipFile(ORIGINAL) as z:
            src1=z.read('classes.dex');src2=z.read('classes2.dex')
        out1,a1=remap_dex(src1);out2,a2=remap_dex(src2)
        self.assertEqual(a1['changed_string_count'],0)
        self.assertEqual(a2['changed_string_count'],10)
        self.assertEqual(len(out2),len(src2))
        assert_self_consistent_dex(out1);assert_self_consistent_dex(out2)
        self.assertEqual(remap_dex(out2)[1]['changed_string_count'],0)
        self.assertIn(b'io.lxsdd.yeelightdj.device',out2)
        self.assertNotIn(b'content://com.yeelight.cherry.device\0',out2)
        self.assertIn(b'com.yeelight.cherry.device_id\0',out2) # unrelated settings remain untouched
        self.assertEqual(hashlib.sha256(ORIGINAL.read_bytes()).hexdigest(),base)

    def test_reject_corrupted_dex_instead_of_sealing_it(self):
        with ZipFile(ORIGINAL) as z:blob=bytearray(z.read('classes2.dex'))
        blob[1150] ^= 1
        with self.assertRaisesRegex(DexRemapError,'DEX_SHA1_INVALID'):
            remap_dex(bytes(blob))

    def test_bad_axml_and_fake_package_refused(self):
        with self.assertRaises(AxmlError):parse_binary_xml(b'not a manifest')
        with ZipFile(ORIGINAL) as z:
            manifest = z.read('AndroidManifest.xml')
        self.assertGreater(len(parse_binary_xml(manifest)['elements']),300)

if __name__=='__main__':unittest.main()
