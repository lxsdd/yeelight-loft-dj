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
  static void check(boolean b, String label) { if(!b) throw new AssertionError(label); }
  static short[] pcm(int n, short value) {
    short[] x = new short[n]; java.util.Arrays.fill(x,value); return x;
  }
  public static void main(String[] args) {
    ClassicOriginalMusicAudioSignal.resetForTest();
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(100).fresh,"starts empty");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024, (short)12000),1024,1000);
    ClassicOriginalMusicAudioSignal.Snapshot s =
        ClassicOriginalMusicAudioSignal.snapshotAt(1010);
    check(s.fresh && s.source.equals("MIC"),"actual original mic provenance");
    check(s.level > 0f && s.level <= 1f,"actual RMS bounded");
    check(s.bands[0] > 0f && s.bands[1] == 0f,"No false mic FFT frequency estimate");
    s.bands[0] = 0f;
    check(ClassicOriginalMusicAudioSignal.snapshotAt(1011).bands[0] > 0f,
          "no external mutable state");
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(2260).fresh,"expired mic");
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(999).fresh,"monotonic rollback");
    ClassicOriginalMusicAudioSignal.resetForTest();
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024,(short)13000),1025,4000);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(4000).fresh,"reject read beyond array");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024,(short)13000),0,4000);
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(4000).fresh,"reject zero sample count");
    ClassicOriginalMusicAudioSignal.observedPcmAt(pcm(1024,(short)0),1024,4001);
    s = ClassicOriginalMusicAudioSignal.snapshotAt(4001);
    check(s.fresh && s.level == 0f && !s.beat,"silence is not a beat");
    byte[] fft = new byte[256];
    fft[2] = 90; fft[3] = 90;
    fft[60] = 90; fft[61] = 90;
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,44100000,5000);
    s = ClassicOriginalMusicAudioSignal.snapshotAt(5000);
    check(s.fresh && s.source.equals("FFT"),"actual OEM FFT callback");
    check(s.level > 0f && s.bands[0] > 0f,"FFT bin energy");
    ClassicOriginalMusicAudioSignal.observedOriginalMicStopped();
    check(ClassicOriginalMusicAudioSignal.snapshotAt(5001).fresh,
          "late stopped mic cannot invalidate newer original FFT");
    ClassicOriginalMusicAudioSignal.observedFftAt(fft,0,5002);
    check(ClassicOriginalMusicAudioSignal.snapshotAt(5002).source.equals("FFT"),
          "invalid FFT sample rate cannot overwrite");
    ClassicOriginalMusicAudioSignal.observedOriginalVisualizerStopped();
    check(!ClassicOriginalMusicAudioSignal.snapshotAt(5003).fresh,"source stop revokes FFT");
    ClassicOriginalMusicAudioSignal.resetForTest();
    ClassicOriginalMusicAudioSignal.observedOriginalMicResult(2048);
    ClassicOriginalMusicAudioSignal.observedOriginalMicSamples(new short[0]);
    check(!ClassicOriginalMusicAudioSignal.snapshot().fresh,
          "cannot use out-of-bounds pending original read");
    ClassicOriginalMusicAudioSignal.observedOriginalMicResult(-3);
    ClassicOriginalMusicAudioSignal.observedOriginalMicSamples(pcm(100,(short)3000));
    check(!ClassicOriginalMusicAudioSignal.snapshot().fresh,"negative read ignored");
    System.out.println("REAL_ORIGINAL_AUDIO_SIGNAL_JAVA_PRODUCTION_PASS");
  }
}
"""

class OriginalAudioSourceContract(unittest.TestCase):
    def test_pinned_oem_mic_read_and_stop(self):
        changed = hook.patch_mic(MIC)
        self.assertIn("invoke-static/range {v16 .. v16}, " + hook.COUNT, changed)
        self.assertIn("invoke-static/range {v6 .. v6}, " + hook.SAMPLES, changed)
        self.assertLess(changed.index(hook.COUNT), changed.index(hook.SAMPLES))
        self.assertIn("invoke-static {}, " + hook.MIC_STOP, changed)
        self.assertEqual(changed.count(hook.PCM_READ), 1)
        self.assertEqual(changed.count(hook.PCM_STOP), 1)
        self.assertIn(".locals 22", changed)
        with self.assertRaises(GateFailure):
            hook.patch_mic(changed)

    def test_range_original_registers_supported(self):
        s = MIC.replace("{v5, v6, v0, v1}", "{v17 .. v20}")
        changed = hook.patch_mic(s)
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
            c=subprocess.run(["javac","--release","8","-d",d,str(src),str(target)],
                capture_output=True,text=True,timeout=30)
            self.assertEqual(c.returncode,0,c.stderr)
            r=subprocess.run(["java","-cp",d,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalMusicAudioHarness"],
                capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn("REAL_ORIGINAL_AUDIO_SIGNAL_JAVA_PRODUCTION_PASS",r.stdout)

if __name__=="__main__":
    unittest.main()
