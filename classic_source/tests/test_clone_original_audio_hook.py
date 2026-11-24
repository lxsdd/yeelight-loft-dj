"""Source-only original OEM audio witness contracts; no private OEM bytes.

Verify exact-smali anchor constraints plus execute actual production Java
PCM/FFT temporal provenance. This is NOT Pixel6a or bulb runtime acceptance.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from classic_first import GateFailure
import clone_original_audio_hook as hook

MIC = """
.class public final Ld4/y;
.super Ljava/lang/Thread;
.method public run()V
    .locals 22
    invoke-virtual {v5, v6, v0, v1}, Landroid/media/AudioRecord;->read([SII)I
    move-result v16
    invoke-virtual {v5}, Landroid/media/AudioRecord;->stop()V
    invoke-virtual {v5}, Landroid/media/AudioRecord;->release()V
    return-void
.end method
"""
FFT = """
.class public final Ld4/z$a;
.super Ljava/lang/Object;
.method public onFftDataCapture(Landroid/media/audiofx/Visualizer;[BI)V
    .locals 3
    invoke-virtual {v0, p2, p3}, Ld4/z;->b([BI)V
    return-void
.end method
.method public onWaveFormDataCapture(Landroid/media/audiofx/Visualizer;[BI)V
    .locals 0
    return-void
.end method
"""
WORKER = """
.class public final Ld4/z;
.super Ljava/lang/Thread;
.method public run()V
    .locals 2
    invoke-virtual {v0}, Landroid/media/audiofx/Visualizer;->release()V
    return-void
    invoke-virtual {v0}, Landroid/media/audiofx/Visualizer;->release()V
    return-void
.end method
"""

JAVA_HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.concurrent.*;
public final class OriginalMusicAudioHarness {
  static void check(boolean b, String name) { if (!b) throw new AssertionError(name); }
  static short[] pcm(int n) { short[] d = new short[n]; java.util.Arrays.fill(d,(short)8000); return d; }
  static final class Fake implements ClassicOriginalMusicAudioSignal.Analyzer {
    int pcmCalls, fftCalls, closeCalls, acceptedCalls;
    boolean throwOnAccepted, throwOnPcm;
    int lastValid, firstSample;
    CountDownLatch entered, resume;
    public void onCaptureClosed() { closeCalls++; }
    public void onAcceptedFrame(ClassicOriginalMusicAudioSignal.Frame frame, long ms) {
      acceptedCalls++;
      ClassicOriginalMusicAudioSignal.Snapshot current =
          ClassicOriginalMusicAudioSignal.snapshotAt(ms);
      check(current.fresh && current.level == frame.level &&
            current.bpm == frame.bpm && current.confidence == frame.confidence,
            "original audio state must be committed before any light observer");
      if (throwOnAccepted) throw new IllegalStateException("bad optional observer");
    }
    public ClassicOriginalMusicAudioSignal.Frame onPcm(short[] x, int n, int hz, long ms) {
      pcmCalls++;
      lastValid = n;
      firstSample = x[0];
      if (throwOnPcm) throw new IllegalStateException("bad DSP");
      check(hz == 16000,"original recorder format");
      if (entered != null) {
        entered.countDown();
        try {
          check(resume.await(5,TimeUnit.SECONDS),"concurrent OEM PCM released");
        } catch (InterruptedException e) { throw new IllegalStateException(e); }
      }
      return new ClassicOriginalMusicAudioSignal.Frame(.4f,new float[]{.7f,.2f,.1f,.05f},true,120f,.8f);
    }
    public ClassicOriginalMusicAudioSignal.Frame onFft(byte[] x, int hz, long ms) {
      fftCalls++;
      if (entered != null) {
        entered.countDown();
        try {
          check(resume.await(5,TimeUnit.SECONDS),"concurrent OEM FFT released");
        } catch (InterruptedException e) { throw new IllegalStateException(e); }
      }
      return new ClassicOriginalMusicAudioSignal.Frame(.3f,new float[]{.5f,.2f,.1f,.05f},false,120f,.8f);
    }
  }
  public static void main(String[] args) throws Exception {
    ClassicOriginalMusicAudioSignal.resetForTest();
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(100).fresh,"starts empty");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,100);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(100).fresh,
          "without owner, no simulated music");
    Fake first = new Fake();
    ClassicOriginalMusicAudioSignal.installAnalyzer(first);
    android.media.AudioRecord rec = new android.media.AudioRecord();
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(rec,pcm(1024),0,1024) == 1024 &&
          rec.readCalls == 1,"one unchanged original read and return value");
    check(first.pcmCalls == 1,"one real original mic callback");
    // The previous callback used a genuine monotonic clock. Only an
    // EXPLICIT test reset may use deterministic synthetic timestamps;
    // re-installing the same owner MUST preserve live measurements.
    ClassicOriginalMusicAudioSignal.resetForTest();
    ClassicOriginalMusicAudioSignal.installAnalyzer(first);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1000);
    ClassicOriginalMusicAudioSignal.Snapshot s =
        ClassicOriginalMusicAudioSignal.snapshotAt(1010);
    check(s.fresh && s.beat && s.source.equals("MIC"),"mic source and beat");
    check(s.bands[0] == .7f && s.bpm == 120f && s.confidence == .8f,
          "shared DSP reaches original host");
    // Five OEM delayed layout rechecks for the SAME original MusicActivity
    // must preserve already-observed PCM, beat and tempo, without closing DSP.
    for (int i=0;i<5;i++) ClassicOriginalMusicAudioSignal.installAnalyzer(first);
    s=ClassicOriginalMusicAudioSignal.snapshotAt(1010);
    check(s.fresh && s.beat && s.bands[0] == .7f && s.bpm == 120f &&
          first.closeCalls == 0,"repeated layout recheck never drops source");
    boolean rejected=false;
    try { ClassicOriginalMusicAudioSignal.installAnalyzer(null); }
    catch (IllegalArgumentException expected) { rejected=true; }
    check(rejected && ClassicOriginalMusicAudioSignal.snapshotAt(1010).fresh,
          "invalid owner cannot clear original source");
    s.bands[0] = 0f;
    check(ClassicOriginalMusicAudioSignal.snapshotAt(1011).bands[0] == .7f,
          "cannot overwrite current bands from a UI snapshot");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1025,16000,1012);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),0,16000,1013);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,0,1014);
    check(first.pcmCalls == 2,"bad read or sample rate rejected");
    byte[] fft = new byte[256];
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,1100);
    s = ClassicOriginalMusicAudioSignal.snapshotAt(1101);
    check(first.fftCalls == 1 && s.fresh && s.source.equals("FFT"),"real original FFT");
    ClassicOriginalMusicAudioSignal.observedOriginalMicStopped();
    check(first.closeCalls == 0, "old mic stop cannot reset current FFT DSP");
    check(ClassicOriginalMusicAudioSignal.snapshotAt(1101).fresh,
          "old mic stop cannot revoke FFT");
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,0,1102);
    check(first.fftCalls == 1,"invalid original FFT format ignored");
    ClassicOriginalMusicAudioSignal.observedOriginalVisualizerStopped();
    check(first.closeCalls == 1, "real FFT owner stopped exactly once");
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(1103).fresh,
          "FFT stop cleared the UI frame");
    ClassicOriginalMusicAudioSignal.observedOriginalVisualizerStopped();
    check(first.closeCalls == 1, "duplicate FFT release does not reset twice");
    Fake next = new Fake();
    ClassicOriginalMusicAudioSignal.installAnalyzer(next);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(1103).fresh,
          "new host invalidates old source");
    ClassicOriginalMusicAudioSignal.clearAnalyzer(first);
    check(next.closeCalls == 0, "old owner must not close the new DSP");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1200);
    check(next.pcmCalls == 1,"old host cannot cancel new host");
    ClassicOriginalMusicAudioSignal.observedOriginalMicStopped();
    check(next.closeCalls == 1, "real OEM mic release resets current DSP");
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(1201).fresh,
          "real mic stop revokes the visible source");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1210);
    ClassicOriginalMusicAudioSignal.clearAnalyzer(next);
    check(next.closeCalls == 2, "MusicActivity pause resets current DSP");
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(1211).fresh,
          "real host pause revokes source");
    ClassicOriginalMusicAudioSignal.installAnalyzer(next);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1300);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(2600).fresh,"stale music cannot remain live");
    ClassicOriginalMusicAudioSignal.clearAnalyzer(next);

    // Adversarial OEM MIC/Visualizer interleaving: a stale frame from the
    // OTHER source must not replace current evidence or reach light output.
    ClassicOriginalMusicAudioSignal.resetForTest();
    Fake overlap = new Fake();
    ClassicOriginalMusicAudioSignal.installAnalyzer(overlap);
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,2000);
    check(overlap.acceptedCalls == 1,"fresh FFT admitted after owner install");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1999);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,2000);
    check(overlap.pcmCalls == 2 && overlap.acceptedCalls == 1 &&
          ClassicOriginalMusicAudioSignal.snapshotAt(2000).source.equals("FFT"),
          "older and duplicate cross-source PCM cannot revoke FFT or light");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,2001);
    check(overlap.acceptedCalls == 2 &&
          ClassicOriginalMusicAudioSignal.snapshotAt(2001).source.equals("MIC"),
          "strictly newer MIC becomes the active source");
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,2000);
    check(overlap.acceptedCalls == 2 &&
          ClassicOriginalMusicAudioSignal.snapshotAt(2001).source.equals("MIC"),
          "old FFT cannot reclaim a newer MIC source");
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,2002);
    check(overlap.acceptedCalls == 3 &&
          ClassicOriginalMusicAudioSignal.snapshotAt(2002).source.equals("FFT"),
          "newer FFT may legitimately take over");
    overlap.throwOnAccepted = true;
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,2003);
    check(overlap.acceptedCalls == 4 &&
          ClassicOriginalMusicAudioSignal.snapshotAt(2003).source.equals("MIC"),
          "failing optional observer cannot interrupt OEM capture or receipt");
    ClassicOriginalMusicAudioSignal.clearAnalyzer(overlap);
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,2004);
    check(overlap.acceptedCalls == 4,"revoked owner cannot emit another frame");

    // A DSP result already executing when the OEM stops/release its capture
    // cannot re-create fresh evidence or emit a late light frame.
    for (String lane : new String[]{"MIC","FFT"}) {
      ClassicOriginalMusicAudioSignal.resetForTest();
      Fake racing = new Fake();
      ClassicOriginalMusicAudioSignal.installAnalyzer(racing);
      if ("MIC".equals(lane))
        ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,3000);
      else
        ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,3000);
      check(racing.acceptedCalls == 1,"initial "+lane+" receipt");
      racing.entered = new CountDownLatch(1);
      racing.resume = new CountDownLatch(1);
      Thread worker = new Thread(() -> {
        if ("MIC".equals(lane))
          ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,3010);
        else
          ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,3010);
      });
      worker.start();
      check(racing.entered.await(5,TimeUnit.SECONDS),lane+" callback in flight");
      if ("MIC".equals(lane))
        ClassicOriginalMusicAudioSignal.observedOriginalMicStopped();
      else
        ClassicOriginalMusicAudioSignal.observedOriginalVisualizerStopped();
      racing.resume.countDown();
      worker.join(5000);
      check(!worker.isAlive(),lane+" worker completed");
      check(racing.acceptedCalls == 1 &&
            !ClassicOriginalMusicAudioSignal.snapshotAt(3011).fresh,
            lane+" post-stop in-flight callback cannot resurrect UI or lights");
      racing.entered = null;
      racing.resume = null;
      ClassicOriginalMusicAudioSignal.clearAnalyzer(racing);
    }

    // Actual bridge: ignored return shape, partial read, offset and failure.
    ClassicOriginalMusicAudioSignal.resetForTest();
    Fake tap = new Fake();
    ClassicOriginalMusicAudioSignal.installAnalyzer(tap);
    android.media.AudioRecord reader = new android.media.AudioRecord();
    short[] window = new short[]{91,92,100,101,102,93,94};
    reader.result = 3;
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==3 &&
          reader.readCalls==1 && reader.lastBuffer==window &&
          reader.lastOffset==2 && reader.lastSize==4 &&
          tap.pcmCalls==1 && tap.lastValid==3 && tap.firstSample==100,
          "exact arguments, partial nonzero-offset read and returned count");
    for (int bad : new int[]{0,-3,5,8}) {
      reader.result=bad;
      check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==bad,
            "failed or out-of-range OEM result is unchanged");
    }
    check(tap.pcmCalls==1,"invalid reads do not reach DSP");
    reader.result=3;
    reader.rateFailure=true;
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==3 &&
          tap.pcmCalls==1,"optional rate failure preserves result");
    reader.rateFailure=false;
    tap.throwOnPcm=true;
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==3 &&
          tap.pcmCalls==2,"optional DSP failure preserves result");
    tap.throwOnPcm=false;
    RuntimeException originalError=new IllegalStateException("original read failure");
    reader.failure=originalError;
    try {
      ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4);
      throw new AssertionError("original exception swallowed");
    } catch (RuntimeException e) { check(e==originalError,"original exception propagated"); }
    reader.failure=null;
    reader.beforeReturn=() -> ClassicOriginalMusicAudioSignal.observedOriginalMicStopped();
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==3 &&
          tap.pcmCalls==2,"stop during original read fences late DSP");
    reader.beforeReturn=null;
    ClassicOriginalMusicAudioSignal.clearAnalyzer(tap);
    int prior=reader.readCalls;
    check(ClassicOriginalMusicAudioSignal.readOriginalMicAndObserve(reader,window,2,4)==3 &&
          reader.readCalls==prior+1 && tap.pcmCalls==2,"no owner still executes one original read");
    System.out.println("REAL_ORIGINAL_AUDIO_SIGNAL_JAVA_PRODUCTION_PASS");
  }
}
"""

class OriginalAudioSourceContract(unittest.TestCase):
    def test_pinned_oem_mic_read_and_stop(self):
        changed = hook.patch_mic(MIC)
        self.assertIn("invoke-static {v5, v6, v0, v1}, " + hook.READ_TAP, changed)
        self.assertIn("invoke-static {}, " + hook.MIC_STOP, changed)
        self.assertEqual(changed.count(hook.PCM_READ), 0)
        self.assertEqual(changed.count(hook.READ_TAP), 1)
        self.assertEqual(changed.count(hook.PCM_STOP), 1)
        self.assertIn(".locals 22", changed)
        restored = changed.replace("invoke-static {v5, v6, v0, v1}, " + hook.READ_TAP,
                                   "invoke-virtual {v5, v6, v0, v1}, " + hook.PCM_READ)
        restored = restored.replace("\n    invoke-static {}, " + hook.MIC_STOP, "")
        self.assertEqual(restored, MIC)
        with self.assertRaises(GateFailure):
            hook.patch_mic(changed)

    def test_owner_observed_discarded_mic_result(self):
        original = MIC.replace("    move-result v16\n", "")
        changed = hook.patch_mic(original)
        self.assertNotIn("move-result", changed)
        self.assertIn("invoke-static {v5, v6, v0, v1}, " + hook.READ_TAP, changed)
        restored = changed.replace("invoke-static {v5, v6, v0, v1}, " + hook.READ_TAP,
                                   "invoke-virtual {v5, v6, v0, v1}, " + hook.PCM_READ)
        restored = restored.replace("\n    invoke-static {}, " + hook.MIC_STOP, "")
        self.assertEqual(restored, original)

    def test_range_original_registers_supported(self):
        s = MIC.replace("invoke-virtual {v5, v6, v0, v1}",
                        "invoke-virtual/range {v17 .. v20}")
        changed = hook.patch_mic(s)
        self.assertIn("invoke-static/range {v17 .. v20}, " + hook.READ_TAP, changed)
        self.assertIn("move-result v16", changed)

    def test_mic_fail_closed_against_unknown_oem(self):
        corruptions = [
            MIC.replace("Ld4/y;", "Ld4/q;"),
            MIC.replace("run()V", "start()V"),
            MIC.replace("->read([SII)I", "->read([BII)I"),
            MIC.replace("move-result v16", "move-result-object v16"),
            MIC.replace("->stop()V", "->shutdown()V"),
            MIC.replace("    return-void", "    invoke-virtual {v5, v6, v0, v1}, "
                        + hook.PCM_READ + "\n    move-result v2\n    return-void"),
            MIC.replace("{v5, v6, v0, v1}", "{v5, v6, v0}"),
            MIC.replace("invoke-virtual {v5, v6, v0, v1}", "invoke-virtual/range {v5, v6, v0, v1}"),
            MIC.replace("invoke-virtual {v5, v6, v0, v1}", "invoke-static {v5, v6, v0, v1}"),
        ]
        for defect in corruptions:
            with self.subTest(case=defect[:55]), self.assertRaises(GateFailure):
                hook.patch_mic(defect)

    def test_fft_callback_and_worker_both_preserved(self):
        callback, worker = hook.patch_fft(FFT, WORKER)
        self.assertEqual(callback.count(hook.FFT), 1)
        self.assertIn("invoke-static/range {p2 .. p3}, " + hook.FFT, callback)
        self.assertEqual(worker.count(hook.FFT_STOP), 2)
        self.assertEqual(worker.count(hook.FFT_RELEASE), 2)
        self.assertEqual(callback.count(hook.FFT_FORWARD), 1)
        self.assertIn("onWaveFormDataCapture", callback)
        with self.assertRaises(GateFailure):
            hook.patch_fft(callback, worker)

    def test_fft_rejects_drift_and_lifecycle_loss(self):
        cases = [
            (FFT.replace("Ld4/z$a;", "Ld4/z$b;"), WORKER),
            (FFT.replace("onFftDataCapture", "onWaveformDataCapture"), WORKER),
            (FFT.replace("->b([BI)V", "->c([BI)V"), WORKER),
            (FFT, WORKER.replace("Ld4/z;", "Ld4/q;")),
            (FFT, WORKER.replace("->release()V", "->close()V", 1)),
            (FFT, WORKER.replace("    return-void", "    invoke-virtual {v0}, " +
                hook.FFT_RELEASE + "\n    return-void", 1))
        ]
        for callback,worker in cases:
            with self.subTest(case=callback[:45]), self.assertRaises(GateFailure):
                hook.patch_fft(callback, worker)

    def test_staging_validates_all_files_before_any_write(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for rel,body in [(hook.MIC_FILE,MIC),(hook.FFT_FILE,FFT),
                             (hook.FFT_WORKER_FILE,WORKER)]:
                p=root/rel
                p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text(body,encoding="utf-8")
            worker = root/hook.FFT_WORKER_FILE
            worker.write_text(WORKER.replace("->release()V","->other()V"))
            with self.assertRaises(GateFailure):
                hook.patch_staged_clone(root)
            self.assertEqual((root/hook.MIC_FILE).read_text(),MIC,
                             "fail before editing mic if other OEM file changed")
            worker.write_text(WORKER)
            report=hook.patch_staged_clone(root)
            self.assertEqual(report["original_music_player_or_lamp_writes"],0)
            self.assertEqual(report["secondary_capture_sessions_started"],0)
            self.assertEqual(report["source_owned_original_methods_modified"],3)
            with self.assertRaises(GateFailure):
                hook.patch_staged_clone(root)

    def test_actual_audio_receipt_precedes_native_output_dispatch(self):
        java=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"/
              "ClassicOriginalMusicAudioSignal.java").read_text()
        dsp=(ROOT/"bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime"/
             "ClassicOriginalDspAnalysis.kt").read_text()
        self.assertIn("expected.onAcceptedFrame(frame, nowMs)",java)
        self.assertIn("nowMs <= observedAtMs",java,
                      "source changes cannot bypass global monotonic receipt")
        self.assertIn("micCaptureEpoch = advanceEpoch(micCaptureEpoch)",java)
        self.assertIn("fftCaptureEpoch = advanceEpoch(fftCaptureEpoch)",java)
        self.assertIn("receiptEpoch !=",java)
        self.assertIn("override fun onAcceptedFrame(",dsp)
        self.assertEqual(dsp.count("frameObserver?.onFrame(frame, timestampMs)"),1)
        self.assertNotIn("return publish(",dsp)
        self.assertIn("onAcceptedFrame",dsp)
        # The existing original DSP never starts another capture or writer.
        self.assertNotIn("new AudioRecord(",dsp)
        self.assertNotIn("set_music(",dsp)

    def test_real_builder_and_primary_music_wiring_source(self):
        build=(ROOT/"build_private_unsigned.py").read_text()
        inline=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"/
                "ClassicMusicInlineControls.java").read_text()
        preview=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"/
                 "ClassicDjPatternPreviewView.java").read_text()
        service=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"/
                 "ClassicDjService.java").read_text()
        self.assertIn("patch_original_audio(staged)",build)
        self.assertIn("smali_report['original_audio_witness']",build)
        self.assertIn("ClassicOriginalMusicAudioSignal.snapshot()",inline)
        self.assertIn("preview.setOriginalFrame(",inline)
        self.assertIn("ClassicOriginalMusicAudioSignal.Snapshot",preview)
        for bad in ("MediaProjection","requestPermissions(","startService(","new Intent(",
                    "set_music","set_power","new AudioRecord","new Visualizer"):
            self.assertNotIn(bad,inline)
            self.assertNotIn(bad,preview)
        self.assertIn("if (!ips.isEmpty())",service)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),"real JDK needed")
    def test_real_java_oem_audio_sample_lifecycle(self):
        src=ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"/"ClassicOriginalMusicAudioSignal.java"
        with tempfile.TemporaryDirectory() as d:
            target=Path(d)/"OriginalMusicAudioHarness.java"
            target.write_text(JAVA_HARNESS,encoding="utf-8")
            stub=Path(d)/"AudioRecord.java"
            stub.write_text("""package android.media;
public class AudioRecord {
  public int result=1024, readCalls, lastOffset, lastSize;
  public short[] lastBuffer;
  public boolean rateFailure;
  public RuntimeException failure;
  public Runnable beforeReturn;
  public int read(short[] data,int offset,int size) {
    readCalls++; lastBuffer=data; lastOffset=offset; lastSize=size;
    if(failure!=null) throw failure;
    if(beforeReturn!=null) beforeReturn.run();
    return result;
  }
  public int getSampleRate() {
    if(rateFailure) throw new IllegalStateException("rate");
    return 16000;
  }
}""", encoding="utf-8")
            c=subprocess.run(["javac","--release","8","-d",d,str(src),str(target),str(stub)],
                capture_output=True,text=True,timeout=30)
            self.assertEqual(c.returncode,0,c.stderr)
            r=subprocess.run(["java","-cp",d,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalMusicAudioHarness"],
                capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn("REAL_ORIGINAL_AUDIO_SIGNAL_JAVA_PRODUCTION_PASS",r.stdout)

if __name__=="__main__":
    unittest.main()
