from pathlib import Path
import json
import os
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from toolchain_upgrade.classic_vendor_audit import PinnedOriginalFailure,audit,sha256_file,ORIGINAL_SHA256
class ClassicVendorAuditTests(unittest.TestCase):
 def test_original_hash_pinned(self):
  original=Path(os.getenv('CLASSIC_ORIGINAL_APK','/NEEDS_EXPLICIT_ORIGINAL_APK'))
  if not original.is_file(): self.skipTest('Owner APK not mounted')
  self.assertEqual(sha256_file(original),ORIGINAL_SHA256)
 def test_read_only_class_inventory(self):
  original=Path(os.getenv('CLASSIC_ORIGINAL_APK','/NEEDS_EXPLICIT_ORIGINAL_APK'))
  if not original.is_file(): self.skipTest('Owner APK not mounted')
  before=sha256_file(original)
  data=audit(original)
  self.assertEqual(data['original_sha256'], before)
  self.assertEqual(data['status'],'READ_ONLY_VENDOR_COMPONENT_INVENTORY_NOT_RUNTIME_VERIFIED')
  self.assertGreater(data['family_class_counts']['kotlin_stdlib'],0)
  self.assertEqual(data['component_versions'],'NOT_VERIFIED_BY_CLASS_PRESENCE')
  self.assertEqual(sha256_file(original),before)
 def test_mismatched_sha_cannot_parse(self):
  with tempfile.TemporaryDirectory() as td:
   bad=Path(td)/'wrong.apk'
   with zipfile.ZipFile(bad,'w') as z:z.writestr('classes.dex',b'dex\n035\x00')
   with self.assertRaisesRegex(PinnedOriginalFailure,'ORIGINAL_APK_SHA_MISMATCH'):
    audit(bad)
if __name__=='__main__':unittest.main()
