"""Executable Java8 regression for honest PCM telemetry & four effect previews.

No OEM, Android device, audio recordings or LAN. Uses real production Java.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SRC=Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
MODEL=SRC/"ClassicDjLiveFrame.java"
EFFECT=SRC/"ClassicDjEffectPreview.java"

HARNESS=r'''package io.github.lxsdd.yeelightclassicdj.ui;
class ClassicDjLiveHarness {
 static void require(boolean x,String s){if(!x)throw new AssertionError(s);}
 static ClassicDjLiveFrame live(long beatAge) {
   return new ClassicDjLiveFrame(true,true,42,50,beatAge,120,0.56f,
                                 0.7f,0.3f,0.15f,0.05f,0.85f);
 }
 public static void main(String[] unused) {
   ClassicDjLiveFrame none=ClassicDjLiveFrame.none();
   require(!none.fresh(),"no unknown active session");
   require(none.summary().contains("Keine eigene"),"inactive label");
   ClassicDjLiveFrame waiting=new ClassicDjLiveFrame(true,false,0,Long.MAX_VALUE,
     Long.MAX_VALUE,0,0,0,0,0,0,0);
   require(!waiting.fresh() && waiting.summary().contains("wartet"),"no false capture success");
   ClassicDjLiveFrame silent=new ClassicDjLiveFrame(true,true,10,100,
     Long.MAX_VALUE,0,0,0,0,0,0,0);
   require(silent.fresh() && !silent.audible() && silent.summary().contains("Stille"),
     "silent source must be explicit");
   ClassicDjLiveFrame old=new ClassicDjLiveFrame(true,true,50,2300,10,
     118,0.9f,1,1,1,1,1);
   require(!old.fresh() && old.summary().contains("Keine aktuellen"),
      "stale PCM fails closed");
   ClassicDjLiveFrame negative=new ClassicDjLiveFrame(true,false,2,-1,-1,
     Double.NaN,Float.NaN,2f,-1f,Float.POSITIVE_INFINITY,0.5f,Float.NaN);
   require(negative.bpm==0 && negative.bass==1 && negative.mid==0
        && negative.treble==0.5f && negative.confidence==0,
      "NaN, infinite and out-of-bounds telemetry sanitized");
   ClassicDjLiveFrame actual=live(60);
   require(actual.fresh() && actual.audible() && actual.recentBeat(),"real PCM accepted");
   require(actual.summary().contains("120 BPM") &&
           actual.summary().contains("85%"),"real BPM confidence label");
   float[] mutable=actual.bands();mutable[0]=0f;
   require(actual.bands()[0]==0.7f,"read-only copy of measured frequency bands");
   for(String scene:new String[]{"SPECTRUM","AMBIENT","BEAT","PULSE"}) {
     require(ClassicDjEffectPreview.explanation(scene,true).length()>10,
       "clear mode-specific semantics");
   }
   int spec=ClassicDjEffectPreview.intensity("SPECTRUM",actual,true,120,300);
   int amb=ClassicDjEffectPreview.intensity("AMBIENT",actual,true,120,300);
   int beat=ClassicDjEffectPreview.intensity("BEAT",actual,true,120,300);
   int pulse=ClassicDjEffectPreview.intensity("PULSE",actual,true,120,300);
   require(spec==70 && amb<spec && beat==100 && pulse<beat && pulse>0,
     "spectral, ambient, onset and pulse models truly differ");
   require(ClassicDjEffectPreview.intensity("BEAT",live(900),true,120,300)==0,
     "no invented late beat");
   require(ClassicDjEffectPreview.intensity("PULSE",live(900),true,120,300)>0,
     "pulse envelope distinct from beat flash");
   int timer=ClassicDjEffectPreview.intensity("PULSE",live(Long.MAX_VALUE),
                                               false,120,300);
   require(timer>0,"timer pulse is independent of beat");
   require(ClassicDjEffectPreview.intensity("PULSE",live(Long.MAX_VALUE),
                                             false,0,300)==0,
     "invalid time BPM rejected");
   require(ClassicDjEffectPreview.intensity("SPECTRUM",old,true,120,300)==0,
     "stale source does not animate");
   require(ClassicDjEffectPreview.intensity("SPECTRUM",none,true,120,300)==0,
     "foreign source does not animate");
   require(ClassicDjEffectPreview.intensity("UNSUPPORTED",actual,true,120,300)==0,
     "unsupported effects fail closed");
   System.out.println("PASS real-pcm-band-meter-model source freshness, silence, ownership and four distinct effect previews");
 }
}'''
class ClassicDjLiveMeterTest(unittest.TestCase):
 @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK missing")
 def test_real_java_live_model_and_effect_preview(self):
  with tempfile.TemporaryDirectory(prefix="classic-live-ui-") as tmp:
   folder=Path(tmp); source=folder/"ClassicDjLiveHarness.java"; source.write_text(HARNESS,encoding="utf-8")
   run=subprocess.run(["javac","--release","8","-d",str(folder),
       str(MODEL),str(EFFECT),str(source)],text=True,capture_output=True,timeout=35)
   self.assertEqual(run.returncode,0,run.stderr)
   runtime=subprocess.run(["java","-cp",str(folder),
       "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjLiveHarness"],
       text=True,capture_output=True,timeout=20)
   self.assertEqual(runtime.returncode,0,runtime.stderr)
   self.assertIn("PASS real-pcm-band-meter-model",runtime.stdout)

 def test_production_wiring_has_no_fake_bands_or_device_actions(self):
  kotlin=(Path(__file__).resolve().parents[1] /
    "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text()
  engine=(Path(__file__).resolve().parents[1] /
    "checkpoint/src/ClassicDjEngine.kt").read_text()
  ui=(SRC/"ClassicMusicInlineControls.java").read_text()
  service=(SRC/"ClassicDjService.java").read_text()
  self.assertIn("result.bass, result.mid, result.presence, result.treble",engine)
  self.assertIn("current?.onSpectrum(output.bass.toFloat()",kotlin)
  self.assertIn("ClassicDjService.frameForOwner(sessionOwner)",ui)
  self.assertIn("sessionFence.owns(token)",service)
  self.assertIn("uiOwner.owns(owner)",service)
  for forbidden in ("set_power","set_music","Socket(","http://","Math.random()"):
   self.assertNotIn(forbidden,ui)
  self.assertNotIn("set_power",kotlin)

if __name__=="__main__":
 unittest.main()
