"""Synthetic identity-preserving protected A/B control. No OEM binaries, SDK or ADB."""
import hashlib
import importlib.util
from pathlib import Path
from unittest.mock import patch
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
sys.path.insert(0,str(ROOT/"private_ci"))
import classic_first as c
import build_private_unsigned as b
import private_test_sign as sign
import private_identity_control as ident

ORIGINAL="com.yeelight.cherry"
MANIFEST='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yeelight.cherry">
  <uses-sdk android:minSdkVersion="19"/>
  <permission android:name="com.yeelight.cherry.PERM"/>
  <uses-permission android:name="com.yeelight.cherry.PERM"/>
  <application android:name=".ClassicApp">
    <activity android:name=".MainActivity" android:exported="true"/>
    <provider android:name=".Provider" android:authorities="com.yeelight.cherry.device" android:exported="false"/>
    <provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="com.yeelight.cherry.firebaseinitprovider" android:exported="false"/>
    <provider android:name="com.yeelight.yeelib.data.DeviceInteractionProvider" android:authorities="com.yeelight.cherry.database.interaction" android:exported="false"/>
  </application>
</manifest>'''

class FakeAapt:
    returncode=0
    stdout="package: name='com.yeelight.cherry' versionCode='35402'\n"

class IdentityAblationTests(unittest.TestCase):
    def test_real_manifest_ablation_preserves_original_package_and_authorities(self):
        with tempfile.TemporaryDirectory() as temp:
            f=Path(temp)/"AndroidManifest.xml"
            f.write_text(MANIFEST,encoding="utf-8")
            result=c.patch_manifest(f,identity_control=True)
            root=ET.parse(f).getroot()
            app=root.find("application")
            self.assertEqual(root.get("package"),ORIGINAL)
            self.assertEqual(result["new_package"],ORIGINAL)
            self.assertTrue(result["identity_control_offline_only"])
            self.assertEqual(app.get(c.A("name")),ORIGINAL+".ClassicApp")
            self.assertEqual(app.find("provider").get(c.A("authorities")),
                             ORIGINAL+".device")
            self.assertEqual(len(app.findall("provider")),1)
            self.assertEqual(root.find("permission").get(c.A("name")),
                             ORIGINAL+".PERM")
            self.assertEqual(app.find("provider").get(c.A("exported")),"false")
            self.assertTrue(any(a.get(c.A("name"))==c.BRIDGE_ACTIVITY
                                for a in app.findall("activity")))
            self.assertTrue(any(a.get(c.A("name"))==c.BRIDGE_SERVICE
                                for a in app.findall("service")))
            self.assertFalse(any(a.get(c.A("name"))==
                "com.google.firebase.provider.FirebaseInitProvider"
                for a in app.findall("provider")))
            self.assertFalse(any(a.get(c.A("name"))==
                "com.yeelight.yeelib.data.DeviceInteractionProvider"
                for a in app.findall("provider")))
            self.assertEqual(root.find("uses-sdk").get(c.A("minSdkVersion")),"23")
            # A second preparation is rejected because the two original
            # unsafe early-init providers are already removed, even when the
            # package stays original (do not assert package-name mismatch).
            with self.assertRaisesRegex(c.GateFailure,
                                        "FIREBASE_INIT_PROVIDER_NOT_UNIQUE"):
                c.patch_manifest(f,identity_control=True)

    def test_default_manifest_remains_isolated_clone(self):
        with tempfile.TemporaryDirectory() as temp:
            f=Path(temp)/"AndroidManifest.xml"
            f.write_text(MANIFEST,encoding="utf-8")
            c.patch_manifest(f)
            root=ET.parse(f).getroot()
            self.assertEqual(root.get("package"),c.NEW_PACKAGE)
            self.assertEqual(root.find("application").find("provider")
                             .get(c.A("authorities")),c.NEW_PACKAGE+".device")

    def test_identity_binary_requires_original_oem_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            original=root/"original.apk"
            output=root/"identity.apk"
            sidecar=root/"sidecar.dex"
            sidecar.write_bytes(b"identity-test-sidecar")
            contents={"AndroidManifest.xml":b"synthetic manifest",
                      "classes.dex":b"oem0",
                      "classes2.dex":b"oem1",
                      "classes3.dex":sidecar.read_bytes()}
            with zipfile.ZipFile(original,"w") as z:
                for name in ("AndroidManifest.xml","classes.dex","classes2.dex"):
                    z.writestr(name,contents[name])
            with zipfile.ZipFile(output,"w") as z:
                for name,data in contents.items():
                    z.writestr(name,data)
            with patch.object(b.subprocess,"run",return_value=FakeAapt()):
                report=b.verify_unsigned(output,original,sidecar,"fake-aapt",
                                         dex_mode="raw",identity_control=True)
                self.assertEqual(report["package"],ORIGINAL)
                self.assertTrue(report["identity_control_offline_only"])
                self.assertEqual(report["controlled_authority_string_remaps"],
                                 {"classes.dex":0,"classes2.dex":0})
                with zipfile.ZipFile(output,"w") as z:
                    z.writestr("AndroidManifest.xml",b"synthetic manifest")
                    z.writestr("classes.dex",b"MUTATED!")
                    z.writestr("classes2.dex",b"oem1")
                    z.writestr("classes3.dex",sidecar.read_bytes())
                with self.assertRaisesRegex(c.GateFailure,
                                            "OEM_DEX_TRANSFORM_MISMATCH"):
                    b.verify_unsigned(output,original,sidecar,"fake-aapt",
                                      dex_mode="raw",identity_control=True)
                with self.assertRaisesRegex(c.GateFailure,
                                            "IDENTITY_CONTROL_MUST_PRESERVE_OEM_DEX"):
                    b.verify_unsigned(output,original,sidecar,"fake-aapt",
                                      dex_mode="smali",identity_control=True)

    def test_offline_owner_scope_refuses_outside_emulator(self):
        with tempfile.TemporaryDirectory() as temp:
            tmp=Path(temp)
            with self.assertRaisesRegex(c.GateFailure,
                                        "IDENTITY_ABLATION_OWNER_OFFLINE_SCOPE_REQUIRED"):
                with patch.dict("os.environ",{"RUNNER_TEMP":str(tmp)},clear=True):
                    b.build(tmp/"x.apk",tmp/"private-output"/
                            "classic-loft-dj-IDENTITY-UNSIGNED.apk",
                            tmp,identity_control=True)
            with self.assertRaisesRegex(sign.Refused,
                                        "IDENTITY_ABLATION_OWNER_OFFLINE_SCOPE_REQUIRED"):
                with patch.dict("os.environ",{"RUNNER_TEMP":str(tmp)},clear=True):
                    sign.sign(tmp/"private-output"/"classic-loft-dj-IDENTITY-UNSIGNED.apk",
                              tmp/"x.json",
                              tmp/"private-output"/"classic-loft-dj-IDENTITY-TEST-SIGNED.apk",
                              tmp,identity_control=True)
            with self.assertRaisesRegex(ident.BaselineBlocked,
                                        "IDENTITY_CONTROL_OWNER_OFFLINE_SCOPE_REQUIRED"):
                ident.scope(tmp/"original.apk",tmp/"signed.apk",
                            serial="emulator-5554",approved=True,env={})

    def test_identity_classifier_never_claims_acceptance(self):
        null={"target_crash":True,"unattributed_fatal":False,
              "exception_category":"NULL_DEREFERENCE"}
        clean={"target_crash":False,"unattributed_fatal":False,
               "exception_category":"NONE"}
        launch="Status: ok\nActivity: com.yeelight.cherry/com.yeelight.cherry.Main\nComplete\n"
        with patch.object(ident,"original_foreground",return_value=True):
            good=ident.evaluate_launch(0,launch,"dummy",clean)
            bad=ident.evaluate_launch(0,launch,"dummy",null)
        self.assertEqual(good["observed_start_state"],"RESUMED_NO_TARGET_FATAL")
        self.assertEqual(bad["observed_start_state"],"NULL_DEREFERENCE")
        for result in (good,bad):
            self.assertFalse(result["classic_functionally_accepted"])
            self.assertFalse(result["native_loft_dj_functionally_accepted"])
            self.assertFalse(result["raw_private_payload_published"])

    def test_protected_script_orders_control_after_original_and_never_passes(self):
        shell=(ROOT/"private_ci/run_android17_emulator.sh").read_text("utf-8")
        self.assertLess(shell.index("android17_start_smoke.py"),
                        shell.index("private_original_baseline.py"))
        self.assertLess(shell.index("private_original_baseline.py"),
                        shell.index("private_identity_control.py"))
        self.assertIn("--identity-control",shell)
        self.assertIn('CLASSIC_APKTOOL_JAR="$RUNNER_TEMP/classic-tools/',shell)
        self.assertIn('  fail\nfi\n',shell)
        self.assertNotIn("upload-artifact",shell)
        self.assertNotIn("gh release",shell)
        self.assertNotIn("adb -d ",shell)

if __name__=="__main__":
    unittest.main()
