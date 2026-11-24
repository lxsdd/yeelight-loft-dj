"""Executable original MusicActivity reverse-session admission regression.

The JDK test executes the SAME process-global Java class shipped in the
private Classic bridge. No OEM APK, LAN I/O, credentials or lamp writes.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
UI=ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
SOURCE=UI/"ClassicOriginalLightOutputGate.java"
INLINE=UI/"ClassicMusicInlineControls.java"

HARNESS=r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class OriginalLightAdmissionHarness {
  static void ok(boolean value,String name) {
    if (!value) throw new AssertionError(name);
  }
  public static void main(String[] args) throws Exception {
    long first=ClassicOriginalLightOutputGate.tryAcquire();
    ok(first>0,"first original host admitted");
    ok(ClassicOriginalLightOutputGate.isBusy(),"exclusive admission held");
    ok(ClassicOriginalLightOutputGate.tryAcquire()==0,"same Activity double start");
    ok(!ClassicOriginalLightOutputGate.beginClose(first+1),"foreign close forbidden");
    ok(ClassicOriginalLightOutputGate.beginClose(first),"closing begins before async stop");
    ok(!ClassicOriginalLightOutputGate.beginClose(first),"duplicate close forbidden");
    ok(ClassicOriginalLightOutputGate.tryAcquire()==0,"new Activity waits for cleanup");
    ok(!ClassicOriginalLightOutputGate.finishClose(first+1,true),"foreign release denied");
    ok(!ClassicOriginalLightOutputGate.finishClose(first-1,true),"stale release denied");
    ok(ClassicOriginalLightOutputGate.finishClose(first,true),"confirmed cleanup releases");
    long second=ClassicOriginalLightOutputGate.tryAcquire();
    ok(second>first,"generation cannot be reused");
    ok(!ClassicOriginalLightOutputGate.finishClose(first,true),"late callback cannot clear new session");
    ok(ClassicOriginalLightOutputGate.tryAcquire()==0,"new owner still exclusive");
    ok(ClassicOriginalLightOutputGate.beginClose(second),"second session closing");
    ok(ClassicOriginalLightOutputGate.finishClose(second,true),"second cleanup released");

    final CountDownLatch go=new CountDownLatch(1),done=new CountDownLatch(16);
    final AtomicInteger admitted=new AtomicInteger();
    for (int i=0;i<16;i++) {
      new Thread(() -> {
        try {
          go.await();
          if (ClassicOriginalLightOutputGate.tryAcquire()!=0L) admitted.incrementAndGet();
        } catch (InterruptedException e) { throw new AssertionError(e); }
        finally { done.countDown(); }
      }).start();
    }
    go.countDown();
    ok(done.await(5,TimeUnit.SECONDS),"all concurrent Activity calls completed");
    ok(admitted.get()==1,"only one caller admitted from 16");

    // A failed network cleanup must NEVER clear the process-wide owner.
    // This is deliberately last: quarantined state must survive all retries.
    // A production test may reset it only by starting a new app process.
    // Obtain admitted token without allowing a second simultaneous writer:
    // the concurrent generation is known as serial 3 at this point.
    long token=3L;
    ok(ClassicOriginalLightOutputGate.beginClose(token),"concurrent owner close");
    ok(!ClassicOriginalLightOutputGate.finishClose(token,false),"uncertain close blocked");
    ok(ClassicOriginalLightOutputGate.isQuarantined(),"quarantine visible to UI");
    ok(ClassicOriginalLightOutputGate.isBusy(),"uncertain output is busy");
    ok(ClassicOriginalLightOutputGate.tryAcquire()==0,"no new commands after uncertain cleanup");
    ok(!ClassicOriginalLightOutputGate.finishClose(token,true),
      "late apparent success cannot erase an already uncertain stop");
    System.out.println("CLASSIC_ORIGINAL_LIGHT_ADMISSION_PASS");
  }
}
"""

class OriginalLightAdmissionTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),"Java 8 toolchain")
    def test_actual_process_wide_gate_under_concurrency_and_uncertain_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            harness=Path(temp)/"OriginalLightAdmissionHarness.java"
            harness.write_text(HARNESS,encoding="utf-8")
            compiled=subprocess.run(
                ["javac","--release","8","-d",temp,str(SOURCE),str(harness)],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(["java","-cp",temp,
                 "io.github.lxsdd.yeelightclassicdj.ui.OriginalLightAdmissionHarness"],
                 capture_output=True,text=True,timeout=35)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("CLASSIC_ORIGINAL_LIGHT_ADMISSION_PASS",run.stdout)

    def test_scene_and_favorite_selection_check_owner_before_output_config(self):
        text=INLINE.read_text(encoding="utf-8")
        for declaration,next_marker in (
            ("private void selectScene(int index) {",
             "/** Load a full clone-local DJ profile"),
            ("private void selectFavorite(int slot) {",
             "/** The original Classic SceneFragment"),
        ):
            section=text.split(declaration,1)[1].split(next_marker,1)[0]
            self.assertIn("redraw();",section)
            self.assertNotIn("applyLightSettings();",section,
                "rejected mode must not receive a configuration frame first")
        # A user-chosen effect is admitted before any output reconfiguration.
        redraw=text.split("private void redraw() {",1)[1].split(
            "private final Runnable refresh",1)[0]
        self.assertLess(redraw.index("!modeStillAllowed(lightMode)"),
                        redraw.index("applyLightSettings();"))
        self.assertIn("stopLightOutput(reasonForCurrentOutput());",redraw)

    def test_gate_is_in_real_original_music_control_lifecycle(self):
        text=INLINE.read_text(encoding="utf-8")
        self.assertIn("ClassicOriginalLightOutputGate.tryAcquire()",text)
        self.assertIn("ClassicOriginalLightOutputGate.beginClose(token)",text)
        self.assertIn("ClassicOriginalLightOutputGate.finishClose(token, clean)",text)
        self.assertIn("stopLightOutput(runtime, epoch)",text)
        self.assertIn("observerEpoch != originalObserverEpoch",text)
        self.assertIn("ClassicOriginalLightOutputGate.isBusy()",text)
        self.assertNotIn("host.startService(",text)
        self.assertNotIn("set_power",text)

if __name__=="__main__":
    unittest.main()
