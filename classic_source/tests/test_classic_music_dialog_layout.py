"""Original MusicActivity UI integration: no second audio controller.

Source-only checks; real OEM-APK layout still requires owner Pixel acceptance.
"""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[1]
UI = SRC / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
INLINE = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
PROVIDER = (UI / "ClassicDjEntryProvider.java").read_text("utf-8")
SERVICE = (UI / "ClassicDjService.java").read_text("utf-8")
BROKER = (UI / "ClassicInternalAudioConsentActivity.java").read_text("utf-8")
PATTERN = (UI / "ClassicDjPatternPreviewView.java").read_text("utf-8")


class ClassicMusicSheetRegression(unittest.TestCase):
    def test_one_in_original_host_reserved_compact_surface(self):
        for required in (
            "new ClassicMusicInlineControls(activity)",
            "root.getChildCount() != 1",
            "controls.setDockHeightListener(lease::resize)",
            "lp.bottomMargin = baseline + requested",
            "lp.bottomMargin = baseline",
            "if (lease != null) lease.detach();",
            "root.addView(controls, params);",
        ):
            self.assertIn(required, PROVIDER)
        for required in (
            "inlineSheet = new ScrollView(activity)",
            "inlineSheet.addView(content)",
            "inlineSheet.setVisibility(View.GONE)",
            "addView(inlineSheet,",
            "compactHeightPx = dp(46) + dp(10)",
            "screen * 0.26f",
            "closeSheet();",
        ):
            self.assertIn(required, INLINE)
        self.assertNotIn("new Dialog(", INLINE)
        self.assertNotIn("host.setContentView(", INLINE)
        self.assertNotIn("new Intent(", INLINE)
        self.assertNotIn("Gravity.TOP | Gravity.END", PROVIDER)

    def test_oem_player_owns_only_audio_switch_and_transport(self):
        self.assertIn("Audioquelle und Start/Stopp: oben in Classic", INLINE)
        self.assertIn("Keine zweite Aufnahme", INLINE)
        for forbidden in (
            "sourceButton", "startButton", "stopOwned(", "host.startActivity(",
            "host.startService(", "host.startForegroundService(",
            "requestPermissions(", "MediaProjection", "ClassicDjService.ACTION_MIC",
            "ClassicDjService.ACTION_INTERNAL", "ClassicDjService.ACTION_STOP",
            "ClassicDjService.frameForOwner(", "onRequestPermissionsResult(",
        ):
            # Production has no duplicate interactive source/start/stop path.
            self.assertNotIn(forbidden, INLINE)
        self.assertIn("manager.createScreenCaptureIntent()", BROKER)
        self.assertIn("resultCode != RESULT_OK", BROKER)
        self.assertIn("ACTION_INTERNAL", BROKER)

    def test_scene_state_is_original_gallery_shared_not_remote_record(self):
        self.assertIn("ClassicSceneUiState.snapshot()", INLINE)
        self.assertIn("ClassicSceneUiState.update(", INLINE)
        self.assertIn("shownRevision != ClassicSceneUiState.revision()", INLINE)
        self.assertIn("HorizontalScrollView effectsScroll", INLINE)
        self.assertIn("chip.setOnClickListener(v -> selectScene(choice))", INLINE)
        self.assertIn("ClassicDjEffectPreview.explanation(", INLINE)
        self.assertIn("new ClassicDjPatternPreviewView(activity)", INLINE)
        self.assertIn("Math.sin(", (UI / "ClassicDjPatternMath.java").read_text("utf-8"))
        for label in ("Spektrum", "Ruhig", "Beat", "Puls", "Scanner", "Meteor", "Welle"):
            self.assertIn(label, (UI / "ClassicSceneUiState.java").read_text("utf-8"))

    def test_distinguishes_simulated_pattern_from_actual_ownership(self):
        self.assertIn("keine bestätigte Auswahl", INLINE)
        self.assertIn("ClassicOriginalMusicSelection.fresh()", INLINE)
        self.assertIn("ClassicOriginalMusicSelection.originalGroupMemberCount()", INLINE)
        self.assertIn("originalMusicSelectionStatus.setText(", INLINE)
        self.assertIn("Nur Simulation", INLINE)
        self.assertIn("keine Lampe wird angesteuert", INLINE)
        self.assertIn("Eight abstract preview cells", PATTERN)
        self.assertNotIn("getRemoteDeviceList(", INLINE)
        self.assertNotIn("new String[0]", INLINE)
        self.assertIn("if (!ips.isEmpty())", SERVICE)

    def test_private_capture_fence_and_sanitized_diagnostics_still_exist(self):
        self.assertIn("private static String classifyStartError(boolean internal, Throwable failure)", SERVICE)
        for reason in ("MIC_PERMISSION","MIC_STATE","MIC_FORMAT","MIC_ABI","MIC_START",
                       "AUDIO_PERMISSION","AUDIO_STATE","AUDIO_ABI","AUDIO_FORMAT","AUDIO_START"):
            self.assertIn(reason, SERVICE)
        self.assertIn("sessionFence.cancel();", SERVICE)
        self.assertIn("startResults.fail(completedOwner, finalState)", SERVICE)

    def test_no_device_or_account_mutation_from_inline_ui(self):
        for body in (INLINE, PATTERN, BROKER):
            for forbidden in ("set_power", "set_music", "Socket(", "HttpURLConnection",
                              "grantRuntimePermission", "Math.random()"):
                self.assertNotIn(forbidden, body)


if __name__ == "__main__":
    unittest.main()
