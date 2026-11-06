"""Source-only CI compatibility guards, no runner secrets or device contact."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from bridge.build_bridge import find_sdk_tools, filter_runtime_jars, dex_descriptors, ToolError

class LoftCiReuseTests(unittest.TestCase):
    def test_detects_loft_ci_sdk_36_toolchain(self):
        with tempfile.TemporaryDirectory() as td:
            sdk=Path(td)
            for name in ('platforms/android-36/android.jar','build-tools/36.0.0/d8','build-tools/36.0.0/aapt'):
                p=sdk/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'x')
            api,d8,aapt=find_sdk_tools(sdk)
            self.assertEqual(api.name,'android.jar')
            self.assertIn('36.0.0',str(d8))
            self.assertEqual(aapt.name,'aapt')

    def test_unconfigured_sdk_does_not_modify_target(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaisesRegex(ToolError, 'ANDROID_SDK_MISSING'):
                find_sdk_tools(Path(td))

    def test_filter_needs_pinned_oem_source(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaisesRegex(ToolError,'ORIGINAL_REQUIRED'):
                filter_runtime_jars([],None,Path(td)/'new')
            self.assertFalse((Path(td)/'new').exists())

    def test_filter_drops_already_present_kotlin_class_but_not_other_classes(self):
        original = os.environ.get('CLASSIC_ORIGINAL_APK')
        if not original or not Path(original).is_file(): self.skipTest('Original owner APK not mounted')
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            path=root/'kotlin-stdlib-test.jar'
            with zipfile.ZipFile(path,'w') as z:
                z.writestr('kotlin/Pair.class',b'old-oem-owned')
                z.writestr('kotlin/madeup/BridgeExtra.class',b'own-extra')
            result,detail=filter_runtime_jars([path],Path(original),root/'out')
            self.assertEqual(detail['filtered_duplicate_kotlin_classes'],1)
            with zipfile.ZipFile(result[0]) as z:
                self.assertNotIn('kotlin/Pair.class',z.namelist())
                self.assertIn('kotlin/madeup/BridgeExtra.class',z.namelist())
            with zipfile.ZipFile(path) as z:
                self.assertIn('kotlin/Pair.class',z.namelist())

    def test_own_bridge_collision_is_never_silently_removed(self):
        original = os.environ.get('CLASSIC_ORIGINAL_APK')
        if not original or not Path(original).is_file(): self.skipTest('Original owner APK not mounted')
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            path=root/'bridge.jar'
            with zipfile.ZipFile(path,'w') as z:z.writestr('kotlin/Pair.class',b'overlap')
            with self.assertRaisesRegex(ToolError,'BRIDGE_OWN_CLASS_DUPLICATES_OEM'):
                filter_runtime_jars([path],Path(original),root/'out')

    def test_gradle_bridge_reuses_loft_plugin_generation_and_contains_no_oem(self):
        root=Path(__file__).resolve().parent.parent
        build=(root/'private_gradle_bridge/build.gradle.kts').read_text()
        self.assertIn('2.4.21',build)
        self.assertIn('android-36/android.jar',build)
        self.assertNotIn('com.yeelight.cherry',build)
        self.assertNotIn('signingConfigs',build)
        self.assertIn('stageBridgeInputs',build)

if __name__=='__main__':unittest.main()
