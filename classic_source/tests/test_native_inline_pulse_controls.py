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
        self.assertIn("new DjRuntime.SceneSettings(", SERVICE)
        self.assertIn("target.applySceneSettings(", SERVICE)
        self.assertNotIn("target.configurePulse(", SERVICE)

    def test_native_pulse_envelope_and_gamma_are_live_in_original_music_ui(self):
        self.assertIn("pulseShape = (pulseShape + 1) % PULSE_SHAPES.length", INLINE)
        self.assertIn("pulseShapeControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE)", INLINE)
        self.assertIn("PULSE_SHAPES = {", INLINE)
        self.assertIn("PULSE_GAMMAS = {2.2, 1.2, 2.0}", INLINE)
        for extra in ("EXTRA_PULSE_MIN", "EXTRA_PULSE_MAX", "EXTRA_PULSE_ATTACK",
                      "EXTRA_PULSE_DECAY", "EXTRA_PULSE_GAMMA"):
            self.assertIn("ClassicDjService." + extra, INLINE)
            self.assertIn(extra, SERVICE)
        self.assertEqual(INLINE.count("addPulseSettings(intent);"), 2)
        self.assertIn("configureIfOwned();", INLINE)

    def test_pulse_presets_are_valid_and_dsp_only(self):
        self.assertIn("{12, 65, 160, 900}", INLINE)
        self.assertIn("{8, 100, 70, 330}", INLINE)
        self.assertIn("{12, 90, 90, 600}", INLINE)
        self.assertNotIn("set_power", INLINE)
        self.assertNotIn("set_bright", INLINE)
        self.assertIn("controlSheet.setContentView(scroll);", INLINE)
        self.assertNotIn("host.setContentView(", INLINE)
        self.assertIn("host.startActivity(intent); // transient Android consent broker; no DJ screen", INLINE)

    def test_no_unverified_lamp_target_or_duplicate_picker(self):
        self.assertIn("new String[0]", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        for forbidden in ("set_music", "set_power", "ColorSelectView(", "new EditText(", "host.setContentView("):
            self.assertNotIn(forbidden, INLINE)

    def test_owner_start_failure_replaces_stale_preparing_status(self):
        self.assertIn("ClassicDjService.startFailureForOwner(sessionOwner)", INLINE)
        self.assertIn("START_ACK_TIMEOUT_MS", INLINE)
        self.assertIn("ClassicDjService.hasActiveSession()", INLINE)
        self.assertIn("erst Stopp, dann erneut", INLINE)
        self.assertNotIn("Analyse nicht aktiv · erneut starten oder stoppen", INLINE)


if __name__ == "__main__":
    unittest.main()
