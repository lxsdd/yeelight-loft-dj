"""Executable synthetic proof of the REAL Classic Music UI probe route.

Uses a fake offline ADB boundary; neither original OEM APK nor phone, account,
network, microphone permission, nor lamp operations are accessed.
"""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_android17_start_smoke import FakeADB, ACTIVITY, LAUNCHER, PACKAGE, PRIVATE_UI_SNAPSHOT
from android17_start_smoke import (
    SmokeBlocked, classify_visible_ui, entry_button_coordinates,
    _native_clone_music_host,
)
MUSIC = PACKAGE + "/com.yeelight.cherry.ui.music.MusicActivity"
CLOSED = ('<?xml version="1.0"?><hierarchy>'
          '<node package="' + PACKAGE + '" text="Loft DJ" clickable="true" '
          'enabled="true" content-desc="Loft DJ Steuerung in Classic-Musik einblenden" '
          'bounds="[10,20][210,100]"/></hierarchy>')
OPEN = CLOSED.replace('</hierarchy>',
    '<node package="' + PACKAGE + '" content-desc="Loft DJ Status innerhalb Classic Music"/>'
    '<node package="' + PACKAGE + '" text="Analyse" clickable="true" enabled="true" '
    'content-desc="Mikrofonanalyse starten, ohne Lampen"/>'
    '<node package="' + PACKAGE + '" text="Stopp" clickable="true" enabled="true" '
    'content-desc="Loft DJ Mikrofonanalyse stoppen"/>'
    '</hierarchy>')
WELCOME = ('<?xml version="1.0"?><hierarchy><node package="' + PACKAGE +
           '" text="PRIVATE_OEM_LOGIN_TEXT"/></hierarchy>')

class FakeMusicADB(FakeADB):
    def __init__(self, *, music=True, ui=CLOSED, expanded=OPEN, after=None):
        super().__init__()
        self.music = music
        self.first_ui = ui
        self.expanded_ui = expanded
        self.after = after
        self.clicked = False
    def __call__(self, args, *, serial):
        key = tuple(args)
        if key == ("shell", "dumpsys", "activity", "activities"):
            self.calls.append(key)
            return ACTIVITY % (
                (self.after if self.after else MUSIC) if self.clicked
                else MUSIC if self.music else LAUNCHER)
        if key == ("shell", "uiautomator", "dump", PRIVATE_UI_SNAPSHOT):
            self.calls.append(key)
            self.ui_count += 1
            self._dump_pending = self.expanded_ui if self.clicked else self.first_ui
            return "UI hierarchy dumped to: " + PRIVATE_UI_SNAPSHOT
        if key[:3] == ("shell", "input", "tap"):
            self.calls.append(key)
            self.clicked = True
            return ""
        return super().__call__(args, serial=serial)

class NativeMusicRealPathTest(unittest.TestCase):
    def test_genuine_oem_music_inline_expands_without_secondary_activity(self):
        a = FakeMusicADB()
        result = a.run(require_clone_music_ui=True)
        self.assertEqual(result["status"], "PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_OBSERVED")
        self.assertTrue(result["entrypoint_observations"][0]["oem_music_activity_resumed"])
        self.assertTrue(result["entrypoint_observations"][1]["oem_music_activity_retained"])
        self.assertEqual(result["entrypoint_observations"][1]["status"],
                         "EMULATOR_CLASSIC_MUSIC_INLINE_OPENED")
        self.assertEqual([c for c in a.calls if c[:3] == ("shell", "input", "tap")],
                         [("shell", "input", "tap", "110", "60")])
        self.assertEqual(sum(c[:3] == ("shell", "am", "start") for c in a.calls), 1)
        self.assertFalse(result["microphone_started"])
        self.assertFalse(result["lamp_commands_sent"])
        self.assertFalse(result["cloud_login_tested"])
        self.assertFalse(result["miot_binding_attested"])
        self.assertFalse(result["app_functionally_accepted"])

    def test_oem_welcome_is_start_evidence_not_fake_music_failure(self):
        a = FakeMusicADB(music=False, ui=WELCOME)
        result = a.run(require_clone_music_ui=True)
        self.assertEqual(result["status"], "PRIVATE_EMULATOR_OEM_START_ONLY")
        self.assertTrue(result["entrypoint_observations"][0]["resumed_activity_observed"])
        self.assertEqual(result["entrypoint_observations"][0]["status"],
                         "OEM_START_OBSERVED_MUSIC_NOT_ATTESTED")
        self.assertFalse(result["native_entry_navigation_tested"])
        self.assertFalse(result["entrypoint_observations"][0]["oem_music_activity_resumed"])
        self.assertFalse(any(c[:3] == ("shell", "input", "tap") for c in a.calls))
        self.assertNotIn("PRIVATE_OEM_LOGIN_TEXT", str(result))

    def test_music_without_inline_button_is_start_only(self):
        a = FakeMusicADB(ui=WELCOME)
        result = a.run(require_clone_music_ui=True)
        self.assertEqual(result["status"], "PRIVATE_EMULATOR_OEM_START_ONLY")
        self.assertTrue(result["entrypoint_observations"][0]["oem_music_activity_resumed"])
        self.assertFalse(any(c[:3] == ("shell", "input", "tap") for c in a.calls))

    def test_stale_host_or_standalone_activity_after_tap_fails(self):
        for wrong in (LAUNCHER, PACKAGE +
                      "/io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity"):
            with self.subTest(wrong=wrong):
                a = FakeMusicADB(after=wrong)
                result = a.run(require_clone_music_ui=True)
                self.assertEqual(result["status"],
                                 "PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_BLOCKED")
                self.assertFalse(result["entrypoint_observations"][1]
                                 ["oem_music_activity_retained"])

    def test_missing_any_real_expanded_control_never_passes(self):
        for label in ("Loft DJ Status innerhalb Classic Music",
                      "Mikrofonanalyse starten, ohne Lampen",
                      "Loft DJ Mikrofonanalyse stoppen"):
            a = FakeMusicADB(expanded=OPEN.replace(label, "removed"))
            result = a.run(require_clone_music_ui=True)
            self.assertEqual(result["status"],
                             "PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_BLOCKED")
            self.assertFalse(result["entrypoint_observations"][1]
                             ["ui"]["expected_native_ui_observed"])

    def test_foreign_disabled_legacy_and_duplicate_toggle_never_tapped(self):
        button = CLOSED.split("<node ", 1)[1].split("/>", 1)[0]
        bad = (
            CLOSED.replace('package="' + PACKAGE + '"',
                           'package="com.android.systemui"'),
            CLOSED.replace('enabled="true"', 'enabled="false"'),
            CLOSED.replace("Steuerung in Classic-Musik einblenden", "Legacy home shortcut"),
        )
        for ui in bad:
            with self.subTest(ui=ui[:25]):
                a = FakeMusicADB(ui=ui)
                result = a.run(require_clone_music_ui=True)
                self.assertEqual(result["status"], "PRIVATE_EMULATOR_OEM_START_ONLY")
                self.assertFalse(any(c[:3] == ("shell", "input", "tap") for c in a.calls))

    def test_duplicate_toggle_coordinates_are_rejected_before_tap(self):
        button = CLOSED.split("<node ", 1)[1].split("/>", 1)[0]
        twice = CLOSED.replace("</hierarchy>", "<node " + button + "/></hierarchy>")
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_CLASSIC_ENTRY_AMBIGUOUS"):
            entry_button_coordinates(twice, "Physical size: 1080x2400",
                                     native_music=True)

    def test_strict_activity_not_legacy_home_or_dj(self):
        self.assertTrue(_native_clone_music_host(ACTIVITY % MUSIC))
        for snap in (ACTIVITY % LAUNCHER,
                     ACTIVITY % (PACKAGE +
                        "/io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity"),
                     "mResumedActivity: ActivityRecord{123 u0 " + MUSIC + " t1}",
                     "ResumedActivity: ActivityRecord{123 u0 com.other/.MusicActivity t1}"):
            self.assertFalse(_native_clone_music_host(snap))

    def test_inline_closed_and_open_are_separate_observations(self):
        self.assertTrue(classify_visible_ui(CLOSED, stage="music_inline_toggle")
                        ["expected_native_ui_observed"])
        self.assertFalse(classify_visible_ui(CLOSED, stage="music_inline_expanded")
                         ["expected_native_ui_observed"])
        self.assertTrue(classify_visible_ui(OPEN, stage="music_inline_expanded")
                        ["expected_native_ui_observed"])
        self.assertEqual(entry_button_coordinates(CLOSED, "Physical size: 1080x2400",
                                                  native_music=True), (110,60))
        with self.assertRaisesRegex(SmokeBlocked, "SMOKE_MUSIC_MODE_OUT_OF_SCOPE"):
            entry_button_coordinates(CLOSED, "Physical size: 1080x2400",
                                     expected_package="com.yeelight.cherry",native_music=True)

if __name__ == "__main__":
    unittest.main()
