"""Actual original-Classic preview math: full dynamic cohort and honest provenance.

Executable production Java8 math/provenance, without OEM, hardware, network,
accounts or any file/device write. Rendering is separately SDK-typechecked.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
MATH = UI / "ClassicDjPatternMath.java"
COHORT = UI / "ClassicDjPreviewCohort.java"
VIEW = UI / "ClassicDjPatternPreviewView.java"
INLINE = UI / "ClassicMusicInlineControls.java"

HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
public final class OriginalFleetPreviewHarness {
  static void ok(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    float[] bands={.55f,.40f,.25f,.12f};
    for (int count:new int[]{1,8,14,15,32,64,128,512}) {
      for (int mode=0;mode<7;mode++) for(int position=0;position<count;position++)
        for(long ms:new long[]{0L,210L,1650L,10000L}) {
          float v=ClassicDjPatternMath.previewLevel(
              mode,position,count,ms,true,120,100,false,4);
          ok(Float.isFinite(v) && v>=0f && v<=1f,
              "dynamic preview bounded for "+count+" members");
          float measured=ClassicDjPatternMath.reactiveLevel(
              mode,position,count,v,true,true,.6f,bands,false);
          ok(Float.isFinite(measured) && measured>=0f && measured<=1f,
              "all seven modes consume only valid full-cohort frames");
        }
      long last=(count-1L)*210L;
      ok(ClassicDjPatternMath.previewLevel(
          4,0,count,0,true,120,100,false,4)>0.99f,"scanner starts left");
      ok(ClassicDjPatternMath.previewLevel(
          4,count-1,count,last,true,120,100,false,4)>0.99f,
          "scanner reaches LAST of "+count+" members");
      ok(ClassicDjPatternMath.previewLevel(
          4,count-1,count,0,true,120,100,true,4)>0.99f,
          "reverse scanner starts at end");
      ClassicDjPreviewCohort.Snapshot selected=
          ClassicDjPreviewCohort.from(count,0,false);
      ok(selected.cells==count &&
         selected.proof==ClassicDjPreviewCohort.Proof.SELECTED &&
         selected.label().contains("vorgemerkte") &&
         !selected.label().contains("bestätigte Music"),
         "unconfirmed group never presented as owned");
      ClassicDjPreviewCohort.Snapshot pending=
          ClassicDjPreviewCohort.from(count,count,false);
      ok(pending.cells==count &&
         pending.proof==ClassicDjPreviewCohort.Proof.SELECTED,
         "Music start handshake is still unconfirmed");
      ClassicDjPreviewCohort.Snapshot owned=
          ClassicDjPreviewCohort.from(count,count,true);
      ok(owned.cells==count &&
         owned.proof==ClassicDjPreviewCohort.Proof.OWNED &&
         owned.label().contains("bestätigte Music-Verbindungen") &&
         owned.label().contains("kein gemessener Lampenzustand"),
         "exact Music ACK does not claim actual physical colour");
    }
    ok(ClassicDjPatternMath.previewLevel(
        4,13,14,2730,true,120,100,false,4)>0.99f,
        "fourteenth position not truncated to eight");
    ok(ClassicDjPatternMath.previewLevel(
        5,13,14,2145,true,120,100,false,4)>0.99f,
        "meteor reaches final member in a 14-cohort");
    ok(ClassicDjPatternMath.previewLevel(
        4,14,14,0,true,120,100,false,4)==0f,
        "no drawing outside verified list");
    ok(ClassicDjPatternMath.previewLevel(
        4,0,0,0,true,120,100,false,4)==0f &&
       ClassicDjPatternMath.previewLevel(
        4,-1,14,0,true,120,100,false,4)==0f &&
       ClassicDjPatternMath.previewLevel(
        4,0,14,-1,true,120,100,false,4)==0f,
        "invalid cohort/index/time blocked");
    ok(ClassicDjPatternMath.previewLevel(
        4,0,14,0,true,120,0,false,4)==0f &&
       ClassicDjPatternMath.previewLevel(
        4,0,14,0,true,120,100,false,0)==0f,
        "unverified speed or trail blocked");
    ok(ClassicDjPreviewCohort.from(0,0,true).proof==
        ClassicDjPreviewCohort.Proof.PLACEHOLDER &&
       ClassicDjPreviewCohort.from(-1,-1,false).cells==8 &&
       ClassicDjPreviewCohort.from(0,0,true).label().contains("Platzhalter"),
        "no selected devices means honest schematic preview");
    ok(ClassicDjPatternMath.reactiveLevel(
        0,13,14,.7f,true,false,0f,bands,false)==0f &&
       ClassicDjPatternMath.reactiveLevel(
        1,13,14,.7f,true,false,0f,bands,false)==0f &&
       ClassicDjPatternMath.reactiveLevel(
        2,13,14,.7f,true,false,0f,bands,false)==0f &&
       ClassicDjPatternMath.reactiveLevel(
        3,13,14,.7f,true,false,0f,bands,false)==0f,
        "no imaginary audio/spectrum/beats on fourteen cells");
    ok(ClassicDjPatternMath.reactiveLevel(
        4,13,14,.7f,true,false,0f,bands,false)==.7f &&
       ClassicDjPatternMath.reactiveLevel(
        3,13,14,.7f,false,false,0f,bands,false)==.7f,
        "explicitly time-driven effects remain available without capture");
    System.out.println("CLASSIC_DYNAMIC_ORIGINAL_PREVIEW_JAVA_PASS");
  }
}
"""

class DynamicOriginalPreviewTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK8 required")
    def test_production_math_and_provenance_for_all_selected_members(self):
        with tempfile.TemporaryDirectory(prefix="classic-original-preview-") as tmp:
            test = Path(tmp)/"OriginalFleetPreviewHarness.java"
            test.write_text(HARNESS, encoding="utf-8")
            build = subprocess.run(
                ["javac","--release","8","-d",tmp,
                 str(MATH),str(COHORT),str(test)],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(build.returncode,0,build.stderr)
            executed = subprocess.run(
                ["java","-cp",tmp,
                 "io.github.lxsdd.yeelightclassicdj.ui.OriginalFleetPreviewHarness"],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(executed.returncode,0,executed.stderr)
            self.assertIn("CLASSIC_DYNAMIC_ORIGINAL_PREVIEW_JAVA_PASS",executed.stdout)

    def test_original_music_view_consumes_actual_count_without_oem_writes(self):
        view=VIEW.read_text(encoding="utf-8")
        inline=INLINE.read_text(encoding="utf-8")
        for required in (
            "preview.setCohort(previewCohort)",
            "ClassicDjPreviewCohort.from(marked",
            "runningOutput && lightConfirmed",
            "ClassicSceneUiState.TRAILS[selected.trailIndex]",
            "previewCohortStatus.setText(previewCohort.label())",
            "schematische Reihenfolge, kein Grundriss",
        ):
            self.assertIn(required,inline)
        for required in (
            "int count = cohort.cells;",
            "for (int i = 0; i < count; i++)",
            "ClassicDjPatternMath.previewLevel(mode, i, count, clock",
            "ClassicDjPatternMath.reactiveLevel(mode, i, count,",
            "rainbowRgb(i, count, clock)",
        ):
            self.assertIn(required,view)
        for forbidden in ("set_music(", "set_power(", "Socket(",
                          "getRemoteDeviceList(", "new AudioRecord("):
            self.assertNotIn(forbidden,view)
            self.assertNotIn(forbidden,inline)

if __name__ == "__main__":
    unittest.main()
