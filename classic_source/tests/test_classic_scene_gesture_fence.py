"""Executable JDK8 regression for the genuine production SceneFragment gesture fence.

Positive and adversarial UI-action tickets; no OEM bytes, network, private
preferences, accounts or device writes. Source wiring is verified separately.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
public final class SceneGestureFenceHarness {
  static void ok(boolean yes,String why){if(!yes)throw new AssertionError(why);}
  static List<String> order(){return new ArrayList<>(
    Arrays.asList(ClassicSceneUiState.SCENES));}
  static ClassicSceneUiState.Selection favorite(int scene,int color,int palette){
    return new ClassicSceneUiState.Selection(
      scene,2,false,2,0,1,true,2,2,color,palette);
  }
  public static void main(String[] args){
    Object host = new Object();
    Object recreated = new Object();
    List<String> visible = order();
    long revision = ClassicSceneUiState.revision();
    ClassicSceneUiState.Selection saved=favorite(4,7,1);
    ClassicSceneGestureFence.Receipt initial =
      ClassicSceneGestureFence.capture(host,revision,visible,saved);
    ok(initial != null,"valid original scene host");
    ok(initial.current(host,revision,order()),"same original host and reorder");
    ok(initial.sameFavorite(favorite(4,7,1)),"equivalent saved profile");
    ok(!initial.current(recreated,revision,order()),
      "recreated fragment cannot use old action");
    ok(!initial.current(null,revision,order()),
      "detached host cannot complete async dialog");
    ok(!initial.current(host,revision+1,order()),
      "another original MusicActivity profile edit revokes action");
    visible.set(0,"WAVE");
    visible.set(6,"SPECTRUM");
    ok(!initial.current(host,revision,visible),"effect order changed elsewhere");
    ok(initial.current(host,revision,order()),
      "negative order probe does not consume a valid action");
    for(int i=0;i<11;i++){
      ClassicSceneUiState.Selection changed;
      switch(i){
       case 0: changed=favorite(5,7,1);break;
       case 1: changed=new ClassicSceneUiState.Selection(4,3,false,2,0,1,true,2,2,7,1);break;
       case 2: changed=new ClassicSceneUiState.Selection(4,2,true,2,0,1,true,2,2,7,1);break;
       case 3: changed=new ClassicSceneUiState.Selection(4,2,false,3,0,1,true,2,2,7,1);break;
       case 4: changed=new ClassicSceneUiState.Selection(4,2,false,2,1,1,true,2,2,7,1);break;
       case 5: changed=new ClassicSceneUiState.Selection(4,2,false,2,0,2,true,2,2,7,1);break;
       case 6: changed=new ClassicSceneUiState.Selection(4,2,false,2,0,1,false,2,2,7,1);break;
       case 7: changed=new ClassicSceneUiState.Selection(4,2,false,2,0,1,true,3,2,7,1);break;
       case 8: changed=new ClassicSceneUiState.Selection(4,2,false,2,0,1,true,2,1,7,1);break;
       case 9: changed=favorite(4,8,1);break;
       default: changed=favorite(4,7,0);break;
      }
      ok(!initial.sameFavorite(changed),"changed favorite field "+i);
    }
    ok(!initial.sameFavorite(null),"favorite deleted while dialog open");
    ClassicSceneGestureFence.Receipt empty =
      ClassicSceneGestureFence.capture(host,revision,order(),null);
    ok(empty != null && empty.sameFavorite(null),
      "empty favorite remains empty at confirmation");
    ok(!empty.sameFavorite(saved),"late replacement of empty slot denied");
    ok(ClassicSceneGestureFence.capture(null,revision,order(),saved)==null,
      "no host cannot begin");
    ok(ClassicSceneGestureFence.capture(host,-1,order(),saved)==null,
      "invalid revision");
    ok(ClassicSceneGestureFence.capture(host,revision,
      Collections.singletonList("SCANNER"),saved)==null,"partial order rejected");
    List<String> duplicate=order();duplicate.set(0,"WAVE");
    ok(ClassicSceneGestureFence.capture(host,revision,duplicate,saved)==null,
      "duplicate stable scene ID rejected");
    List<String> foreign=order();foreign.set(0,"OEM_PRIVATE_SCENE");
    ok(ClassicSceneGestureFence.capture(host,revision,foreign,saved)==null,
      "OEM foreign scene is not a DJ effect");
    // The real SceneFragment and MusicActivity share one revisioned process state.
    ok(ClassicSceneUiState.updateColor(8),"explicit color choice");
    ok(!initial.current(host,ClassicSceneUiState.revision(),order()),
      "old color confirmation cannot overwrite new profile");
    ClassicSceneGestureFence.Receipt fresh =
      ClassicSceneGestureFence.capture(recreated,ClassicSceneUiState.revision(),
        order(),favorite(4,8,1));
    ok(fresh!=null && fresh.current(recreated,
      ClassicSceneUiState.revision(),order()),"new host can start new action");
    System.out.println("SCENE_GESTURE_PRODUCTION_PASS");
  }
}
"""

class SceneGestureFenceProductionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK unavailable")
    def test_production_receipts_and_stale_callback_denials(self):
        with tempfile.TemporaryDirectory(prefix="classic-scenes-fence-") as temp:
            base=Path(temp)
            source=base/"SceneGestureFenceHarness.java"
            source.write_text(HARNESS,encoding="utf-8")
            java=[UI/"ClassicSceneUiState.java",
                  UI/"ClassicSceneGestureFence.java",source]
            compile=subprocess.run(["javac","--release","8","-d",temp,
                                    *[str(x) for x in java]],
                                   capture_output=True,text=True,timeout=35)
            self.assertEqual(compile.returncode,0,compile.stderr)
            run=subprocess.run(["java","-cp",temp,
                  "io.github.lxsdd.yeelightclassicdj.ui.SceneGestureFenceHarness"],
                  capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("SCENE_GESTURE_PRODUCTION_PASS",run.stdout)

    def test_original_scene_and_music_activity_wiring(self):
        scene=(UI/"ClassicSceneGalleryPanel.java").read_text()
        music=(UI/"ClassicMusicInlineControls.java").read_text()
        order=(UI/"ClassicEffectOrder.java").read_text()
        for line in ("captureAction(previous)",
                     "currentAction(receipt)",
                     "receipt.sameFavorite(effectSettings.readFavorite(slot))",
                     "effectSettings.saveFavorite(slot, selected)",
                     "dragReceipt = captureAction(null)",
                     "dragReceipt = null",
                     "!currentAction(dragReceipt)",
                     "if (!ownsHost()) return;"):
            self.assertIn(line,scene)
        self.assertIn("effectOrder.persistedOrder()",scene)
        self.assertIn("store.read(ClassicOrderPolicy.Surface.SCENE_LIST, SCOPE)",order)
        self.assertIn("if (!attached || index < 0",music)
        self.assertIn("if (!attached || !effectSettings.selectFavorite(slot))",music)
        for forbidden in ("set_power(", "set_music(", "HttpURLConnection",
                          "ContentResolver", "SQLiteDatabase", "sendBroadcast("):
            self.assertNotIn(forbidden,(UI/"ClassicSceneGestureFence.java").read_text())

if __name__=="__main__":
    unittest.main()
