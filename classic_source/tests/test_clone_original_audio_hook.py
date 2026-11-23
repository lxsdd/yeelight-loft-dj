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
public final class OriginalMusicAudioHarness {
  static void check(boolean b, String name) { if (!b) throw new AssertionError(name); }
  static short[] pcm(int n) { short[] d = new short[n]; java.util.Arrays.fill(d,(short)8000); return d; }
  static final class Fake implements ClassicOriginalMusicAudioSignal.Analyzer {
    int pcmCalls, fftCalls, closeCalls;
    public void onCaptureClosed() { closeCalls++; }
    public ClassicOriginalMusicAudioSignal.Frame onPcm(short[] x, int n, int hz, long ms) {
      pcmCalls++;
      check(hz == 16000,"original recorder format");
      return new ClassicOriginalMusicAudioSignal.Frame(.4f,new float[]{.7f,.2f,.1f,.05f},true,120f,.8f);
    }
    public ClassicOriginalMusicAudioSignal.Frame onFft(byte[] x, int hz, long ms) {
      fftCalls++;
      return new ClassicOriginalMusicAudioSignal.Frame(.3f,new float[]{.5f,.2f,.1f,.05f},false,120f,.8f);
    }
  }
  public static void main(String[] args) {
    ClassicOriginalMusicAudioSignal.resetForTest();
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(100).fresh,"starts empty");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,100);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(100).fresh,
          "without owner, no simulated music");
    Fake first = new Fake();
    ClassicOriginalMusicAudioSignal.installAnalyzer(first);
    ClassicOriginalMusicAudioSignal.observedOriginalMicResult(1024);
    ClassicOriginalMusicAudioSignal.observedOriginalMicRate(new android.media.AudioRecord());
    ClassicOriginalMusicAudioSignal.observedOriginalMicSamples(pcm(1024));
    check(first.pcmCalls == 1,"one real original mic callback");
    // The previous callback used a genuine monotonic clock; reset ownership
    // before injecting deterministic synthetic timestamps for source gating.
    ClassicOriginalMusicAudioSignal.installAnalyzer(first);
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024),1024,16000,1000);
    ClassicOriginalMusicAudioSignal.Snapshot s =
        ClassicOriginalMusicAudioSignal.snapshotAt(1010);
    check(s.fresh && s.beat && s.source.equals("MIC"),"mic source and beat");
    check(s.bands[0] == .7f && s.bpm == 120f && s.confidence == .8f,
          "shared DSP reaches original host");
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
    System.out.println("REAL_ORIGINAL_AUDIO_SIGNAL_JAVA_PRODUCTION_PASS");
  }
}
"""

class OriginalAudioSourceContract(unittest.TestCase):
    def test_pinned_oem_mic_read_and_stop(self):
        changed = hook.patch_mic(MIC)
        self.assertIn("invoke-static/range {v16 .. v16}, " + hook.COUNT, changed)
        self.assertIn("invoke-static/range {v6 .. v6}, " + hook.SAMPLES, changed)
        self.assertLess(changed.index(hook.COUNT), changed.index(hook.RATE))
        self.assertLess(changed.index(hook.RATE), changed.index(hook.SAMPLES))
        self.assertIn("invoke-static/range {v5 .. v5}, " + hook.RATE, changed)
        self.assertIn("invoke-static {}, " + hook.MIC_STOP, changed)
        self.assertEqual(changed.count(hook.PCM_READ), 1)
        self.assertEqual(changed.count(hook.PCM_STOP), 1)
        self.assertIn(".locals 22", changed)
        with self.assertRaises(GateFailure):
            hook.patch_mic(changed)

    def test_range_original_registers_supported(self):
        s = MIC.replace("{v5, v6, v0, v1}", "{v17 .. v20}")
        changed = hook.patch_mic(s)
        self.assertIn("invoke-static/range {v17 .. v17}, " + hook.RATE, changed)
        self.assertIn("invoke-static/range {v18 .. v18}, " + hook.SAMPLES, changed)

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
            stub.write_text("package android.media; public class AudioRecord { "
                            "public int getSampleRate() { return 16000; } }", encoding="utf-8")
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
