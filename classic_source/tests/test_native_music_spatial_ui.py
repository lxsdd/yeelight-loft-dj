"""One original MusicActivity source with a compact native spatial preview."""
from pathlib import Path
import unittest

UI = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
GALLERY = (UI / "ClassicSceneGalleryPanel.java").read_text("utf-8")
MATH = (UI / "ClassicDjPatternMath.java").read_text("utf-8")
SERVICE = (UI / "ClassicDjService.java").read_text("utf-8")


class SpatialSettingsMusicUiTest(unittest.TestCase):
    def test_seven_modes_are_chips_not_legacy_button_grid(self):
        self.assertIn("HorizontalScrollView effectsScroll", INLINE)
        self.assertIn("chip.setOnClickListener(v -> selectScene(choice))", INLINE)
        self.assertIn("chip.setSelected(active)", INLINE) if "chip.setSelected(active)" in INLINE else self.assertIn("v.setSelected(active)", INLINE)
        self.assertIn("ClassicSceneUiState.update(", INLINE)
        self.assertIn("preview.setMode(selected.scene", INLINE)
        self.assertIn("Math.sin(", MATH)
        self.assertIn("case 4:", MATH)
        self.assertIn("case 5:", MATH)
        self.assertIn("case 6:", MATH)

    def test_spatial_parameters_remain_at_original_gallery(self):
        self.assertIn("spatialEditor.setVisibility(s.scene >= 4 ? View.VISIBLE : View.GONE)", GALLERY)
        self.assertIn("spatialEditor.addView(row)", GALLERY)
        self.assertIn("spatialEditor.addView(second)", GALLERY)
        self.assertIn("s.speedIndex, !s.reverse", GALLERY)
        self.assertIn("new int[]{60, 90, 120, 150, 180}", INLINE)
        self.assertNotIn("spatialSpeedButton", INLINE)
        self.assertNotIn("EXTRA_SPATIAL_SPEED", INLINE)
        self.assertIn("target.applySceneSettings(new DjRuntime.SceneSettings(", SERVICE)

    def test_oem_play_stop_and_devices_are_not_overridden(self):
        for forbidden in ("set_music", "set_power", "new Dialog(", "new Intent(",
                          "host.setContentView(", "host.startService(", "ACTION_VISUALIZER",
                          "getRemoteDeviceList("):
            self.assertNotIn(forbidden, INLINE)
        self.assertIn("keine bestätigte Auswahl", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)


if __name__ == "__main__":
    unittest.main()
