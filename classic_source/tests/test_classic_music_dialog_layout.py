"""Owner-visible Classic Music layout/source/consent regression contract.

No OEM APK, physical lamp or Android permission is exercised in public CI.
"""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[1]
UI = SRC / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (UI / "ClassicMusicInlineControls.java").read_text(encoding="utf-8")
PROVIDER = (UI / "ClassicDjEntryProvider.java").read_text(encoding="utf-8")
SERVICE = (UI / "ClassicDjService.java").read_text(encoding="utf-8")
BROKER = (UI / "ClassicInternalAudioConsentActivity.java").read_text(encoding="utf-8")


class ClassicMusicSheetRegression(unittest.TestCase):
    def test_original_music_uses_reserved_in_place_panel_and_direct_modes(self):
        self.assertIn("new ClassicMusicInlineControls(activity)", PROVIDER)
        self.assertIn("root.getChildCount() != 1", PROVIDER)
        self.assertIn("ViewGroup.LayoutParams.MATCH_PARENT", PROVIDER)
        self.assertIn("controls.setDockHeightListener(lease::resize)", PROVIDER)
        self.assertIn("lp.bottomMargin = baseline + requested", PROVIDER)
        self.assertIn("lp.bottomMargin = baseline", PROVIDER)
        self.assertIn("if (lease != null) lease.detach();", PROVIDER)
        self.assertNotIn("Gravity.TOP | Gravity.END", PROVIDER)
        self.assertNotIn("belowToolbar", PROVIDER)
        self.assertIn("root.addView(controls, params);", PROVIDER)
        self.assertIn("inlineSheet = new ScrollView(activity)", INLINE)
        self.assertIn("inlineSheet.addView(expanded)", INLINE)
        self.assertIn("inlineSheet.setVisibility(View.GONE)", INLINE)
        self.assertIn("addView(inlineSheet,", INLINE)
        self.assertIn("choice.setOnClickListener(v -> selectScene(selectedScene))", INLINE)
        self.assertNotIn("scene = (scene + 1) % SCENES.length", INLINE)
        self.assertNotIn("new Dialog(", INLINE)
        self.assertNotIn("controlSheet", INLINE)
        self.assertNotIn("host.setContentView(", INLINE)
        self.assertNotIn("new Intent(host, ClassicDjActivity.class)", INLINE)
        self.assertIn("ClassicDjService.frameForOwner(sessionOwner)", INLINE)
        self.assertIn("ClassicDjEffectPreview.intensity(", INLINE)
        self.assertIn("PCM-Analyse noch nicht gestartet", INLINE)
        self.assertIn("new ProgressBar(activity, null,", INLINE)
        for name in ("Bass", "Mitten", "Präsenz", "Höhen"):
            self.assertIn(name, INLINE)
        self.assertNotIn("Math.random()", INLINE)

    def test_explains_internal_audio_dialog_without_claiming_screen_video_capture(self):
        self.assertIn("Quelle: Internes Android-Audio", INLINE)
        self.assertIn("Wir verarbeiten nur erlaubtes App-Audio, kein Bildschirmvideo", INLINE)
        self.assertIn("Eine Freigabe ist pro Sitzung erforderlich", INLINE)
        self.assertIn('startButton.setText(preferInternal ? "Audio freigeben" : "Analyse starten")', INLINE)
        self.assertIn("manager.createScreenCaptureIntent()", BROKER)
        self.assertIn("resultCode != RESULT_OK", BROKER)
        self.assertIn("host.startActivity(intent); // transient Android consent broker; no DJ screen", INLINE)
        self.assertIn("if (returnFromConsent) openControlSheet();", INLINE)
        self.assertIn("closeControlSheet();", INLINE)
        self.assertIn("inlineSheet.setVisibility(View.VISIBLE)", INLINE)

    def test_source_failure_classification_is_sanitized_and_owner_fenced(self):
        self.assertIn("private static String classifyStartError(boolean internal, Throwable failure)", SERVICE)
        for reason in ("AUDIO_PERMISSION", "AUDIO_STATE", "AUDIO_ABI",
                       "AUDIO_FORMAT", "AUDIO_START", "AUDIO_READ"):
            self.assertIn(reason, SERVICE)
        self.assertIn("stopSession(token, classifyStartError(internal, error), true)", SERVICE)
        self.assertIn("startResults.fail(completedOwner, finalState)", SERVICE)
        self.assertIn("startResults.failureFor(owner)", SERVICE)
        self.assertIn("ClassicDjService.startFailureForOwner(sessionOwner)", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)
        self.assertIn("new String[0]", INLINE)

    def test_no_unapproved_device_or_oem_mutation(self):
        for body in (INLINE, BROKER):
            for forbidden in ("set_power", "set_music", "Socket(", "HttpURLConnection",
                              "getRemoteDeviceList(", "grantRuntimePermission"):
                self.assertNotIn(forbidden, body)
        self.assertNotIn("startForegroundService(intent); // transient", INLINE)


if __name__ == "__main__":
    unittest.main()
