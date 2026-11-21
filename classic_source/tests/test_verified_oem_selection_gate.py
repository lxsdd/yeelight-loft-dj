"""Regression: all Classic DJ UI paths fail closed on unverified OEM lamp identities.

No device, APK, emulator or network access. Tests enforce the production
Service gate and both entry points rather than merely testing a helper.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
SERVICE = (ROOT / "ClassicDjService.java").read_text("utf-8")
ACTIVITY = (ROOT / "ClassicDjActivity.java").read_text("utf-8")
INLINE = (ROOT / "ClassicMusicInlineControls.java").read_text("utf-8")


class VerifiedOemSelectionGateTest(unittest.TestCase):
    def test_service_rejects_nonempty_intent_targets_before_runtime_start(self):
        block = SERVICE.split("final String[] selectedIps =", 1)[1].split(
            "final DjRuntime session;", 1)[0]
        self.assertIn("if (!ips.isEmpty())", block)
        self.assertIn("stopSelf();", block)
        self.assertIn("return START_NOT_STICKY;", block)
        self.assertIn("OEM-Lampenauswahl nicht verifiziert", block)
        self.assertNotIn("requestStart(", block)
        self.assertLess(SERVICE.index("if (!ips.isEmpty())"),
                        SERVICE.index("session = obtainExistingCheckpoint()"))

    def test_legacy_activity_cannot_emit_manual_ip_targets(self):
        start = ACTIVITY.split("private void startMicService()", 1)[1].split(
            "static boolean validPrivateLanIp", 1)[0]
        self.assertIn("final String[] selectedIps = new String[0];", start)
        self.assertIn("i.putExtra(EXTRA_SELECTED_IPS, selectedIps);", start)
        self.assertNotIn("lampIps", start)
        self.assertNotIn("musicOptIn", start)
        self.assertNotIn("ips.toArray(", start)
        self.assertNotIn("Music-Rückkanal für", start)
        self.assertIn("Lampensteuerung gesperrt", ACTIVITY)
        self.assertNotIn("new EditText(this)", ACTIVITY)
        self.assertNotIn("new CheckBox(this)", ACTIVITY)

    def test_original_music_inline_stays_analysis_only(self):
        self.assertIn("new String[0]", INLINE)
        self.assertIn("ClassicDjService.ACTION_MIC", INLINE)
        self.assertIn("ClassicDjService.ACTION_STOP", INLINE)
        self.assertIn("host.startActivity(intent); // transient Android consent broker; no DJ screen", INLINE)
        self.assertIn("inlineSheet.addView(expanded);", INLINE)
        self.assertNotIn("new Dialog(", INLINE)
        self.assertNotIn("host.setContentView(", INLINE)
        self.assertNotIn("set_power", INLINE)
        self.assertNotIn("set_music", INLINE)


if __name__ == "__main__":
    unittest.main()
