"""Visible original MusicActivity spatial settings route, not a synthetic scene picker."""
from pathlib import Path
import unittest

UI = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
SERVICE = (UI / "ClassicDjService.java").read_text("utf-8")


class SpatialSettingsMusicUiTest(unittest.TestCase):
    def test_visible_grouped_scene_choices_and_live_parameters(self):
        self.assertIn('heading.setText(rowIndex == 0 ? "Musikreaktionen" : "Räumliche Lichtszene")', INLINE)
        self.assertIn('choice.setText((selected ? "✓ " : "") + SCENE_LABELS[i])', INLINE)
        gallery = (UI / "ClassicSceneGalleryPanel.java").read_text("utf-8")
        self.assertIn("sceneParametersReadOnly.setText(", INLINE)
        self.assertNotIn("spatialSpeedButton", INLINE)
        self.assertIn("spatialEditor.setVisibility(s.scene >= 4 ? View.VISIBLE : View.GONE)", gallery)
        self.assertIn("spatialEditor.addView(row)", gallery)
        self.assertIn("spatialEditor.addView(second)", gallery)
        self.assertIn("SPATIAL_SPEEDS = {50, 75, 100, 150, 200}", INLINE)
        self.assertIn("SPATIAL_TRAILS = {1, 2, 4, 6, 8}", INLINE)
        self.assertIn("SPATIAL_RANGES = {{1, 100}, {5, 60}, {20, 85}}", INLINE)
        self.assertIn("s.speedIndex, !s.reverse", gallery)
        self.assertIn("updateSceneParameterSummary();", INLINE)
        self.assertIn("configureIfOwned();", INLINE)

    def test_start_and_live_config_uses_same_atomic_scene_snapshot(self):
        self.assertEqual(INLINE.count("addSpatialSettings(intent);"), 2)
        for field in ("EXTRA_SPATIAL_SPEED", "EXTRA_SPATIAL_REVERSE",
                      "EXTRA_SPATIAL_TRAIL", "EXTRA_SPATIAL_MIN",
                      "EXTRA_SPATIAL_MAX"):
            self.assertIn("ClassicDjService." + field, INLINE)
            self.assertIn(field, SERVICE)
        self.assertIn("(double) SPATIAL_SPEEDS[spatialSpeedIndex]", INLINE)
        self.assertIn("(double) SPATIAL_TRAILS[spatialTrailIndex]", INLINE)
        self.assertIn("(double) SPATIAL_RANGES[spatialRangeIndex][0]", INLINE)
        self.assertIn("(double) SPATIAL_RANGES[spatialRangeIndex][1]", INLINE)
        self.assertIn("target.applySceneSettings(new DjRuntime.SceneSettings(", SERVICE)
        self.assertNotIn("intent.putExtra(ClassicDjService.EXTRA_SPATIAL_RGB", INLINE)

    def test_no_unqualified_oem_scene_device_or_source_start(self):
        self.assertIn("new String[0]", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertNotIn("startTimedScene(", INLINE)
        self.assertNotIn("ACTION_VISUALIZER", INLINE)
        for forbidden in ("set_power", "set_music", "new Dialog(",
                          "ColorSelectView(", "new EditText(",
                          "host.setContentView("):
            self.assertNotIn(forbidden, INLINE)


if __name__ == "__main__":
    unittest.main()
