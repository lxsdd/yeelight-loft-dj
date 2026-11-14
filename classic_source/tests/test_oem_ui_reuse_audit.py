"""Synthetic original-Classic UI reuse contracts; ZERO OEM bytes or devices."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from private_ci.oem_ui_reuse_audit import analyze, UiAuditBlocked

HEADER = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
          'package="com.yeelight.cherry"><application>'
          '<activity android:name="com.yeelight.cherry.ui.activity.MusicActivity"/>'
          '</application></manifest>')
LAYOUT = ('<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" '
          'android:layout_width="match_parent" android:layout_height="match_parent">'
          '<Button android:id="@+id/example" android:onClick="demo" '
          'android:layout_width="wrap_content" android:layout_height="wrap_content"/>'
          '</LinearLayout>')
SMALI = ('.class public Lcom/yeelight/cherry/ui/activity/MusicActivity;\n'
         '.super Landroid/app/Activity;\n'
         '.method public onCreate(Landroid/os/Bundle;)V\n'
         '    invoke-virtual {p0, v0}, Lcom/yeelight/cherry/ui/activity/MusicActivity;'
         '->setContentView(I)V\n'
         '    invoke-virtual {v0, v1}, Landroid/view/View;'
         '->setOnClickListener(Landroid/view/View$OnClickListener;)V\n'
         '    return-void\n.end method\n')
class OriginalUiReuseAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.resources, self.code = base / "resources", base / "code"
        (self.resources / "res/layout").mkdir(parents=True)
        (self.code / "smali/com/yeelight/cherry/ui/activity").mkdir(parents=True)
        (self.resources / "AndroidManifest.xml").write_text(HEADER, encoding="utf8")

    def write_layout(self, name, body=LAYOUT):
        path = self.resources / "res/layout" / (name + ".xml")
        path.write_text(body, encoding="utf8")
        return path

    def write_music(self, body=SMALI):
        path = (self.code / "smali/com/yeelight/cherry/ui/activity" /
                "MusicActivity.smali")
        path.write_text(body, encoding="utf8")
        return path

    def test_real_shape_mapping_never_claims_runtime_or_oem_reuse(self):
        p = self.write_layout("activity_music")
        self.write_layout("fragment_scene_list")
        self.write_layout("fragment_colorflow_color_select")
        self.write_layout("fragment_room_list")
        smali = self.write_music()
        before = {f: hashlib.sha256(f.read_bytes()).hexdigest() for f in
                  (p, smali, self.resources / "AndroidManifest.xml")}
        r = analyze(self.resources, self.code)
        music = r["roles"]["music"]
        self.assertEqual(music["original_layout_variants"], 1)
        self.assertEqual(music["unique_original_class_definitions"], 1)
        self.assertEqual(music["manifest_activity_definitions"], 1)
        self.assertEqual(music["xml_click_attributes"], 1)
        self.assertEqual(music["xml_interactive_widgets"], 1)
        self.assertGreater(music["source_reference_counts"]["view_inflation"], 0)
        self.assertGreater(music["source_reference_counts"]["listener_wiring"], 0)
        self.assertEqual(music["binding_state"], "STATIC_LAYOUT_AND_CLASS_CANDIDATES")
        self.assertEqual(r["roles"]["scenes"]["binding_state"], "LAYOUT_ONLY_CODE_UNMAPPED")
        self.assertFalse(r["original_apk_or_smali_published"])
        self.assertFalse(r["private_symbols_emitted"])
        self.assertFalse(r["runtime_ui_navigation_verified"])
        self.assertFalse(r["original_behavior_compatibility_verified"])
        self.assertFalse(music["production_hook_approved"])
        self.assertNotIn("MusicActivity.smali", str(r))
        self.assertNotIn("com.yeelight.cherry.ui.activity.MusicActivity", str(r))
        self.assertEqual(before, {f: hashlib.sha256(f.read_bytes()).hexdigest()
                                  for f in before})

    def test_layout_only_must_not_imply_working_music_navigation(self):
        self.write_layout("activity_music")
        r = analyze(self.resources, self.code)
        self.assertEqual(r["roles"]["music"]["binding_state"],
                         "LAYOUT_ONLY_CODE_UNMAPPED")
        self.assertEqual(r["roles"]["music"]["manifest_activity_definitions"], 0)

    def test_class_only_must_not_imply_known_picker_or_layout(self):
        self.write_music()
        r = analyze(self.resources, self.code)
        self.assertEqual(r["roles"]["music"]["binding_state"],
                         "CLASS_ONLY_LAYOUT_UNMAPPED")
        self.assertEqual(r["roles"]["colors"]["binding_state"], "UNMAPPED_NOT_PROVEN")

    def test_lookalike_third_party_class_is_never_original_proof(self):
        self.write_layout("activity_music")
        self.write_music(SMALI.replace("Lcom/yeelight/", "Lcom/fakebrand/"))
        r = analyze(self.resources, self.code)
        self.assertEqual(r["roles"]["music"]["unique_original_class_definitions"], 0)
        self.assertEqual(r["roles"]["music"]["binding_state"],
                         "LAYOUT_ONLY_CODE_UNMAPPED")

    def test_duplicate_class_source_is_ambiguous_not_accepted(self):
        self.write_layout("activity_music")
        self.write_music()
        extra = self.code / "smali_classes2/com/yeelight/cherry/ui/activity"
        extra.mkdir(parents=True)
        (extra / "MusicActivity.smali").write_text(SMALI, encoding="utf8")
        r = analyze(self.resources, self.code)
        self.assertEqual(r["roles"]["music"]["unique_original_class_definitions"], 0)

    def test_untrusted_xml_namespace_and_entities_refused(self):
        manifest = self.resources / "AndroidManifest.xml"
        original = manifest.read_text("utf8")
        manifest.write_text(original.replace("com.yeelight.cherry", "foreign.app"), "utf8")
        with self.assertRaisesRegex(UiAuditBlocked, "UI_NOT_ORIGINAL_MANIFEST"):
            analyze(self.resources, self.code)
        manifest.write_text(original, "utf8")
        p = self.write_layout("fragment_room_list", '<!DOCTYPE x [ <!ENTITY x "z"> ]><x/>')
        with self.assertRaisesRegex(UiAuditBlocked, "UI_XML_DECLARATION_DENIED"):
            analyze(self.resources, self.code)
        p.unlink()

    def test_bad_resource_root_and_symlink_are_rejected(self):
        with self.assertRaisesRegex(UiAuditBlocked, "UI_DECODED_ROOT_INVALID"):
            analyze(self.resources, self.resources)
        target = self.write_layout("activity_main_new")
        mirror = self.resources / "res/layout" / "fragment_scene_list.xml"
        mirror.symlink_to(target)
        with self.assertRaisesRegex(UiAuditBlocked, "UI_LAYOUT_FILE_INVALID"):
            analyze(self.resources, self.code)

    def test_decode_missing_never_turns_to_green(self):
        with self.assertRaisesRegex(UiAuditBlocked, "UI_DECODED_ROOT_INVALID"):
            analyze(self.resources, self.code / "missing")

    def test_production_builder_records_private_original_analysis_only(self):
        src=(Path(__file__).resolve().parents[1]/"build_private_unsigned.py").read_text("utf8")
        self.assertIn("original_ui_reuse_audit(decoded, codeonly)", src)
        self.assertLess(src.index("original_ui_reuse_audit(decoded, codeonly)"),
                        src.index("stage_reassembled_oem(original, codeonly, staged)"))
        self.assertIn('report["original_ui_reuse_inventory"] = ui_reuse', src)
        self.assertIn("'d', '-r', '-f'", src)
        self.assertIn("inspect_original(original)", src)
        self.assertNotIn("upload-artifact", src)

if __name__ == "__main__":
    unittest.main()
