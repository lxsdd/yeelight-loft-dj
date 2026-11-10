"""Fail-closed source gate: native Classic Kotlin must be exercised by CI.

No SDK, OEM byte, hardware, Gradle execution or network call occurs in this
test; actual Kotlin compilation and nine main-method regressions run in Android CI.
"""
import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "classic_source/checkpoint_jvm_ci"
WORKFLOW = ROOT / ".github/workflows/android.yml"
MAIN_CLASSES = (
    "CoordinatorTestKt", "CancelDuringStartTestKt", "BeatAnalyzerTestKt",
    "DjEngineTestKt", "PcmBandsTestKt", "CaptureSessionTestKt",
    "AndroidAudioRecordInputTestKt", "YeelightReverseMusicTransportTestKt",
    "CheckpointRuntimeTestKt",
)


class NativeKotlinCITests(unittest.TestCase):
    def test_exact_ci_execution_contract(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("name: Compile and run Classic DJ JVM integration regressions (no OEM APK)", workflow)
        self.assertIn("gradle --no-daemon --stacktrace -p classic_source/checkpoint_jvm_ci verifyClassicJvm", workflow)
        self.assertEqual(workflow.count(" -p classic_source/checkpoint_jvm_ci verifyClassicJvm"), 1)
        self.assertIn("pull_request:", workflow)
        self.assertIn("contents: read", workflow)

    def test_all_nine_production_jvm_programs_and_owned_sources_wired(self):
        build = (PROJECT / "build.gradle.kts").read_text(encoding="utf-8")
        settings = (PROJECT / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('kotlin("jvm") version "2.2.20"', build)
        for name in MAIN_CLASSES:
            self.assertIn(name, build)
        self.assertEqual(build.count('to "loftdj.classic.') + build.count('to "io.github.lxsdd.'), 9)
        for directory in ('"../checkpoint/src"', '"../checkpoint/tests"',
                          '"../checkpoint/tests/android-stubs"', '"../bridge/kotlin"'):
            self.assertIn(directory, build)
        self.assertIn('"io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java"', build)
        self.assertTrue((PROJECT / "src/main/java/android/content/Context.java").is_file())
        self.assertIn("FAIL_ON_PROJECT_REPOS", settings)
        for forbidden in ("CLASSIC_ORIGINAL_APK", "gh release", "upload-artifact",
                          "workflow_dispatch", "adb ", "apksigner", "sdkmanager",
                          "set_music", "gh api"):
            self.assertNotIn(forbidden, build)

    def test_no_missing_entrypoint_or_unbounded_test_list(self):
        build = (PROJECT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('dependsOn(runTasks)', build)
        self.assertIn('sourceSets["main"].runtimeClasspath', build)
        self.assertEqual(len(MAIN_CLASSES), len(set(MAIN_CLASSES)))
        self.assertFalse(re.search(r"gradle\s+.*publish", build))


if __name__ == "__main__":
    unittest.main()
