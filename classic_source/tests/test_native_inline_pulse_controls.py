"""Original music effect strip and original SceneFragment pulse editor ownership."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (ROOT / "ClassicMusicInlineControls.java").read_text("utf-8")
GALLERY = (ROOT / "ClassicSceneGalleryPanel.java").read_text("utf-8")
SERVICE = (ROOT / "ClassicDjService.java").read_text("utf-8")
RUNTIME = (Path(__file__).resolve().parents[1] / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text("utf-8")


class NativePulseControlsTest(unittest.TestCase):
    def test_original_scene_editor_exclusively_owns_pulse_settings(self):
        self.assertIn("pulseEditor.setVisibility(s.scene == 3 ? View.VISIBLE : View.GONE)", GALLERY)
        self.assertIn("s.gainIndex, !s.beatPulse", GALLERY)
        self.assertIn("(s.pulseTempoIndex + 1) % 5", GALLERY)
        self.assertIn("(s.pulseShapeIndex + 1) % 3", GALLERY)
        self.assertIn("pulseTempo.setEnabled(!s.beatPulse)", GALLERY)
        self.assertIn("pulseShape.setText(", GALLERY)
        self.assertIn("ClassicSceneUiState.update(", INLINE)
        self.assertNotIn("pulseModeButton", INLINE)
        self.assertNotIn("gainButton", INLINE)
        self.assertNotIn("EXTRA_PULSE_BPM", INLINE)

    def test_one_original_player_does_not_start_second_capture(self):
        for forbidden in ("sourceButton", "startButton", "ACTION_MIC", "ACTION_INTERNAL",
                          "ACTION_STOP", "host.startService(", "stopOwned("):
            self.assertNotIn(forbidden, INLINE)
        self.assertIn("Audioquelle und Start/Stopp: oben in Classic", INLINE)
        self.assertIn("preview.setMode(selected.scene, selected.beatPulse,", INLINE)
        self.assertIn("Kein Original-Audiositzungsbeleg", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertIn("target.applySceneSettings(new DjRuntime.SceneSettings(", SERVICE)
        self.assertIn("ClassicPulseScene.Options(", RUNTIME)
        self.assertNotIn("set_power", INLINE)

    def test_gallery_and_music_share_no_duplicate_storage(self):
        self.assertIn("ClassicSceneUiState.revision()", INLINE)
        self.assertIn("ClassicSceneUiState.snapshot()", INLINE)
        self.assertNotIn("SharedPreferences", INLINE)
        self.assertNotIn("SQLiteDatabase", INLINE)
        self.assertNotIn("new Dialog(", INLINE)


if __name__ == "__main__":
    unittest.main()
