"""Synthetic source contract for zero-restart Classic DJ live controls.

The private original and hardware tests remain separate owner-only gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PATHS = {
  "activity": ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjActivity.java",
  "service": ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java",
  "interface": ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java",
  "runtime": ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt",
  "bridge": ROOT / "checkpoint/src/ClassicPcmDjBridge.kt",
  "engine": ROOT / "checkpoint/src/ClassicDjEngine.kt",
}

class LiveDJConfigurationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {k: p.read_text("utf-8") for k,p in PATHS.items()}

    def test_user_action_only_changes_existing_session(self):
        ui = self.sources["activity"]
        self.assertIn("ClassicDjService.hasActiveSession()", ui)
        self.assertIn("config.setAction(ClassicDjService.ACTION_CONFIG)", ui)
        self.assertIn("EXTRA_GAIN, liveGain", ui)
        self.assertIn("EXTRA_SCENE, liveScene", ui)
        self.assertIn("if (!ClassicDjService.hasActiveSession()) return;", ui)
        self.assertIn('String[] codes = {"SPECTRUM", "AMBIENT", "BEAT", "PULSE"}', ui)
        self.assertIn('writePulseSettings(config);', ui)
        self.assertIn('writePulseSettings(i);', ui)
        self.assertIn('pulseBeatSync = false;', ui)
        self.assertIn('pulseBeatSync = true;', ui)

    def test_live_config_does_not_restart_mic_or_music_network(self):
        service = self.sources["service"]
        start = service.index('if (ACTION_CONFIG.equals(action))')
        end = service.index('if (!ACTION_MIC.equals(action))', start)
        cfg = service[start:end]
        self.assertIn("runtime.configure(gain, scene)", cfg)
        for invalid in ("startForeground(", "startMusicMode", "set_music", "set_power",
                        "session.start(", "sessionFence.begin()", "obtainExistingCheckpoint()"):
            self.assertNotIn(invalid, cfg)
        self.assertIn("boolean configure(double sensitivity, String scene);",
                      self.sources["interface"])

    def test_pulse_owned_dispatch_and_strict_parameter_gate(self):
        service = self.sources["service"]
        self.assertIn("configureRuntime(runtime, intent, gain, scene)", service)
        self.assertIn("configureRuntime(session, intent, requestedGain, requestedScene)", service)
        self.assertIn('if (!"BEAT".equals(mode) && !"TIMER".equals(mode)) return false;', service)
        self.assertIn("value != Math.rint(value)", service)
        self.assertIn("target.configurePulse(", service)
        self.assertIn("target.configure(gain, scene)", service)
        self.assertIn("boolean configurePulse(int rgb, boolean beatSync", self.sources["interface"])
        run = self.sources["runtime"]
        block = run[run.index("override fun configurePulse("):run.index("override fun start(")]
        for forbidden in ("requestStart(", "requestStop(", "Socket(", "set_power", "set_music", "startGroup("):
            self.assertNotIn(forbidden, block)
        self.assertIn("newEngine.configurePulse(pulse)", run)
        self.assertIn("ClassicPulseScene.Options(", block)

    def test_dsp_bounded_scene_and_gain_without_new_transport(self):
        run = self.sources["runtime"]
        cfg = run[run.index("override fun configure("):run.index("override fun start(")]
        self.assertIn('scene !in setOf("SPECTRUM", "AMBIENT", "BEAT", "PULSE")', cfg)
        self.assertIn("sensitivity !in 0.25..4.0", cfg)
        self.assertIn("inputBridge?.setSensitivity(sensitivity)", cfg)
        self.assertIn("engine?.setScene(scene)", cfg)
        self.assertIn("state == State.STOPPING || state == State.CLOSED", cfg)
        for method in ("requestStart(", "requestStop(", "startGroup(", "stopGroup(",
                       "ServerSocket", "Socket(", "set_music", "set_power"):
            self.assertNotIn(method, cfg)
        self.assertIn("@Synchronized fun setSensitivity(value: Double): Boolean",
                      self.sources["bridge"])
        self.assertIn("@Synchronized fun setScene(scene: String): Boolean",
                      self.sources["engine"])

if __name__ == "__main__":
    unittest.main()
