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
            self.assertEqual(len(app.findall("provider")),2)
            self.assertTrue(result["identity_native_entry_provider"])
            bootstrap = [p for p in app.findall("provider") if
                         p.get(c.A("name")) ==
                         "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjEntryProvider"]
            self.assertEqual(len(bootstrap),1)
            self.assertEqual(bootstrap[0].get(c.A("authorities")),
                             ORIGINAL+".loftdj.entry")
            self.assertEqual(bootstrap[0].get(c.A("exported")),"false")
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
            self.assertFalse(any(p.get(c.A("name")) ==
                "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjEntryProvider"
                for p in root.find("application").findall("provider")))
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

    def test_identity_dj_foreground_requires_exact_oem_package_and_nonexported_activity(self):
        good=("topResumedActivity=ActivityRecord{ab u0 com.yeelight.cherry/"
              "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity t7}")
        self.assertTrue(ident.identity_dj_resumed(good))
        self.assertTrue(ident.identity_dj_resumed("  "+good+"\nResumedActivity="+good.split("=",1)[1]))
        self.assertFalse(ident.identity_dj_resumed(good.replace(
            "com.yeelight.cherry/", "io.lxsdd.yeelightdj/")))
        self.assertFalse(ident.identity_dj_resumed(good.replace(
            "ClassicDjActivity", "OtherActivity")))
        self.assertFalse(ident.identity_dj_resumed(good+"\nResumedActivity=ActivityRecord{ab u0 "
            "com.yeelight.cherry/com.yeelight.cherry.Other t7}"))
        self.assertFalse(ident.identity_dj_resumed("mResumedActivity="+good.split("=",1)[1]))

    def test_identity_navigation_requires_visible_native_controls_and_no_fatal(self):
        top=("topResumedActivity=ActivityRecord{ab u0 com.yeelight.cherry/"
             "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity t7}")
        xml=('<?xml version="1.0" encoding="UTF-8"?>'
             '<hierarchy rotation="0">'
             '<node text="Loft DJ · Yeelight Classic" clickable="false"/>'
             '<node text="Mikrofon starten" clickable="true"/>'
             '<node text="DJ stoppen" clickable="true"/>'
             '</hierarchy>')
        clean={"target_crash":False,"unattributed_fatal":False,"exception_category":"NONE"}
        null={"target_crash":True,"unattributed_fatal":False,
              "exception_category":"NULL_DEREFERENCE"}
        good=ident.evaluate_identity_navigation(top,clean,xml)
        self.assertEqual(good["native_dj_observed_state"],"DJ_NATIVE_CONTROLS_VISIBLE")
        self.assertTrue(good["native_dj_navigation_observed"])
        self.assertTrue(good["native_entry_navigation_tested"])
        self.assertFalse(good["native_loft_dj_functionally_accepted"])
        self.assertFalse(good["classic_functionally_accepted"])
        self.assertFalse(good["raw_private_payload_published"])
        cases=[
            (top,null,xml,"NULL_DEREFERENCE"),
            (top,clean,xml.replace("Mikrofon starten","Wrong"),"DJ_UI_NOT_VISIBLE"),
            (top,clean,"<malformed","DJ_UI_NOT_VISIBLE"),
            (top.replace("ClassicDjActivity","Other"),clean,xml,"DJ_NOT_RESUMED"),
        ]
        for snapshot,crash,ui,expected in cases:
            with self.subTest(expected=expected,ui=ui):
                result=ident.evaluate_identity_navigation(snapshot,crash,ui)
                self.assertEqual(result["native_dj_observed_state"],expected)
                self.assertFalse(result["native_dj_navigation_observed"])
                self.assertFalse(result["native_loft_dj_functionally_accepted"])

    def test_identity_provider_markers_allowlisted_never_replace_ui_acceptance(self):
        source = ("11-14 18:00:00.000 I ClassicDjProbe: ENTRY_PROVIDER_REGISTERED\n"
                  "11-14 18:00:00.010 I ClassicDjProbe: ENTRY_ACTIVITY_RESUMED\n"
                  "11-14 18:00:00.020 I ClassicDjProbe: ENTRY_ADDED_TO_ACTIVITY\n"
                  "11-14 18:00:00.030 I ClassicDjProbe: secret-private-class\n")
        observation = ident.classify_private_entry_markers(source)
        self.assertTrue(observation["provider_registered"])
        self.assertTrue(observation["activity_resumed"])
        self.assertTrue(observation["entry_added_to_activity"])
        self.assertFalse(observation["ui_visibility_proven"])
        self.assertFalse(observation["raw_logcat_published"])
        with_laid_out = ident.classify_private_entry_markers(
            "11-14 18:00:00 I ClassicDjProbe: ENTRY_VIEW_LAID_OUT")
        self.assertTrue(with_laid_out["entry_view_laid_out"])
        self.assertFalse(with_laid_out["ui_visibility_proven"])
        focus = ident.classify_private_entry_markers(
            "11-14 18:00:00 I ClassicDjProbe: ENTRY_HOST_FOCUSED\n"
            "11-14 18:00:00 I ClassicDjProbe: ENTRY_HOST_UNFOCUSED")
        self.assertTrue(focus["entry_host_focused"])
        self.assertTrue(focus["entry_host_unfocused"])
        self.assertFalse(focus["ui_visibility_proven"])
        self.assertNotIn("secret-private-class", str(observation))
        self.assertEqual(
            ident.classify_private_entry_markers("no provider markers")["status"],
            "PRIVATE_ENTRY_MARKERS_ABSENT_NOT_ACCEPTED")
        with self.assertRaisesRegex(ident.BaselineBlocked, "IDENTITY_ENTRY_LOG_INVALID"):
            ident.classify_private_entry_markers(None)



    def test_original_identity_ui_surface_discriminates_system_cover_and_app(self):
        owner = "com.yeelight.cherry"
        normal = ('<?xml version="1.0"?><hierarchy>'
                  '<node package="' + owner + '" text="private device" '
                  'resource-id="private.account.value">'
                  '<node package="' + owner + '" text="Loft DJ" '
                  'content-desc="Loft DJ öffnen" clickable="true" '
                  'enabled="true"/></node></hierarchy>')
        data = ident.classify_private_ui_surface(normal)
        self.assertEqual(data["node_count"], 2)
        self.assertEqual(data["original_package_node_count"], 2)
        self.assertEqual(data["top_level_original_node_count"], 1)
        self.assertEqual(data["dj_text_node_count"], 1)
        self.assertEqual(data["dj_content_description_node_count"], 1)
        self.assertEqual(data["dj_candidate_clickable_count"], 1)
        self.assertEqual(data["dj_candidate_enabled_count"], 1)
        self.assertFalse(data["ui_navigation_proven"])
        self.assertFalse(data["raw_private_xml_or_names_published"])
        self.assertNotIn("private device", str(data))
        self.assertNotIn("private.account.value", str(data))
        system = ('<?xml version="1.0"?><hierarchy>'
                  '<node package="android" text="private popup"/>'
                  '</hierarchy>')
        status = ident.classify_private_ui_surface(system)
        self.assertEqual(status["original_package_node_count"], 0)
        self.assertEqual(status["android_package_node_count"], 1)
        self.assertEqual(status["dj_candidate_clickable_count"], 0)
        hidden = ('<?xml version="1.0"?><hierarchy><node package="' + owner +
                  '" text="Private login"/></hierarchy>')
        hidden_status = ident.classify_private_ui_surface(hidden)
        self.assertEqual(hidden_status["original_package_node_count"], 1)
        self.assertEqual(hidden_status["dj_text_node_count"], 0)

    def test_original_identity_surface_parser_is_bounded_and_fail_closed(self):
        for bad in (None, "", "<no-ui/>", '<!DOCTYPE root><hierarchy/>',
                    '<?xml version="1.0"?><hierarchy><node/>'):
            with self.subTest(bad=bad), self.assertRaises(ident.BaselineBlocked):
                ident.classify_private_ui_surface(bad)
        with self.assertRaisesRegex(ident.BaselineBlocked, "IDENTITY_SURFACE_NODE_COUNT_INVALID"):
            ident.classify_private_ui_surface(
                '<?xml version="1.0"?><hierarchy>' +
                '<node package="android"/>' * 6001 + '</hierarchy>')


    def test_exact_aosp_deprecated_target_dialog_matches_one_visible_ok(self):
        xml = ('<?xml version="1.0"?><hierarchy>'
               '<node package="android" text="Classic warning">'
               '<node package="android" text="This app was built for an older '
               "version of Android. It might not work properly and doesn't "
               'include the latest security and privacy protections."/>'
               '<node package="android" resource-id="android:id/button1" '
               'text="OK" clickable="true" enabled="true" '
               'bounds="[150,1750][400,1850]"/>'
               '</node></hierarchy>')
        focused = ("mCurrentFocus=Window{6a u0 "
                   "DeprecatedTargetSdkVersionDialog}")
        screen = "Physical size: 1080x2400"
        coords = ident.legacy_target_warning_ok_point(xml, focused, screen)
        self.assertEqual(coords, (275, 1800))
        # Expected AOSP UI dialog fingerprint only, not general OK taps.
        for invalid_xml in (
            xml.replace("security and privacy", "user interface"),
            xml.replace("android:id/button1", "android:id/button2"),
            xml.replace('text="OK"', 'text="Allow"'),
            xml.replace('enabled="true"', 'enabled="false"'),
            xml.replace('package="android"', 'package="com.yeelight.cherry"', 1),
            xml.replace('bounds="[150,1750][400,1850]"',
                        'bounds="[0,0][9999,9999]"'),
            xml.replace("</hierarchy>", '<!DOCTYPE evil></hierarchy>'),
        ):
            with self.subTest(invalid_xml=invalid_xml[-60:]):
                self.assertIsNone(ident.legacy_target_warning_ok_point(
                    invalid_xml, focused, screen))
        self.assertIsNone(ident.legacy_target_warning_ok_point(
            xml, "mCurrentFocus=Window{6a u0 PermissionDialog}", screen))
        self.assertIsNone(ident.legacy_target_warning_ok_point(
            xml, "mCurrentFocus=null", screen))
        self.assertIsNone(ident.legacy_target_warning_ok_point(
            xml, focused, "Physical size: 0x0"))
        self.assertIsNone(ident.legacy_target_warning_ok_point(
            None, focused, screen))

    def test_legacy_warning_scope_is_owner_only_and_single_tap_not_global(self):
        source = (ROOT / "private_ci/private_identity_control.py").read_text(
            "utf-8")
        self.assertIn('if android_only:', source)
        self.assertIn('warning_point = legacy_target_warning_ok_point(', source)
        self.assertIn('if warning_point is not None:', source)
        self.assertIn('offline_guard()\\n                    adb(["shell", "input", "tap"', source)
        self.assertIn('pause(1)', source)
        self.assertIn('permission_granted": False', source)
        self.assertNotIn('settings put global hide_error_dialogs', source)
        self.assertNotIn('debug.wm.disable_deprecated_target_sdk_dialog', source)
        self.assertNotIn('pm grant ', source)
        self.assertNotIn('input keyevent 66', source)
        self.assertNotIn('input keyevent 4', source)

    def test_private_identity_file_capture_is_read_only_and_cleaned(self):
        actions = []
        raw = '<?xml version="1.0"?><hierarchy><node text="private"/></hierarchy>'
        def fake(args, *, timeout, allow_failure):
            actions.append(tuple(args))
            if args[:2] == ["shell", "cat"]:
                return 0, raw
            return 0, ""
        output, status = ident.capture_private_hierarchy(fake)
        self.assertEqual(output, raw)
        self.assertEqual(status, "UI_DUMP_FILE_OBSERVED_NOT_ACCEPTED")
        path = ident.PRIVATE_UI_FILE
        self.assertEqual(actions, [
            ("shell", "rm", "-f", path),
            ("shell", "uiautomator", "dump", path),
            ("shell", "cat", path),
            ("shell", "rm", "-f", path)])
        self.assertNotIn("private", status)

    def test_missing_identity_ui_data_is_distinct_from_missing_button(self):
        calls = []
        def no_data(args, *, timeout, allow_failure):
            calls.append(tuple(args))
            if args[:3] == ["shell", "uiautomator", "dump"]:
                return 2, "private not printed"
            return 0, ""
        self.assertEqual(ident.capture_private_hierarchy(no_data),
                         (None, "UIAUTOMATOR_DUMP_FAILED"))
        self.assertEqual(calls[-1], ("shell", "rm", "-f", ident.PRIVATE_UI_FILE))
        def no_xml(args, *, timeout, allow_failure):
            if args[:2] == ["shell", "cat"]:
                return 0, "not an XML hierarchy"
            return 0, ""
        self.assertEqual(ident.capture_private_hierarchy(no_xml),
                         (None, "UI_DUMP_HIERARCHY_MISSING"))

    def test_ui_capture_cleanup_denial_is_not_reported_as_success(self):
        calls = []
        def cleanup_fails(args, *, timeout, allow_failure):
            calls.append(tuple(args))
            if args[:3] == ["shell", "rm", "-f"]:
                return (0 if len(calls) == 1 else 2), ""
            return 0, ""
        with self.assertRaisesRegex(ident.BaselineBlocked,
                                    "IDENTITY_UI_TEMP_CLEANUP_FAILED"):
            ident.capture_private_hierarchy(cleanup_fails)
        self.assertEqual(calls[-1],
                         ("shell", "rm", "-f", ident.PRIVATE_UI_FILE))

    def test_identity_dj_ui_option_is_boolean_only(self):
        with self.assertRaisesRegex(ident.BaselineBlocked,"IDENTITY_CONTROL_BAD_UI_MODE"):
            ident.probe(Path("/untrusted"),Path("/untrusted-signed"),
                        serial="emulator-5554",approved=True,probe_native_ui="true")

    def test_protected_script_orders_control_after_original_and_never_passes(self):
        shell=(ROOT/"private_ci/run_android17_emulator.sh").read_text("utf-8")
        self.assertLess(shell.index("android17_start_smoke.py"),
                        shell.index("private_original_baseline.py"))
        self.assertLess(shell.index("private_original_baseline.py"),
                        shell.index("private_identity_control.py"))
        self.assertIn("--probe-native-ui",shell)
        self.assertIn("--identity-control",shell)
        self.assertIn('CLASSIC_APKTOOL_JAR="$RUNNER_TEMP/classic-tools/',shell)
        self.assertIn('  fail\nfi\n',shell)
        self.assertNotIn("upload-artifact",shell)
        self.assertNotIn("gh release",shell)
        self.assertNotIn("adb -d ",shell)

if __name__=="__main__":
    unittest.main()
