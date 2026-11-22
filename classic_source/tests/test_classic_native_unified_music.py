"""Execute actual production Java effect math: no fake beat, safe movement.

No original APK, Android runtime, device input or cloud. Native Android layout
is validated in the separate SDK bridge build and Pixel owner acceptance.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
MATH = UI / "ClassicDjPatternMath.java"
INLINE = (UI / "ClassicMusicInlineControls.java").read_text("utf-8")
VIEW = (UI / "ClassicDjPatternPreviewView.java").read_text("utf-8")
HARNESS = r'''
package io.github.lxsdd.yeelightclassicdj.ui;
public class UnifiedPreviewHarness {
 static void yes(boolean v,String msg) {if(!v)throw new AssertionError(msg);}
 static float level(int s,int p,long t,boolean beat,int bpm) {
   return ClassicDjPatternMath.level(s,p,t,beat,bpm);
 }
 public static void main(String[] ignored) {
   for(int scene=0;scene<7;scene++)for(int i=0;i<8;i++)
    for(long time:new long[]{0,170,340,680,1400,10000}) {
      float v=level(scene,i,time,true,120);
      yes(Float.isFinite(v)&&v>=0f&&v<=1f,"bounded preview");
    }
   for(int i=0;i<8;i++) {
     yes(level(2,i,0,true,120)==level(2,i,1200,true,120),
         "No PCM cannot invent beat events");
     yes(level(3,i,0,true,120)==level(3,i,1200,true,120),
         "Beat pulse requires measured onset");
   }
   yes(level(4,0,0,true,120)>level(4,7,0,true,120),"scanner begins left");
   yes(level(4,7,1190,true,120)>level(4,0,1190,true,120),"scanner reaches right");
   yes(level(4,0,2380,true,120)>level(4,7,2380,true,120),"scanner returns");
   yes(level(5,2,700,true,120)>level(5,0,700,true,120),
       "meteor head leads fading tail");
   yes(level(5,0,700,true,120)>level(5,7,700,true,120),
       "meteor trail bounded");
   yes(level(6,0,900,true,120)!=level(6,3,900,true,120),
       "wave shifts across abstract positions");
   yes(level(3,0,0,false,120)>level(3,0,300,false,120),
       "timed pulse decays without audio");
   yes(level(3,0,500,false,120)>level(3,0,300,false,120),
       "timed pulse repeats at 120 BPM");
   for(int s=0;s<7;s++) {
     yes(level(s,-1,0,true,120)==0f,"negative index denied");
     yes(level(s,8,0,true,120)==0f,"index outside eight abstract cells denied");
     yes(level(s,0,-1,true,120)==0f,"negative clock denied");
   }
   yes(level(3,0,0,false,0)==0f,"invalid timer BPM denied");
   yes(level(7,0,0,true,120)==0f,"unknown scene denied");
   float[] bands = new float[]{.15f,.70f,.28f,.42f};
   yes(ClassicDjPatternMath.reactiveLevel(0,1,.10f,true,true,.6f,bands,false)==.70f,
       "spectrum uses real observed FFT bands");
   yes(ClassicDjPatternMath.reactiveLevel(0,2,.30f,true,false,0f,bands,false)==.30f,
       "inactive scene cannot claim audio provenance");
   yes(ClassicDjPatternMath.reactiveLevel(2,0,.46f,true,false,0f,bands,false)==0f,
       "beat never fabricated without original source");
   yes(ClassicDjPatternMath.reactiveLevel(2,0,.46f,true,true,.68f,bands,true)==1f,
       "original observed onset reaches beat scene");
   yes(ClassicDjPatternMath.reactiveLevel(3,0,.20f,true,true,.50f,bands,false)==.06f,
       "beat pulse requires real onset");
   yes(ClassicDjPatternMath.reactiveLevel(4,3,1f,false,true,0f,bands,false)<.20f,
       "real mic silence attenuates timed scanner");
   yes(ClassicDjPatternMath.reactiveLevel(6,3,1f,false,true,1f,bands,false)==1f,
       "real audio preserves full wave intensity");
   System.out.println("UNIFIED_PREVIEW_JAVA_PRODUCTION_MATH_PASS");
 }
}
'''


class UnifiedMusicNativeContract(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_real_source_compiled_scene_geometry(self):
        with tempfile.TemporaryDirectory(prefix="classic-unified-ui-") as temp:
            source = Path(temp) / "UnifiedPreviewHarness.java"
            source.write_text(HARNESS)
            args = ["javac", "--release", "8", "-d", temp, str(MATH), str(source)]
            built = subprocess.run(args, capture_output=True, text=True, timeout=35)
            self.assertEqual(built.returncode, 0, built.stderr)
            run = subprocess.run(
                ["java", "-cp", temp,
                 "io.github.lxsdd.yeelightclassicdj.ui.UnifiedPreviewHarness"],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("UNIFIED_PREVIEW_JAVA_PRODUCTION_MATH_PASS", run.stdout)

    def test_android_view_consumes_only_pure_preview_math(self):
        self.assertIn("ClassicDjPatternMath.level(mode, i, clock", VIEW)
        self.assertIn("ClassicDjPatternMath.reactiveLevel(mode, i, timed", VIEW)
        self.assertIn("ClassicOriginalMusicAudioSignal.snapshot()", INLINE)
        self.assertIn("ClassicSceneUiState.snapshot()", INLINE)
        self.assertIn("ClassicOriginalMusicSelection.originalGroupMemberCount()", INLINE)
        self.assertNotIn("AudioRecord(", VIEW)
        self.assertNotIn("new Intent(", INLINE)
        self.assertNotIn("host.startService(", INLINE)
        self.assertNotIn("set_music", VIEW)


if __name__ == "__main__":
    unittest.main()
