"""Concrete owner-fenced analysis feedback and explicit microphone permission tests.

Pure JDK + static source wiring: no OEM binary, device, network or user data.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
STATE = UI / "ClassicAnalysisStartResult.java"
HARNESS = r'''
package io.github.lxsdd.yeelightclassicdj.ui;
public class ClassicAnalysisStartResultHarness {
    static void need(boolean value) {
        if (!value) throw new AssertionError("analysis start owner fence");
    }
    public static void main(String[] args) {
        ClassicAnalysisStartResult result = new ClassicAnalysisStartResult();
        need(result.failureFor("first") == null);
        need(!result.fail("first", "no owner"));
        result.begin("first");
        need(!result.fail("foreign", "foreign status"));
        need(result.fail("first", "microphone failed"));
        need("microphone failed".equals(result.failureFor("first")));
        need(result.failureFor("foreign") == null);
        need(!result.clear("foreign"));
        need("microphone failed".equals(result.failureFor("first")));
        result.begin("second");
        need(result.failureFor("first") == null);
        need(result.failureFor("second") == null);
        need(!result.fail("first", "late old failure"));
        need(result.fail("second", "capture error"));
        need(result.clear("second"));
        need(result.failureFor("second") == null);
        need(!result.clear("second"));
        need(!result.fail(null, "null"));
        result.begin(null);
        need(result.failureFor("second") == null);
        System.out.println("PASS Classic analysis exact-owner failure, retry and stale rejection");
    }
}
'''


class ClassicAnalysisStartResultTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("java") and shutil.which("javac"), "JDK unavailable")
    def test_compiled_real_owner_status_has_positive_and_negative_cases(self):
        with tempfile.TemporaryDirectory(prefix="classic-dj-analysis-") as tmp:
            temp = Path(tmp)
            source = temp / "ClassicAnalysisStartResultHarness.java"
            source.write_text(HARNESS, encoding="utf-8")
            output = temp / "classes"
            compiled = subprocess.run(
                ["javac", "--release", "8", "-d", str(output), str(STATE), str(source)],
                capture_output=True, text=True, timeout=40)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            executed = subprocess.run(
                ["java", "-cp", str(output),
                 "io.github.lxsdd.yeelightclassicdj.ui.ClassicAnalysisStartResultHarness"],
                capture_output=True, text=True, timeout=40)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn("PASS Classic analysis", executed.stdout)

    def test_real_ui_displays_exact_owner_failure_and_allows_explicit_retry(self):
        ui = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
        service = (UI / "ClassicDjService.java").read_text("utf-8")
        self.assertIn("ClassicDjService.startFailureForOwner(sessionOwner)", ui)
        self.assertIn("locallyStarted = false;", ui)
        self.assertIn("sessionOwner = null;", ui)
        self.assertIn("requestedAtMs = SystemClock.elapsedRealtime();", ui)
        self.assertIn("Analyse-Start nicht bestätigt", ui)
        self.assertIn("ClassicDjService.isStopping()", ui)
        self.assertIn("startResults.begin(requestedOwner);", service)
        self.assertIn("startResults.fail(completedOwner, finalState);", service)
        self.assertIn("startResults.clear(completedOwner);", service)
        self.assertIn("sessionFence.cancel();", service)

    def test_microphone_prompt_requires_explicit_tap_never_starts_on_permission_callback(self):
        ui = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
        self.assertIn("host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)", ui)
        self.assertIn("host.requestPermissions(", ui)
        self.assertIn("MICROPHONE_PERMISSION_REQUEST", ui)
        self.assertIn("micPermissionRequested = true;", ui)
        self.assertNotIn("onRequestPermissionsResult(", ui)
        self.assertNotIn("set_power", ui)
        self.assertNotIn("set_music", ui)
        self.assertIn("new String[0]", ui)

    def test_binary_incompatibility_does_not_crash_original_oem_music_ui(self):
        service = (UI / "ClassicDjService.java").read_text("utf-8")
        self.assertIn("catch (RuntimeException | LinkageError failure)", service)
        self.assertIn("rejectStart(requestedOwner, \"DJ-Einstellungen nicht unterstützt\")", service)
        self.assertIn('if (!ips.isEmpty())', service)
        self.assertNotIn("get_prop", service)


if __name__ == "__main__":
    unittest.main()
