"""Production route for a verified Classic Visualizer source. Offline only."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text("utf8")
INTERFACE = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java").read_text("utf8")
SERVICE = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java").read_text("utf8")
VISUALIZER = (ROOT / "checkpoint/src/ClassicAndroidVisualizerInput.kt").read_text("utf8")
INLINE = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicMusicInlineControls.java").read_text("utf8")
BUILD = (ROOT / "checkpoint_jvm_ci/build.gradle.kts").read_text("utf8")


class VisualizerSourceContract(unittest.TestCase):
    def test_same_production_pcm_and_generation_owned_dsp(self):
        self.assertIn("startVisualizer(Context context, int audioSessionId", INTERFACE)
        self.assertIn("ClassicAndroidVisualizerInput.open(", RUNTIME)
        self.assertIn("startCapture(selectedLampIps, listener) {", RUNTIME)
        self.assertEqual(RUNTIME.count("ClassicPcmDjBridge(newEngine"), 1)
        self.assertEqual(RUNTIME.count("ClassicPcmCaptureSession(bridge"), 1)
        self.assertIn("session.startVisualizer(this, audioSessionId, ips, callback)", SERVICE)
        self.assertIn('"VisualizerInput" to "loftdj.classic.VisualizerInputTestKt"', BUILD)

    def test_original_visualizer_needs_explicit_session_not_a_fake_projection(self):
        self.assertIn("ACTION_VISUALIZER", SERVICE)
        self.assertIn("EXTRA_AUDIO_SESSION_ID", SERVICE)
        self.assertIn("visualizer && !intent.hasExtra(EXTRA_AUDIO_SESSION_ID)", SERVICE)
        self.assertIn("audioSessionId == 0", SERVICE)
        self.assertIn("Manifest.permission.MODIFY_AUDIO_SETTINGS", SERVICE)
        self.assertIn("ClassicDjEntryProvider.verifiedMusicReturnIntent(this)", SERVICE)
        self.assertIn("projectionRequired ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION", SERVICE)
        self.assertIn("if (projectionRequired)", SERVICE)
        self.assertIn("Visualizer.OnDataCaptureListener", VISUALIZER)
        self.assertIn("Visualizer.getCaptureSizeRange()", VISUALIZER)
        self.assertIn("Visualizer.getMaxCaptureRate()", VISUALIZER)
        self.assertIn("source !== visualizer", VISUALIZER)
        self.assertNotIn("import android.media.projection", VISUALIZER)
        self.assertNotIn("createScreenCaptureIntent", VISUALIZER)
        self.assertNotIn("ACTION_VISUALIZER", INLINE)
        self.assertNotIn("FileOutputStream", VISUALIZER)
        self.assertNotIn("Socket", VISUALIZER)

    def test_no_unverified_oem_participants_or_external_writes(self):
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertIn('require(audioSessionId >= 0)', RUNTIME)
        self.assertIn("sessionFence.owns(token)", SERVICE)
        self.assertIn("uiOwner.begin(requestedOwner)", SERVICE)
        self.assertIn("ClassicYeelightMusicTransport::validLanIp", RUNTIME)


if __name__ == "__main__":
    unittest.main()
