"""Actual original Classic DJ effect profile persistence: JDK8 production Java."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

UI = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
SOURCES = [UI / (name + ".java") for name in (
    "ClassicSceneUiState", "ClassicEffectSettingsStore")]
CONTEXT = """package android.content;
public class Context {
 public static final int MODE_PRIVATE = 0;
 public String getPackageName(){return "";}
 public SharedPreferences getSharedPreferences(String n,int mode){return null;}
}
"""
PREFS = """package android.content;
public interface SharedPreferences {
 String getString(String k,String v);
 Editor edit();
 interface Editor { Editor putString(String k,String v); boolean commit(); }
}
"""
HARNESS = r"""package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
import android.content.*;
public class ClassicEffectSettingsHarness {
  static void check(boolean b,String message) {if(!b)throw new AssertionError(message);}
  static class Mem implements SharedPreferences {
    Map<String,String> map=new HashMap<>();int commits=0;boolean accept=true;
    public String getString(String key,String otherwise){return map.getOrDefault(key,otherwise);}
    public Editor edit(){return new Editor(){
      Map<String,String> change=new HashMap<>();
      public Editor putString(String k,String v){change.put(k,v);return this;}
      public boolean commit(){if(!accept)return false;map.putAll(change);commits++;return true;}
    };}
  }
  static class Ctx extends Context {
    final Mem mem = new Mem();
    final String packageId;
    Ctx(String id){packageId=id;}
    public String getPackageName(){return packageId;}
    public SharedPreferences getSharedPreferences(String n,int mode){
      check("classic-loft-dj-effect-settings-v1".equals(n) &&
        mode==MODE_PRIVATE,"private effect prefs only");return mem;
    }
  }
  public static void main(String[] args) {
    Ctx ctx=new Ctx("io.lxsdd.yeelightdj");
    ClassicEffectSettingsStore settings=new ClassicEffectSettingsStore(ctx);
    settings.restoreOnFirstHost();
    check(ctx.mem.commits==0,"host attach must never write");
    check(ClassicSceneUiState.snapshot().scene==0,"no fallback source load");
    check(!settings.selectScene(-1) && !settings.selectScene(7),
      "invalid DJ scene rejected");
    check(settings.selectScene(4),"scanner selected by explicit original UI action");
    check(ClassicSceneUiState.snapshot().scene==4,"scanner state matches host");
    check(settings.saveExplicit(ClassicSceneUiState.snapshot()),"save real selection");
    check(ctx.mem.commits==1 && ctx.mem.map.size()==2,"atomic 2-key single edit");
    check(ClassicSceneUiState.update(4, 4, true, 4, 2),"original editor setting");
    check(ClassicSceneUiState.updateColor(7),"original scene colour edit");
    check(settings.saveExplicit(ClassicSceneUiState.snapshot()),"real geometry save");
    check(ctx.mem.commits==2,"second editor event one commit");
    check(settings.selectScene(3),"independent pulse configuration");
    check(ClassicSceneUiState.updateAudio(3,false,4,1),"timer pulse editor");
    check(ClassicSceneUiState.updateColor(2),"independent pulse scene colour");
    check(settings.saveExplicit(ClassicSceneUiState.snapshot()),"save pulse");
    check(settings.selectScene(4),"return to stored scanner");
    ClassicSceneUiState.Selection scanner=ClassicSceneUiState.snapshot();
    check(scanner.speedIndex==4 && scanner.reverse && scanner.trailIndex==4 &&
      scanner.rangeIndex==2 && scanner.scene==4 && scanner.colorIndex==7,
      "scene profile including colour retained");
    check(settings.selectScene(3),"pulse independent of scanner");
    ClassicSceneUiState.Selection pulse=ClassicSceneUiState.snapshot();
    check(!pulse.beatPulse && pulse.pulseTempoIndex==4 && pulse.pulseShapeIndex==1 && pulse.colorIndex==2,
      "pulse mode and envelope retained");
    int count=ctx.mem.commits;
    settings.restoreOnFirstHost();
    new ClassicEffectSettingsStore(ctx).restoreOnFirstHost();
    check(ctx.mem.commits==count && ClassicSceneUiState.snapshot().scene==3,
      "reentrant host restore read only and preserves live user state");
    check(!settings.saveExplicit(new ClassicSceneUiState.Selection(99,0,false,
       0,0,0,false,0,0)), "invalid profile rejects before any write");
    check(ctx.mem.commits==count,"invalid data no write");
    String prefix="dj-effect.";
    ctx.mem.map.put(prefix+"SCANNER","v4,4,4,1,4,2,1,1,2,2,7,0");
    check(settings.readProfile(4)==null,"wrong version ignored");
    check(settings.selectScene(4),"switch to scanner before explicit palette edit");
    check(ClassicSceneUiState.updatePalette(1),"rainbow palette from original scene editor");
    check(settings.saveExplicit(ClassicSceneUiState.snapshot()),"rainbow profile persisted");
    check(settings.readProfile(4).paletteIndex==1,"rainbow profile round trip");
    check(!ClassicSceneUiState.updatePalette(-1) &&
          !ClassicSceneUiState.updatePalette(2),"invalid palettes rejected");
    ctx.mem.map.put(prefix+"SCANNER","v3,4,4,1,4,2,1,1,2,2,7,4");
    check(settings.readProfile(4)==null,"invalid rainbow profile rejected");
    check(settings.selectScene(4),"invalid stored palette safely resets to solid");
    check(ClassicSceneUiState.snapshot().paletteIndex==0 &&
          ClassicSceneUiState.snapshot().speedIndex==2,
          "malformed v3 cannot change original scene state");
    ctx.mem.map.put(prefix+"SCANNER","v3,4,4,1,4,2,1,1,2,2,7,1");
    check(settings.readProfile(4).paletteIndex==1,"valid rainbow profile restored");
    check(settings.selectScene(4),"valid rainbow profile chosen");
    check(ClassicSceneUiState.snapshot().paletteIndex==1 &&
          ClassicSceneUiState.snapshot().speedIndex==4 &&
          ClassicSceneUiState.snapshot().reverse,"saved rainbow parameters reach original UI");
    ctx.mem.map.put(prefix+"SCANNER","v1,3,4,1,4,2,1,1,2,2");
    check(settings.readProfile(4)==null,"foreign scene key mismatch rejected");
    ctx.mem.map.put(prefix+"SCANNER","v1,4,999,1,4,2,1,1,2,2");
    check(settings.readProfile(4)==null,"out of range rejected");
    // Legacy v1 preferences are read-only-compatible. An explicit next edit
    // writes bounded v2 data; no background migration may overwrite a user file.
    ctx.mem.map.put(prefix+"SCANNER","v1,4,4,1,4,2,1,1,2,2");
    check(settings.readProfile(4)!=null && settings.readProfile(4).colorIndex==0 &&
          settings.readProfile(4).paletteIndex==0,
          "previous v1 profile maps to unchanged single-colour pink");
    ctx.mem.map.put(prefix+"SCANNER","v2,4,4,1,4,2,1,1,2,2,7");
    check(settings.readProfile(4).colorIndex==7 &&
          settings.readProfile(4).paletteIndex==0,
          "previous v2 colour remains solid on read");
    ctx.mem.map.put(prefix+"SCANNER","v2,4,4,1,4,2,1,1,2,2,12");
    check(settings.readProfile(4)==null,"out-of-range colour rejected");
    ctx.mem.map.put(prefix+"SCANNER","v2,4,4,1,4,2,1,1,2,2,-1");
    check(settings.readProfile(4)==null,"negative colour rejected");
    check(!ClassicSceneUiState.updateColor(-1) &&
          !ClassicSceneUiState.updateColor(ClassicSceneUiState.COLORS.length),
          "invalid colours rejected without mutation");
    check(ctx.mem.commits==count+1,"only explicit rainbow edit wrote preferences");
    ctx.mem.accept=false;
    check(!settings.saveExplicit(ClassicSceneUiState.snapshot()),
      "denied preferences not reported as saved");
    check(ctx.mem.commits==count+1,"failed editor no effects");
    boolean blocked=false;
    try {new ClassicEffectSettingsStore(new Ctx("com.yeelight.cherry"));}
    catch(SecurityException expected){blocked=true;}
    check(blocked,"original OEM package cannot be written");

    // A complete four-slot DJ favourite must be available in BOTH original
    // views, with no OEM favourite-record or database modifications.
    ctx.mem.accept=true;
    int preFavorites=ctx.mem.commits;
    for(int i=0;i<4;i++)
      check(settings.readFavorite(i)==null,"favorite starts empty");
    check(ctx.mem.commits==preFavorites,"read/host attach never writes favorite");
    ClassicSceneUiState.Selection beforeFavorite=ClassicSceneUiState.snapshot();
    check(!settings.saveFavorite(-1,beforeFavorite) &&
          !settings.saveFavorite(4,beforeFavorite) &&
          !settings.clearFavorite(-1) && !settings.clearFavorite(4) &&
          settings.readFavorite(-1)==null && settings.readFavorite(4)==null,
          "unknown slot never reaches user prefs");
    check(!settings.selectFavorite(0),"empty slot cannot change selected scene");
    check(settings.saveFavorite(0,beforeFavorite),"explicit favorite save");
    check(ctx.mem.commits==preFavorites+1 &&
          ctx.mem.map.containsKey("dj-favorite.0"),
          "one original DJ preference write, no OEM favorite");
    check(ClassicSceneUiState.update(6,4,true,4,2),"independent scene edit");
    check(ClassicSceneUiState.updateAudio(3,false,4,0),"independent audio editor");
    check(ClassicSceneUiState.updateColor(7) &&
          ClassicSceneUiState.updatePalette(1),"rainbow/colour from editor");
    ClassicSceneUiState.Selection rainbow=ClassicSceneUiState.snapshot();
    check(settings.saveFavorite(3,rainbow),"fourth slot preserves whole profile");
    ClassicEffectSettingsStore musicHost=new ClassicEffectSettingsStore(ctx);
    musicHost.restoreOnFirstHost();
    check(ctx.mem.commits==preFavorites+2,"music UI startup remains read-only");
    check(musicHost.selectFavorite(0),"MusicActivity selects gallery favorite");
    ClassicSceneUiState.Selection restored=ClassicSceneUiState.snapshot();
    check(restored.scene==beforeFavorite.scene &&
          restored.speedIndex==beforeFavorite.speedIndex &&
          restored.reverse==beforeFavorite.reverse &&
          restored.trailIndex==beforeFavorite.trailIndex &&
          restored.rangeIndex==beforeFavorite.rangeIndex &&
          restored.gainIndex==beforeFavorite.gainIndex &&
          restored.beatPulse==beforeFavorite.beatPulse &&
          restored.pulseTempoIndex==beforeFavorite.pulseTempoIndex &&
          restored.pulseShapeIndex==beforeFavorite.pulseShapeIndex &&
          restored.colorIndex==beforeFavorite.colorIndex &&
          restored.paletteIndex==beforeFavorite.paletteIndex,
          "all favorite scene/colour/rainbow/geometry settings restored");
    check(musicHost.selectFavorite(3),"music UI loads another independent slot");
    ClassicSceneUiState.Selection restoredRainbow=ClassicSceneUiState.snapshot();
    check(restoredRainbow.scene==6 && restoredRainbow.colorIndex==7 &&
          restoredRainbow.paletteIndex==1 && restoredRainbow.reverse &&
          restoredRainbow.speedIndex==4 && restoredRainbow.trailIndex==4 &&
          restoredRainbow.rangeIndex==2 && restoredRainbow.gainIndex==3 &&
          !restoredRainbow.beatPulse && restoredRainbow.pulseTempoIndex==4 &&
          restoredRainbow.pulseShapeIndex==0,
          "full 4th rainbow scene and audio settings loaded");
    check(ctx.mem.commits==preFavorites+2,"select favorite makes no implicit write");
    ctx.mem.map.put("dj-favorite.1","v3,1,2,0,2,0,1,1,2,2,99,0");
    long rev=ClassicSceneUiState.revision();
    check(musicHost.readFavorite(1)==null && !musicHost.selectFavorite(1) &&
          ClassicSceneUiState.revision()==rev,
          "corrupt favorite cannot alter a running DJ profile");
    check(settings.clearFavorite(0) && musicHost.readFavorite(0)==null &&
          !musicHost.selectFavorite(0) && musicHost.readFavorite(3)!=null,
          "explicit deletion leaves other slots untouched");
    ctx.mem.accept=false;
    check(!settings.saveFavorite(2,rainbow) &&
          !settings.clearFavorite(3) && settings.readFavorite(3)!=null,
          "denied write never claims success or removes existing favorite");
    System.out.println("PASS original-host DJ-only profiles, save, failure, per-scene, no implicit writes");
  }
}
"""

class ClassicEffectSettingsPersistenceTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK unavailable")
    def test_real_production_store_and_state(self):
        with tempfile.TemporaryDirectory(prefix="classic-effect-profiles-") as folder:
            base=Path(folder)
            for name, data in (
                ("android/content/Context.java", CONTEXT),
                ("android/content/SharedPreferences.java", PREFS),
                ("ClassicEffectSettingsHarness.java", HARNESS),
            ):
                path=base/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(data,encoding="utf-8")
            result=subprocess.run(["javac","--release","8","-d",folder,
                *[str(path) for path in SOURCES],
                str(base/"android/content/Context.java"),
                str(base/"android/content/SharedPreferences.java"),
                str(base/"ClassicEffectSettingsHarness.java")],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(["java","-cp",folder,
                "io.github.lxsdd.yeelightclassicdj.ui.ClassicEffectSettingsHarness"],
                capture_output=True,text=True,timeout=25)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("PASS original-host DJ-only profiles",result.stdout)

    def test_actual_scene_and_music_hosts_only_save_on_user_action(self):
        gallery=(UI/"ClassicSceneGalleryPanel.java").read_text()
        music=(UI/"ClassicMusicInlineControls.java").read_text()
        store=(UI/"ClassicEffectSettingsStore.java").read_text()
        for production in (gallery,music):
            self.assertIn("effectSettings.restoreOnFirstHost()",production)
            self.assertIn("effectSettings.selectScene(index)",production)
            self.assertIn("effectSettings.saveExplicit(ClassicSceneUiState.snapshot())",production)
            self.assertNotIn("sendBroadcast(",production)
        self.assertIn('private static final String CLONE = "io.lxsdd.yeelightdj"',store)
        self.assertIn("prefs.edit()",store)
        self.assertIn("static final int FAVORITE_SLOTS = 4", store)
        self.assertIn("FAVORITE_PREFIX + slot",store)
        self.assertIn("effectSettings.saveFavorite(",gallery)
        self.assertIn("effectSettings.clearFavorite(",gallery)
        self.assertIn("effectSettings.selectFavorite(slot)",gallery)
        self.assertIn("effectSettings.selectFavorite(slot)",music)
        self.assertIn("effectSettings.readFavorite(slot)",music)
        self.assertIn("if (lease == null) return;",gallery)
        self.assertIn("if (!attached || !effectSettings.selectFavorite(slot)) return;",music)
        self.assertIn("applyLightSettings();",music)
        self.assertNotIn("getContentResolver(",store)
        self.assertNotIn("set_music",store)
        self.assertNotIn("SQLiteDatabase",store)

if __name__ == "__main__":
    unittest.main()
