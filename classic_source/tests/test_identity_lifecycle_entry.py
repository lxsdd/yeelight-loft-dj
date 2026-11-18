"""Synthetic in-Classic lifecycle entry checks, no OEM APK or device."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build_private_unsigned as candidate
import classic_first as classic

ATTR = "{http://schemas.android.com/apk/res/android}"

def provider(name, authority, exported="false"):
    return {"tag": "provider", "attributes": {
        ATTR + "name": name, ATTR + "authorities": authority,
        ATTR + "exported": exported,
    }}

def fake_elements(with_entry=True, *, exported="false", name=None,
                  authority="com.yeelight.cherry.loftdj.entry"):
    entries = [provider("com.yeelight.safe.Provider" + str(i),
                        "com.yeelight.cherry.safe" + str(i)) for i in range(5)]
    if with_entry:
        entries.append(provider(
            name or "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjEntryProvider",
            authority, exported=exported))
    return entries

class IdentityLifecycleEntryTests(unittest.TestCase):
    def _verify(self, elements, identity):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic-only")
            with patch.object(candidate, "parse_binary_xml",
                              return_value={"elements": elements}):
                return candidate.verify_final_firebase_boundary(
                    path, identity_control=identity)

    def test_identity_has_exact_six_nonexported_providers(self):
        report = self._verify(fake_elements(), identity=True)
        self.assertTrue(report["identity_dj_entry_provider"])
        self.assertEqual(report["remaining_oem_providers"], 5)

    def test_clone_has_its_own_nonexported_music_host_provider(self):
        report = self._verify(
            fake_elements(authority="io.lxsdd.yeelightdj.loftdj.music"),
            identity=False)
        self.assertFalse(report["identity_dj_entry_provider"])
        self.assertTrue(report["clone_native_music_provider"])

    def test_identity_rejects_missing_invalid_or_exported_bootstrap(self):
        cases = (
            (fake_elements(False), "FINAL_PROVIDER_COUNT_NOT_SIX"),
            (fake_elements(exported="true"), "FINAL_NATIVE_MUSIC_ENTRY_PROVIDER_INVALID"),
            (fake_elements(name="unknown.Injector"), "FINAL_NATIVE_MUSIC_ENTRY_PROVIDER_INVALID"),
        )
        for elements, code in cases:
            with self.subTest(code=code), self.assertRaisesRegex(
                    classic.GateFailure, code):
                self._verify(elements, identity=True)

    def test_delayed_oem_layout_is_reattached_without_global_overlay(self):
        source = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                  "/ui/ClassicDjEntryProvider.java").read_text("utf-8")
        import re
        sequence = re.search(r"LAYOUT_RECHECK_MS\s*=\s*\{([^}]+)\}", source)
        self.assertIsNotNone(sequence)
        delays = [int(v.strip().removesuffix("L")) for v in sequence.group(1).split(",")]
        self.assertEqual(delays[0], 0)
        self.assertLessEqual(len(delays), 6)
        self.assertEqual(delays, sorted(set(delays)))
        self.assertLessEqual(delays[-1], 3000)
        self.assertIn("handler.postDelayed(attempt, delayMs)", source)
        self.assertIn("cancelPending(activity);", source)
        self.assertIn("onActivityPaused(Activity a)", source)
        self.assertIn("onActivityDestroyed(Activity a)", source)
        self.assertIn("pauseMusicPanel(a);", source)
        # OEM onResume can be unfocused/unmeasured; neither should suppress
        # the bounded per-Activity attach before Android performs its layout.
        self.assertIn('probe(activity.hasWindowFocus() ?', source)
        self.assertNotIn("|| !activity.hasWindowFocus()", source)
        self.assertNotIn("content.getWidth() <= 0", source)
        self.assertNotIn("content.getHeight() <= 0", source)
        self.assertIn('probe("ENTRY_ADDED_TO_ACTIVITY")', source)
        self.assertIn('probe("ENTRY_ROOT_UNAVAILABLE")', source)
        self.assertIn("button.bringToFront();", source)
        self.assertIn("view.bringToFront();", source)
        for prohibited in ("WindowManager", "setContentView(", "set_music", "startActivity(intent)"):
            self.assertNotIn(prohibited, source)

    def test_clone_must_not_ship_original_identity_authority(self):
        with self.assertRaisesRegex(classic.GateFailure,
                                    "FINAL_NATIVE_MUSIC_ENTRY_PROVIDER_INVALID"):
            self._verify(fake_elements(True), identity=False)
        with self.assertRaisesRegex(classic.GateFailure,
                                    "FINAL_PROVIDER_COUNT_NOT_SIX"):
            self._verify(fake_elements(False), identity=False)
        with self.assertRaisesRegex(classic.GateFailure,
                                    "FINAL_NATIVE_MUSIC_ENTRY_PROVIDER_INVALID"):
            self._verify(
                fake_elements(exported="true",
                              authority="io.lxsdd.yeelightdj.loftdj.music"),
                identity=False)

    def test_embedded_music_actions_do_not_touch_oem_devices(self):
        provider_source = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                           "/ui/ClassicDjEntryProvider.java").read_text("utf-8")
        inline = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                  "/ui/ClassicMusicInlineControls.java").read_text("utf-8")
        self.assertIn("isOriginalMusicHost(activity.getPackageName()", provider_source)
        self.assertIn("root.addView(controls, params);", provider_source)
        self.assertIn("new ClassicMusicInlineControls(activity)", provider_source)
        self.assertIn("onHostPaused()", inline)
        self.assertIn("host.stopService(new Intent(host, ClassicDjService.class))", inline)
        self.assertIn("new String[0]", inline)
        self.assertIn("ClassicDjService.ACTION_CONFIG", inline)
        self.assertIn("ClassicDjService.ACTION_STOP", inline)
        self.assertIn("ClassicDjService.ACTION_MIC", inline)
        for forbidden in ("startActivity(", "setContentView(", "set_music",
                          "set_power", "Socket(", "HttpURLConnection",
                          "Color4-IP(s)", "getRemoteDeviceList"):
            self.assertNotIn(forbidden, inline)

    def test_notification_routes_to_verified_music_only(self):
        source_root = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
        service = (source_root / "ClassicDjService.java").read_text("utf-8")
        provider = (source_root / "ClassicDjEntryProvider.java").read_text("utf-8")
        self.assertIn("ClassicDjEntryProvider.verifiedMusicReturnIntent(this)", service)
        self.assertNotIn("new Intent(this, ClassicDjActivity.class)", service)
        self.assertIn("if (open != null) n.setContentIntent(open)", service)
        self.assertIn("if (caller == null || !CLONE_PACKAGE.equals(caller.getPackageName())) return null;", provider)
        self.assertIn("if (observed == null || observed.isFinishing() || observed.isDestroyed()) return null;", provider)
        self.assertIn("if (!isOriginalMusicHost(observed.getPackageName(), activityName)) return null;", provider)
        self.assertIn("if (observedMusicHost.get() == a)", provider)
        self.assertNotIn("getLaunchIntentForPackage", service)

    def test_provider_lifecycle_safe_inline_entry_contract(self):
        path = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                "/ui/ClassicDjEntryProvider.java")
        source = path.read_text("utf-8")
        entry = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                 "/ui/ClassicDjEntryView.java").read_text("utf-8")
        self.assertIn("implements Application.ActivityLifecycleCallbacks", source)
        self.assertIn("registerActivityLifecycleCallbacks(this)", source)
        self.assertIn("onActivityResumed(final Activity activity)", source)
        self.assertIn("handler.postDelayed(", source)
        self.assertIn("ORIGINAL_PACKAGE.equals(activity.getPackageName())", source)
        self.assertIn("activity instanceof ClassicDjActivity", source)
        self.assertIn("hasExistingEntry(root, 0)", source)
        self.assertIn("root.addView(button, params)", source)
        self.assertIn("Gravity.BOTTOM | Gravity.END", source)
        self.assertIn("new Intent(context, ClassicDjActivity.class)", entry)
        self.assertNotIn("startActivity(intent)", source)
        self.assertNotIn("HttpURLConnection", source)
        self.assertNotIn("Socket(", source)
        self.assertNotIn("getSystemService(Context.WINDOW_SERVICE)", source)
        self.assertIn("return null;", source)
        self.assertIn('probe("ENTRY_PROVIDER_REGISTERED")', source)
        self.assertIn('probe("ENTRY_ACTIVITY_RESUMED")', source)
        self.assertIn("ENTRY_VIEW_LAID_OUT", source)
        self.assertIn("ENTRY_VIEW_NOT_LAID_OUT", source)
        self.assertIn("ENTRY_HOST_FOCUSED", source)
        self.assertIn("ENTRY_HOST_UNFOCUSED", source)
        self.assertIn("setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)", entry)
        self.assertIn("view.getGlobalVisibleRect(visible)", source)
        self.assertIn('if (DIAG_EVENTS.getAndIncrement() >= 40) return;', source)
        self.assertNotIn("Log.d(", source)

if __name__ == "__main__":
    unittest.main()
