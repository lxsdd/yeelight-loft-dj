"""Updated Apktool source pin: fail-closed, never silently call unpinned jar."""
from pathlib import Path
import json
import unittest
from unittest.mock import patch
class ApktoolPinTests(unittest.TestCase):
 def test_private_fullbuild_uses_pinned_303_jar(self):
  root=Path(__file__).resolve().parents[1]
  expected='dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'
  self.assertIn(expected,(root/'build_private_unsigned.py').read_text())
  self.assertIn(expected,(root/'private_ci/build_with_loft_toolchain.sh').read_text())
  self.assertEqual(json.loads((root/'toolchain_versions.json').read_text())['proposed']['apktool'],'3.0.3')
 def test_public_oem_upload_still_forbidden(self):
  s=(Path(__file__).resolve().parents[1]/'private_ci/build_with_loft_toolchain.sh').read_text()
  self.assertIn('BLOCKED_PUBLIC_WORKFLOW',s)
  self.assertIn('BLOCKED_UNVERIFIED_CI_PRIVACY',s)
if __name__=='__main__':unittest.main()

# Regression: private full build must not accept arbitrary toolchain from PATH.
class ApktoolFailClosedTests(unittest.TestCase):
 def test_full_build_blocks_unpinned_apktool_fallback(self):
  from build_private_unsigned import build
  from classic_first import GateFailure
  root=Path(__file__).resolve().parents[1]
  code=(root/'build_private_unsigned.py').read_text()
  self.assertIn("PINNED_APKTOOL_3_0_3_JAR_REQUIRED",code)
  self.assertNotIn("must_be_tool('apktool')",code)
