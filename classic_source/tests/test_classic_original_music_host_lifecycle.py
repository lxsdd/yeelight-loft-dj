"""Real Classic MusicActivity lifecycle: no repeated DSP reset on OEM rechecks.

Runs the production Java gate with concurrent synthetic host callbacks.
Never accesses private OEM bytes, apps, Android devices, LAN, accounts or lamps.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
GATE = UI / "ClassicOriginalMusicHostLifecycle.java"
INLINE = UI / "ClassicMusicInlineControls.java"
PROVIDER = UI / "ClassicDjEntryProvider.java"
SIGNAL = UI / "ClassicOriginalMusicAudioSignal.java"

HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public class OriginalMusicHostLifecycleHarness {
  static void check(boolean yes,String message) {
    if(!yes)throw new AssertionError(message);
  }
  public static void main(String[] ignored) throws Exception {
    ClassicOriginalMusicHostLifecycle music = new ClassicOriginalMusicHostLifecycle();
    check(!music.leaveResumed(),"unattached pause is inert");
    check(music.enterResumed(),"original MusicActivity first resume registers DSP");
    for(int i=0;i<8;i++)
      check(!music.enterResumed(),"delayed OEM layout pass is not another audio owner");
    check(music.leaveResumed(),"one original pause detaches exactly once");
    check(!music.leaveResumed(),"destroy after pause cannot detach twice");
    check(music.enterResumed(),"same surviving Activity may resume after Back");
    check(!music.enterResumed(),"second attach still denied after reentry");
    check(music.leaveResumed(),"reentry pause detaches once");
    // Concurrent provider UI checks must elect one true capture attach.
    final CountDownLatch start=new CountDownLatch(1);
    final CountDownLatch end=new CountDownLatch(24);
    final AtomicInteger admitted=new AtomicInteger();
    for(int i=0;i<24;i++) new Thread(() -> {
      try {
        start.await();
        if(music.enterResumed())admitted.incrementAndGet();
      } catch (InterruptedException e) {throw new AssertionError(e);}
      finally {end.countDown();}
    }).start();
    start.countDown();
    check(end.await(10,TimeUnit.SECONDS),"all delayed attach callbacks finish");
    check(admitted.get()==1,"exactly one original host DSP attach among 24");
    check(music.leaveResumed(),"concurrent generation can close once");
    check(!music.leaveResumed(),"no double close after concurrent generation");
    ClassicOriginalMusicHostLifecycle newActivity =
        new ClassicOriginalMusicHostLifecycle();
    check(newActivity.enterResumed(),"fresh Activity has independent lease");
    check(!music.leaveResumed(),"stale old Activity cannot release new Activity");
    check(newActivity.leaveResumed(),"new Activity pauses normally");
    ClassicOriginalMusicHostLifecycle retry = new ClassicOriginalMusicHostLifecycle();
    check(retry.enterResumed(),"optional DSP observer starts setup");
    check(retry.leaveResumed(),"setup failure must free old observer lease");
    check(retry.enterResumed(),"post-failure layout recheck is allowed");
    check(!retry.enterResumed(),"successful retry must not attach twice");
    check(retry.leaveResumed(),"successful retry is released on real pause");
    System.out.println("CLASSIC_ORIGINAL_MUSIC_HOST_LIFECYCLE_PASS");
  }
}
"""

class OriginalMusicHostLifecycleTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK required")
    def test_actual_java_lifecycle_gate_under_rechecks_and_concurrency(self):
        with tempfile.TemporaryDirectory(prefix="classic-original-music-host-") as d:
            harness=Path(d)/"OriginalMusicHostLifecycleHarness.java"
            harness.write_text(HARNESS,encoding="utf-8")
            compile_run=subprocess.run(["javac","--release","8","-d",d,
                str(GATE),str(harness)],capture_output=True,text=True,timeout=35)
            self.assertEqual(compile_run.returncode,0,compile_run.stderr)
            run=subprocess.run(["java","-cp",d,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalMusicHostLifecycleHarness"],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("CLASSIC_ORIGINAL_MUSIC_HOST_LIFECYCLE_PASS",run.stdout)

    def test_real_original_music_and_provider_callbacks_use_the_gate(self):
        text=INLINE.read_text(encoding="utf-8")
        provider=PROVIDER.read_text(encoding="utf-8")
        signal=SIGNAL.read_text(encoding="utf-8")
        self.assertIn("new ClassicOriginalMusicHostLifecycle()",text)
        resume=text.split("public void onHostResumed() {",1)[1].split(
            "public void onHostPaused() {",1)[0]
        self.assertLess(resume.index("if (!hostLifecycle.enterResumed()) return;"),
                        resume.index("++originalObserverEpoch;"))
        self.assertLess(resume.index("if (!hostLifecycle.enterResumed()) return;"),
                        resume.index("ClassicOriginalMusicAudioSignal.installAnalyzer(originalDsp);"))
        self.assertIn("observerEpoch != originalObserverEpoch",resume)
        self.assertIn("catch (RuntimeException | LinkageError unavailable)",resume)
        self.assertIn("hostLifecycle.leaveResumed();",resume)
        self.assertIn("++originalObserverEpoch;",resume.split(
            "catch (RuntimeException | LinkageError unavailable)",1)[1])
        self.assertIn("Original-Audioanalyse nicht verfügbar",resume)
        self.assertIn("ClassicOriginalMusicAudioSignal.clearAnalyzer(originalDsp);",resume)
        pause=text.split("public void onHostPaused() {",1)[1]
        self.assertLess(pause.index("if (!hostLifecycle.leaveResumed()) return;"),
                        pause.index("++originalObserverEpoch;"))
        self.assertIn("ClassicOriginalMusicAudioSignal.clearAnalyzer(originalDsp)",pause)
        self.assertIn("catch (RuntimeException | LinkageError ignored)",pause)
        self.assertIn("if (lightRuntime != null)\n            stopLightOutput(",pause)
        self.assertIn("pauseMusicPanel(a);",provider)
        self.assertIn("existing.onHostResumed();",provider)
        self.assertIn("controls.onHostResumed();",provider)
        self.assertIn("LAYOUT_RECHECK_MS",provider)
        self.assertIn("if (owner == next) return;",signal)
        for forbidden in ("new AudioRecord(", "set_power(", "set_music(",
                          "getRemoteDeviceList("):
            self.assertNotIn(forbidden,text)
            self.assertNotIn(forbidden,signal)

if __name__ == "__main__":
    unittest.main()
