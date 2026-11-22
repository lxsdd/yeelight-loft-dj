"""Exact clone-only original SceneFragment hook, not a generic UI injector."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import clone_scene_gallery_hook as hook
from classic_first import GateFailure

SMALI = """
.class public Lcom/yeelight/cherry/ui/fragment/SceneFragment;
.super Landroidx/fragment/app/Fragment;

.method public onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V
    .locals 4
    invoke-super {p0, p1, p2}, Landroidx/fragment/app/Fragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V
    const/4 v0, 0x0
    return-void
.end method

.method public onStop()V
    .locals 2
    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onStop()V
    return-void
.end method
"""

class SceneGalleryHookTest(unittest.TestCase):
    def test_exact_original_callbacks_preserved_and_only_two_hooks(self):
        changed = hook.patch_text(SMALI)
        self.assertEqual(changed.count("invoke-super"), 2)
        self.assertEqual(changed.count("invoke-static/range"), 2)
        self.assertEqual(changed.count("->attach(Ljava/lang/Object;Landroid/view/View;)V"), 1)
        self.assertEqual(changed.count("->detach(Ljava/lang/Object;)V"), 1)
        self.assertIn("const/4 v0, 0x0", changed)
        self.assertEqual(changed.count(".locals"), 2)
        self.assertNotIn("set_power", changed)
        with self.assertRaisesRegex(GateFailure, "ALREADY_PATCHED"):
            hook.patch_text(changed)

    def test_unverified_oem_shape_never_partially_patched(self):
        for broken in (
            SMALI.replace("SceneFragment;", "RoomFragment;"),
            SMALI.replace("invoke-super {p0, p1, p2}", "invoke-super {v0, p1, p2}"),
            SMALI.replace("onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
                          "onViewCreated(Landroid/view/View;)V"),
            SMALI.replace("    .locals 4\n", ""),
            SMALI.replace(".method public onStop()V", ".method public onDestroy()V"),
        ):
            with self.subTest(prefix=broken[:35]):
                with self.assertRaises(GateFailure):
                    hook.patch_text(broken)

    def test_shared_scene_model_is_actual_jdk_executable(self):
        import shutil
        import subprocess
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java toolchain not installed")
        source = Path(__file__).resolve().parents[1] / (
            "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicSceneUiState.java")
        harness = r'''
package io.github.lxsdd.yeelightclassicdj.ui;
public class SceneStateHarness {
    public static void main(String[] args) {
        long firstRevision = ClassicSceneUiState.revision();
        ClassicSceneUiState.Selection initial = ClassicSceneUiState.snapshot();
        if (initial.scene != 0 || initial.gainIndex != 1) throw new AssertionError("default");
        if (!ClassicSceneUiState.update(5, 4, true, 1, 2)) throw new AssertionError("gallery");
        if (ClassicSceneUiState.revision() <= firstRevision)
            throw new AssertionError("gallery change invisible to MusicActivity");
        long galleryRevision = ClassicSceneUiState.revision();
        ClassicSceneUiState.Selection gallery = ClassicSceneUiState.snapshot();
        if (gallery.scene != 5 || gallery.speedIndex != 4 || !gallery.reverse)
            throw new AssertionError("gallery state");
        if (!ClassicSceneUiState.updateAudio(3, false, 4, 0))
            throw new AssertionError("music state");
        if (ClassicSceneUiState.revision() <= galleryRevision)
            throw new AssertionError("music change invisible to gallery");
        long audioRevision = ClassicSceneUiState.revision();
        ClassicSceneUiState.Selection music = ClassicSceneUiState.snapshot();
        if (music.scene != 5 || music.gainIndex != 3 || music.beatPulse ||
            music.pulseTempoIndex != 4 || music.pulseShapeIndex != 0)
            throw new AssertionError("music state");
        if (ClassicSceneUiState.update(20, 0, false, 0, 0) ||
            ClassicSceneUiState.updateAudio(100, true, 0, 0))
            throw new AssertionError("invalid state accepted");
        if (ClassicSceneUiState.revision() != audioRevision)
            throw new AssertionError("rejected changes must not redraw gallery");
        ClassicSceneUiState.Selection after = ClassicSceneUiState.snapshot();
        if (after.scene != 5 || after.gainIndex != 3)
            throw new AssertionError("invalid state mutated owner");
        if (gallery.scene != 5 || gallery.gainIndex != 1)
            throw new AssertionError("immutable snapshot");
    }
}
'''
        with tempfile.TemporaryDirectory() as t:
            helper = Path(t) / "SceneStateHarness.java"
            helper.write_text(harness)
            subprocess.run(["javac", "-d", t, str(source), str(helper)], check=True)
            subprocess.run(["java", "-cp", t,
                "io.github.lxsdd.yeelightclassicdj.ui.SceneStateHarness"], check=True)

    def test_staged_private_copy_only_and_idempotence(self):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / hook.SOURCE_PATH
            path.parent.mkdir(parents=True)
            path.write_text(SMALI)
            record = hook.patch_staged_clone(Path(t))
            self.assertEqual(record["original_oem_methods_modified"], 2)
            self.assertFalse(record["runtime_ui_verified"])
            self.assertEqual(path.read_text().count("invoke-static/range"), 2)
            with self.assertRaisesRegex(GateFailure, "ALREADY_PATCHED"):
                hook.patch_staged_clone(Path(t))

class OriginalGalleryProductionSourceTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[1]
    def test_real_private_builder_routes_explicit_scene_hook(self):
        private = (self.ROOT / "build_private_unsigned.py").read_text()
        self.assertIn("patch_scene_gallery(staged)", private)
        self.assertIn("smali_report['scene_gallery']", private)
        self.assertNotIn("scene_gallery_hook", (self.ROOT / "classic_first.py").read_text())
    def test_gallery_and_music_share_in_memory_scene_model_no_device_control(self):
        bridge = self.ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
        gallery = (bridge / "ClassicSceneGalleryPanel.java").read_text()
        music = (bridge / "ClassicMusicInlineControls.java").read_text()
        for txt in (gallery, music):
            self.assertIn("ClassicSceneUiState", txt)
        self.assertIn("ClassicSceneUiState.snapshot()", gallery)
        self.assertIn("syncSceneFromGallery();", music)
        self.assertIn("ClassicSceneUiState.revision() != observedSceneRevision", music)
        self.assertIn("previous.panel.redraw();", gallery)
        self.assertIn("EXPLANATIONS[s.scene]", gallery)
        self.assertIn("pulseEditor.setVisibility(s.scene == 3 ? View.VISIBLE : View.GONE)", gallery)
        self.assertIn("spatialEditor.setVisibility(s.scene >= 4 ? View.VISIBLE : View.GONE)", gallery)
        self.assertIn("ClassicSceneUiState.updateAudio(s.gainIndex", gallery)
        self.assertIn("sceneParametersReadOnly.setText(", music)
        self.assertNotIn("spatialSpeedButton", music)
        self.assertIn("cards[i].setSelected(i == s.scene)", gallery)
        self.assertIn("ClassicSceneUiState.updateAudio(", music)
        self.assertIn("ClassicDjService.ACTION_CONFIG", gallery)
        self.assertIn("ClassicDjService.ownerForInternal()", gallery)
        self.assertIn("ClassicDjService.EXTRA_SPATIAL_SPEED", gallery)
        self.assertIn("ClassicDjService.EXTRA_PULSE_MODE", gallery)
        self.assertIn("publishSharedScene();", music)
        for forbidden in ("startTimedScene(", "set_power", "startForegroundService(", "new Dialog("):
            self.assertNotIn(forbidden, gallery)
        self.assertIn("root.getChildCount() != 1", gallery)
        self.assertIn("p.bottomMargin == originalBottom + reserved", gallery)
        self.assertIn("private static final String CLONE_PACKAGE", gallery)
        self.assertIn("original_scene_adapter_unchanged", (self.ROOT / "clone_scene_gallery_hook.py").read_text())

if __name__ == "__main__":
    unittest.main()
