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

def fake_elements(with_entry=True, *, exported="false", name=None):
    entries = [provider("com.yeelight.safe.Provider" + str(i),
                        "com.yeelight.cherry.safe" + str(i)) for i in range(5)]
    if with_entry:
        entries.append(provider(
            name or "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjEntryProvider",
            "com.yeelight.cherry.loftdj.entry", exported=exported))
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

    def test_clone_retains_original_five_without_bootstrap(self):
        report = self._verify(fake_elements(False), identity=False)
        self.assertFalse(report["identity_dj_entry_provider"])

    def test_identity_rejects_missing_invalid_or_exported_bootstrap(self):
        cases = (
            (fake_elements(False), "FINAL_PROVIDER_COUNT_NOT_SIX"),
            (fake_elements(exported="true"), "FINAL_IDENTITY_ENTRY_PROVIDER_INVALID"),
            (fake_elements(name="unknown.Injector"), "FINAL_IDENTITY_ENTRY_PROVIDER_INVALID"),
        )
        for elements, code in cases:
            with self.subTest(code=code), self.assertRaisesRegex(
                    classic.GateFailure, code):
                self._verify(elements, identity=True)

    def test_clone_must_not_ship_identity_provider(self):
        with self.assertRaisesRegex(classic.GateFailure,
                                    "FINAL_PROVIDER_COUNT_NOT_FIVE"):
            self._verify(fake_elements(True), identity=False)

    def test_provider_lifecycle_safe_inline_entry_contract(self):
        path = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                "/ui/ClassicDjEntryProvider.java")
        source = path.read_text("utf-8")
        entry = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj"
                 "/ui/ClassicDjEntryView.java").read_text("utf-8")
        self.assertIn("implements Application.ActivityLifecycleCallbacks", source)
        self.assertIn("registerActivityLifecycleCallbacks(this)", source)
        self.assertIn("onActivityResumed(final Activity activity)", source)
        self.assertIn("handler.post(", source)
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

if __name__ == "__main__":
    unittest.main()
