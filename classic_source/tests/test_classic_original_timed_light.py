"""Regression for production Original-Classic clock-only scene admission.

Pure policy is compiled with Java 8 from the actual shipped bridge source.
Android code and actual Kotlin runtime wiring are checked additionally; no
APK, OEM credentials, hardware or LAN commands are used.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
MODE = UI / "ClassicOriginalLightMode.java"
INLINE = UI / "ClassicMusicInlineControls.java"
RUNTIME = ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt"

HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
public final class OriginalTimedModeHarness {
    static void yes(boolean b,String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) {
        int d=ClassicOriginalLightMode.DENY;
        int a=ClassicOriginalLightMode.ORIGINAL_AUDIO;
        int t=ClassicOriginalLightMode.TIMED;
        for (String s : new String[]{"SCANNER","METEOR","WAVE"}) {
            yes(ClassicOriginalLightMode.choose(s,true,false)==t, s+" timer without source");
            yes(ClassicOriginalLightMode.choose(s,true,true)==a, s+" real audio preferred");
            yes(ClassicOriginalLightMode.stillPermitted(t,s,true,false), s+" timer stays owned");
            yes(ClassicOriginalLightMode.stillPermitted(t,s,true,true), s+" timer not stolen by audio");
        }
        yes(ClassicOriginalLightMode.choose("PULSE",false,true)==t, "timer pulse owns clock");
        yes(ClassicOriginalLightMode.choose("PULSE",false,false)==t, "timer pulse needs no audio");
        yes(ClassicOriginalLightMode.stillPermitted(t,"PULSE",false,false), "timer pulse stable");
        yes(!ClassicOriginalLightMode.stillPermitted(t,"PULSE",true,true), "no beat-pulse without source");
        for (String s : new String[]{"SPECTRUM","AMBIENT","BEAT","PULSE"}) {
            yes(ClassicOriginalLightMode.choose(s,true,false)==d, s+" cannot forge PCM");
            yes(ClassicOriginalLightMode.choose(s,true,true)==a, s+" uses verified PCM");
            yes(!ClassicOriginalLightMode.stillPermitted(t,s,true,false), s+" not timer scene");
        }
        yes(!ClassicOriginalLightMode.stillPermitted(a,"SCANNER",true,false), "stale audio closes only audio owner");
        yes(ClassicOriginalLightMode.stillPermitted(a,"SCANNER",true,true), "active audio allowed");
        yes(ClassicOriginalLightMode.choose("UNKNOWN",true,true)==d, "unknown scene denied");
        yes(!ClassicOriginalLightMode.stillPermitted(t,"UNKNOWN",false,true), "unknown live mode denied");
        yes(!ClassicOriginalLightMode.stillPermitted(d,"WAVE",true,true), "no unapproved mode");
        System.out.println("ORIGINAL_CLASSIC_TIMED_MODE_POLICY_PASS");
    }
}
"""

class OriginalClassicTimedLightTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_actual_compiled_original_scene_mode_policy(self):
        with tempfile.TemporaryDirectory(prefix="classic-owned-clock-") as temp:
            source=Path(temp)/"OriginalTimedModeHarness.java"
            source.write_text(HARNESS, encoding="utf-8")
            result=subprocess.run(
                ["javac","--release","8","-d",temp,str(MODE),str(source)],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(["java","-cp",temp,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalTimedModeHarness"],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("ORIGINAL_CLASSIC_TIMED_MODE_POLICY_PASS",run.stdout)

    def test_original_music_activity_and_runtime_are_wired_to_same_clock_owner(self):
        ui=INLINE.read_text(encoding="utf-8")
        rt=RUNTIME.read_text(encoding="utf-8")
        for required in [
            "ClassicOriginalLightMode.choose(", "ClassicOriginalLightMode.stillPermitted(",
            "beginLightOutput(consent, mode)", "runtime.startOriginalTimedScene(targets, outputListener, outputGuard)",
            "runtime.startOriginalAudio(targets, outputListener, outputGuard)",
            "ClassicOriginalLightOutputGate.tryAcquire()",
            "ClassicOriginalMusicSelection.isOutputLeaseCurrent(consent)",
            "ClassicSceneUiState.revision() != sceneRevision",
            "lightMode == ClassicOriginalLightMode.ORIGINAL_AUDIO",
            "lightMode = ClassicOriginalLightMode.DENY",
            "stopLightOutput();"
        ]:
            self.assertIn(required,ui,required)
        for required in [
            "fun startOriginalTimedScene(", "originalAuthorization = consent",
            "if (originalAuthorization != null)", "originalOutputGuard?.isStillAuthorized()",
            "clockSceneAllowed(scene, pulse)", "engine?.clockTick(clock())"
        ]:
            if required == "engine?.clockTick(clock())":
                self.assertIn("current?.clockTick(clock())",rt)
            else:
                self.assertIn(required,rt,required)
        self.assertNotIn("new AudioRecord(",ui)
        self.assertNotIn("startService(",ui)
        self.assertNotIn("set_power",ui)

if __name__=="__main__":
    unittest.main()
