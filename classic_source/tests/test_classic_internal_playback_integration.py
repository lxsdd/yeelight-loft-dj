"""Source-only native internal audio contract; owner consent and Pixel still required."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
SERVICE = (BRIDGE / "ui/ClassicDjService.java").read_text("utf-8")
CONSENT = (BRIDGE / "ui/ClassicInternalAudioConsentActivity.java").read_text("utf-8")
INLINE = (BRIDGE / "ui/ClassicMusicInlineControls.java").read_text("utf-8")
RUNTIME = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text("utf-8")
PLAYBACK = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/ClassicPlaybackCaptureInput.kt").read_text("utf-8")
MANIFEST = (ROOT / "classic_first.py").read_text("utf-8")


class ClassicPlaybackContract(unittest.TestCase):
    def test_same_pcm_dsp_pipeline_for_mic_and_playback(self):
        self.assertIn("startCapture(selectedLampIps, listener) { inputFactory() }", RUNTIME)
        self.assertIn("ClassicPlaybackCaptureInput.open(projection", RUNTIME)
        self.assertEqual(RUNTIME.count("ClassicPcmDjBridge(newEngine"), 1)
        self.assertEqual(RUNTIME.count("ClassicPcmCaptureSession(bridge"), 1)

    def test_independent_timed_scenes_share_owned_engine_without_audio(self):
        interface = (BRIDGE / "runtime/DjRuntime.java").read_text("utf-8")
        self.assertIn("void startTimedScene(", interface)
        self.assertIn("override fun startTimedScene(", RUNTIME)
        self.assertIn("sourceInput = null", RUNTIME)
        self.assertIn("require(selectedLampIps.isNotEmpty())", RUNTIME)
        self.assertIn("clockSceneAllowed(scene, pulse)", RUNTIME)
        self.assertIn("if (clockOnly && !clockSceneAllowed(scene, pulse))", RUNTIME)
        self.assertIn("session?.poll(bufferSize = WINDOW_SIZE) ?: false", RUNTIME)
        self.assertIn("current?.clockTick(clock())", RUNTIME)
        self.assertIn("SCENE_TIMED_", RUNTIME)
        self.assertNotIn("ACTION_TIMED_SCENE", SERVICE)

    def test_uses_existing_loft_dj_android_capture_rules(self):
        for value in ("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN"):
            self.assertIn(value, PLAYBACK)
        self.assertIn("AudioPlaybackCaptureConfiguration.Builder(projection)", PLAYBACK)
        self.assertIn("AudioRecord.READ_NON_BLOCKING", PLAYBACK)
        self.assertIn("projection.registerCallback(", PLAYBACK)
        self.assertIn("projection.unregisterCallback(", PLAYBACK)
        self.assertIn("projection.stop()", PLAYBACK)
        self.assertNotIn("FileOutputStream", PLAYBACK)

    def test_explicit_one_time_android_consent_and_owner(self):
        self.assertIn("manager.createScreenCaptureIntent()", CONSENT)
        self.assertIn("startActivityForResult(", CONSENT)
        self.assertIn("resultCode != RESULT_OK", CONSENT)
        self.assertIn("ClassicDjService.ACTION_INTERNAL", CONSENT)
        self.assertIn("EXTRA_CONSENT_DATA", CONSENT)
        self.assertIn("host.startActivity(intent)", INLINE)
        self.assertIn("sourceButton.setOnClickListener", INLINE)
        self.assertIn("if (!internalOwned) stopOwned()", INLINE)
        self.assertIn("ClassicDjService.ownerForInternal()", INLINE)
        for denied in ("pm grant", "set_power", "set_music", "startDiscovery"):
            self.assertNotIn(denied, CONSENT)

    def test_service_fences_projection_and_nonparticipants(self):
        self.assertIn("ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION", SERVICE)
        self.assertIn("session.startInternal(this, mediaProjection, ips, callback)", SERVICE)
        self.assertIn("stopSession(ownedToken", SERVICE)
        self.assertIn("sessionFence.cancel();", SERVICE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertIn("void onSpectrum(float bass, float mid,", SERVICE)
        self.assertIn("acceptSpectrum(bass, mid, presence, treble, beat, confidence)", SERVICE)
        self.assertIn("private void acceptSpectrum(", SERVICE)
        self.assertIn("ClassicDjLiveFrame frameForOwner(String owner)", SERVICE)
        self.assertIn("if (current.shuttingDown || !current.sessionFence.owns(", SERVICE)
        self.assertIn("current.uiOwner.owns(owner)", SERVICE)
        self.assertIn("latestConfidence = 0f;", SERVICE)
        self.assertIn("microphone|mediaProjection", MANIFEST)
        self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", MANIFEST)


if __name__ == "__main__":
    unittest.main()
