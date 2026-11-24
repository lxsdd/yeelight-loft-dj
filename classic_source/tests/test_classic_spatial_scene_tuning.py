"""Exact source contracts for the Classic scene-gallery -> single owned runtime.
The executable Kotlin/JVM tests exercise actual output; this closes the Android
Service boundary without publishing OEM symbols or sending device commands.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENGINE = (ROOT / "checkpoint/src/ClassicDjEngine.kt").read_text("utf8")
SCENE = (ROOT / "checkpoint/src/ClassicSpatialChoreography.kt").read_text("utf8")
RUNTIME = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text("utf8")
INTERFACE = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java").read_text("utf8")
SERVICE = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java").read_text("utf8")


class SceneTuningWiringTest(unittest.TestCase):
    def test_one_real_scene_engine_for_timer_and_music(self):
        self.assertIn("fun configureSpatial(", ENGINE)
        self.assertIn("spatial.configure(proposed)", ENGINE)
        self.assertIn("spatial.frame(mode, index, snapshot.size", ENGINE)
        self.assertIn("coordinator.frame(id, generation, look.rgb, look.brightness)", ENGINE)
        self.assertIn("clockTick(timestampMs: Long)", ENGINE)
        self.assertIn("private fun dispatchOwned(", ENGINE)
        self.assertIn("lastSceneInputMs = timestampMs", ENGINE)
        self.assertNotIn("lighting.reset()\n            lastAudioMs = timestampMs", ENGINE)

    def test_spatial_configuration_is_bounded_before_output(self):
        for phrase in ("rgb == -1 || rgb in 0..0xFFFFFF",
                       "speedPercent in 25..400", "trailLength in 1..8",
                       "minBrightness in 1..99",
                       "maxBrightness in (minBrightness + 1)..100"):
            self.assertIn(phrase, SCENE)
        self.assertIn("if (options.reverse) count - 1 - index else index", SCENE)
        self.assertIn("if (options.rgb == -1) color else options.rgb", SCENE)
        self.assertIn("intensity * energy", SCENE)
        self.assertNotIn("set_power", SCENE)
        self.assertNotIn("set_music", SCENE)

    def test_all_sources_and_timed_scene_share_the_exact_runtime(self):
        self.assertIn("boolean configureSpatial(int rgb, int speedPercent", INTERFACE)
        self.assertIn("override fun configureSpatial(", RUNTIME)
        self.assertIn("engine?.configureSpatial(", RUNTIME)
        self.assertIn("check(newEngine.configureSpatial(", RUNTIME)
        self.assertIn("startCapture(selectedLampIps, listener, sourceInput = null)", RUNTIME)
        self.assertIn("ClassicPcmDjBridge(newEngine", RUNTIME)
        self.assertIn("ClassicMusicCoordinator(transportFactory(", RUNTIME)

    def test_service_validates_scene_extras_and_fails_closed(self):
        for extra in ("EXTRA_SPATIAL_RGB", "EXTRA_SPATIAL_SPEED",
                      "EXTRA_SPATIAL_REVERSE", "EXTRA_SPATIAL_TRAIL",
                      "EXTRA_SPATIAL_MIN", "EXTRA_SPATIAL_MAX"):
            self.assertIn(extra, SERVICE)
        self.assertIn("private static int spatialColor(Intent intent)", SERVICE)
        self.assertIn("if (!intent.hasExtra(EXTRA_SPATIAL_RGB)) return -1", SERVICE)
        self.assertIn("return -2;", SERVICE)
        self.assertLess(SERVICE.index("if (spatialRgb < -1"),
                        SERVICE.index("target.applySceneSettings("))
        self.assertLess(SERVICE.index("if (rgb < 0 || rgb > 0xFFFFFF"),
                        SERVICE.index("target.applySceneSettings("))
        self.assertIn("new DjRuntime.SceneSettings(", SERVICE)
        self.assertIn("return target.applySceneSettings(", SERVICE)
        self.assertNotIn("target.configurePulse(rgb,", SERVICE)
        self.assertNotIn("target.configureSpatial(spatialRgb, speed,", SERVICE)
        self.assertIn("boolean applySceneSettings(SceneSettings settings)", INTERFACE)
        self.assertIn("override fun applySceneSettings(settings: DjRuntime.SceneSettings)", RUNTIME)
        self.assertIn("(clockOnly && !clockSceneAllowed(settings.scene, nextPulse))", RUNTIME)
        self.assertIn("catch (RuntimeException | LinkageError incompatible)", SERVICE)
        self.assertIn("uiOwner.owns(intent.getStringExtra(EXTRA_UI_OWNER))", SERVICE)
        # No guessed OEM selection or unapproved Lamp-to-RGB shortcut.
        self.assertIn("OEM-Lampenauswahl nicht verifiziert", SERVICE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertNotIn("ACTION_TIMED_SCENE", SERVICE)


if __name__ == "__main__":
    unittest.main()
