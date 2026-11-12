"""Synthetic contract for private Classic candidate launcher; never reads OEM APK."""
import contextlib
from io import StringIO
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
import clone_launcher_selection as m

GOOD = m.PACKAGE + "/com.yeelight.cherry.MainActivity"
OTHER = m.PACKAGE + "/com.yeelight.cherry.OtherLauncher"
BADGING = "package: name='io.lxsdd.yeelightdj' versionCode='35402'\n"


class CloneLauncherContract(unittest.TestCase):
    def test_oem_entry_selected_from_real_os_resolution(self):
        m.verify_badging(BADGING)
        self.assertEqual(m.select_installed_launcher(GOOD, GOOD),
                         "com.yeelight.cherry.MainActivity")
        self.assertEqual(m.select_installed_launcher(GOOD + "\n" + OTHER, OTHER),
                         "com.yeelight.cherry.OtherLauncher")

    def test_original_baseline_launcher_is_explicit_and_clone_mode_remains_strict(self):
        original = m.ORIGINAL + "/com.yeelight.cherry.MainActivity"
        m.verify_badging("package: name='com.yeelight.cherry' versionCode='35402'\n",
                         package=m.ORIGINAL)
        self.assertEqual(m.select_installed_launcher(original, original,
                             package=m.ORIGINAL), "com.yeelight.cherry.MainActivity")
        with self.assertRaises(m.CloneLauncherBlocked):
            m.select_installed_launcher(original, original)
        with self.assertRaises(m.CloneLauncherBlocked):
            m.verify_badging(BADGING, package=m.ORIGINAL)
        with self.assertRaises(m.CloneLauncherBlocked):
            m.select_installed_launcher(original, original, package="foreign.app")

    def test_aapt_launchable_heuristics_not_required_for_clone(self):
        m.verify_badging(BADGING)
        self.assertEqual(m.select_installed_launcher(GOOD, GOOD),
                         "com.yeelight.cherry.MainActivity")

    def test_wrong_badging_and_overlarge_input_fail(self):
        for value in ("package: name='com.yeelight.cherry'\n",
                      BADGING + BADGING, "", "a" * 65537):
            with self.subTest(value=value[:50]), self.assertRaises(m.CloneLauncherBlocked):
                m.verify_badging(value)

    def test_foreign_internal_native_and_untrusted_launcher_fail_closed(self):
        for value in (
            "com.other/com.yeelight.cherry.MainActivity",
            m.PACKAGE + "/.MainActivity",
            m.PACKAGE + "/io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity",
            m.PACKAGE + "/com.other.Evil",
            GOOD + "; rm -rf /",
            GOOD + "\n" + OTHER,
        ):
            with self.subTest(input=value), self.assertRaises(m.CloneLauncherBlocked):
                m.canonical(value)

    def test_no_ambiguous_or_unresolved_component(self):
        cases = (
            ("", GOOD),
            ("No activities found", GOOD),
            (GOOD + "\n" + GOOD, GOOD),
            (GOOD + "\n" + OTHER, "No activity found"),
            (GOOD, OTHER),
            (GOOD, GOOD + "\n" + OTHER),
            (GOOD, m.PACKAGE + "/.Unknown"),
            (GOOD, "com.other/com.yeelight.cherry.MainActivity"),
            (GOOD, GOOD + "\nSecret"),
        )
        for query, resolved in cases:
            with self.subTest(query=query[:24], resolved=resolved[:24]):
                with self.assertRaises(m.CloneLauncherBlocked):
                    m.select_installed_launcher(query, resolved)

    def test_cli_preinstall_and_installed_without_vendor_leak(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "badging.txt"
            q = Path(d) / "query.txt"
            r = Path(d) / "resolved.txt"
            p.write_text(BADGING, encoding="utf-8")
            q.write_text(GOOD, encoding="utf-8")
            r.write_text(GOOD, encoding="utf-8")
            with contextlib.redirect_stdout(StringIO()) as o:
                self.assertEqual(m.main(["--preinstall", "--badging", str(p)]), 0)
                self.assertEqual(m.main(["--installed", "--badging", str(p),
                                         "--query", str(q), "--resolved", str(r)]), 0)
            self.assertEqual(o.getvalue(), "com.yeelight.cherry.MainActivity\n")

    def test_cli_bad_inputs_redact_private_component_names(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "badging.txt"
            q = Path(d) / "query.txt"
            r = Path(d) / "resolved.txt"
            p.write_text(BADGING, encoding="utf-8")
            q.write_text(m.PACKAGE + "/com.yeelight.cherry.PrivateSecret", encoding="utf-8")
            r.write_text(GOOD, encoding="utf-8")
            with contextlib.redirect_stderr(StringIO()) as err:
                with contextlib.redirect_stdout(StringIO()) as out:
                    self.assertEqual(m.main(["--installed", "--badging", str(p),
                                             "--query", str(q), "--resolved", str(r)]), 2)
            self.assertEqual(out.getvalue(), "")
            self.assertIn("CLONE_LAUNCHER_DIAGNOSTIC_RESOLVED_LAUNCHER_MISMATCH",
                          err.getvalue())
            self.assertNotIn("PrivateSecret", err.getvalue())


if __name__ == "__main__":
    unittest.main()
