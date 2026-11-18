"""Source regression: native Classic Music inline pulse UI remains DSP-only."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (ROOT / "ClassicMusicInlineControls.java").read_text("utf-8")
SERVICE = (ROOT / "ClassicDjService.java").read_text("utf-8")


class NativePulseControlsTest(unittest.TestCase):
    def test_native_inline_pulse_ui_has_live_timing(self):
        self.assertIn("pulseControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE)", INLINE)
        self.assertIn("beatPulse = !beatPulse", INLINE)
        self.assertIn("pulseTempo = (pulseTempo + 1) % PULSE_TEMPOS.length", INLINE)
        self.assertIn("pulseTempoButton.setEnabled(!beatPulse)", INLINE)
        self.assertIn("PULSE_TEMPOS = {60, 90, 120, 150, 180}", INLINE)

    def test_initial_and_live_config_both_include_pulse_settings(self):
        self.assertEqual(INLINE.count("addPulseSettings(intent);"), 2)
        self.assertIn("ClassicDjService.EXTRA_PULSE_MODE", INLINE)
        self.assertIn("ClassicDjService.EXTRA_PULSE_BPM", INLINE)
        self.assertIn("ClassicDjService.ACTION_CONFIG", INLINE)
        self.assertIn("configureRuntime(runtime, intent, gain, scene)", SERVICE)
        self.assertIn("target.configurePulse(", SERVICE)

    def test_no_unverified_lamp_target_or_duplicate_picker(self):
        self.assertIn("new String[0]", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        for forbidden in ("set_music", "set_power", "ColorSelectView(", "new EditText(", "startActivity("):
            self.assertNotIn(forbidden, INLINE)

    def test_no_stale_preparing_status_after_runtime_stops(self):
        self.assertIn("Analyse nicht aktiv · erneut starten oder stoppen", INLINE)
        self.assertIn("ClassicDjService.hasActiveSession()", INLINE)


if __name__ == "__main__":
    unittest.main()
