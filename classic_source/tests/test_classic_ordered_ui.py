"""Executable offline Classsic ordering: real Java code, no OEM/device/DB access."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1] / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
SRC = [BASE / (name + ".java") for name in
       ("ClassicOrderPolicy", "ClassicLocalOrderStore", "ClassicDragDropController", "ClassicEffectOrder", "ClassicSceneUiState")]
CONTEXT = r'''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public String getPackageName(){return "";}
 public SharedPreferences getSharedPreferences(String name,int mode){return null;}
}
'''
PREFS = r'''package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback);
 Editor edit();
 interface Editor {
  Editor putString(String key,String value);
  boolean commit();
 }
}
'''
TEST = r'''package io.github.lxsdd.yeelightclassicdj.ui;

import java.util.*;
import android.content.Context;
import android.content.SharedPreferences;

public class ClassicOrderHarness {
  private static void check(boolean ok,String message){
    if(!ok)throw new AssertionError(message);
  }
  private static List<String> list(String...a){return Arrays.asList(a);}
  static final class MemPrefs implements SharedPreferences {
    final Map<String,String> map = new HashMap<>();
    int writes=0; boolean allow=true;
    public String getString(String k,String d){return map.getOrDefault(k,d);}
    public Editor edit(){return new Editor(){
      String k,v;
      public Editor putString(String key,String value){k=key;v=value;return this;}
      public boolean commit(){if(!allow)return false;map.put(k,v);writes++;return true;}
    };}
  }
  static final class Ctx extends Context {
    final MemPrefs prefs=new MemPrefs();
    final String packageName;
    Ctx(String name){packageName=name;}
    public String getPackageName(){return packageName;}
    public SharedPreferences getSharedPreferences(String n,int mode){
      check("classic-local-ui-order-v1".equals(n),"separate local namespace");
      check(mode==Context.MODE_PRIVATE,"private preferences only");
      return prefs;
    }
  }
  static final class Adapter implements ClassicDragDropController.VerifiedList {
    final ClassicOrderPolicy.Surface surface;
    final String scope; List<String> original,shown; boolean editing=true;
    Adapter(ClassicOrderPolicy.Surface s,String k,List<String> ids,ClassicLocalOrderStore store){
      surface=s;scope=k;original=new ArrayList<>(ids);
      shown=ClassicOrderPolicy.project(original,store.read(s,k));
    }
    public ClassicOrderPolicy.Surface surface(){return surface;}
    public String stableScope(){return scope;}
    public List<String> originalStableIds(){return original;}
    public List<String> displayedStableIds(){return shown;}
    public void render(List<String> ids){shown=new ArrayList<>(ids);}
    public boolean userEditing(){return editing;}
  }
  public static void main(String[]args) {
    Ctx ctx=new Ctx("io.lxsdd.yeelightdj");
    ClassicLocalOrderStore store=new ClassicLocalOrderStore(ctx);
    ClassicDragDropController ctrl=new ClassicDragDropController(store);
    check(store.read(ClassicOrderPolicy.Surface.DEVICE_LIST,"global").isEmpty(),"no default mutation");
    check(ctx.prefs.writes==0,"read never writes");
    Adapter devices=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"global",list("A","B","C"),store);
    check(ctrl.onUserMove(devices,"C",0),"move to first");
    check(devices.shown.equals(list("C","A","B")),"first position");
    check(ctrl.onUserMove(devices,"C",2),"move to last");
    check(devices.shown.equals(list("A","B","C")),"last position");
    check(ctrl.onUserMove(devices,"C",2),"no-op drop");
    check(ctx.prefs.writes==2,"no-op must not write");
    check(ctrl.onUserMove(devices,"B",0),"persistent reorder");
    check(devices.shown.equals(list("B","A","C")),"correct order");
    Adapter reopened=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"global",list("A","B","C","D"),store);
    check(reopened.shown.equals(list("B","A","C","D")),"relaunch + discovered device order");
    Adapter offline=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"global",list("B","C"),store);
    check(ctrl.onUserMove(offline,"C",0),"offline member move");
    reopened=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"global",list("A","B","C","D"),store);
    check(reopened.shown.equals(list("C","A","B","D")),"offline stable-ID preservation");
    Adapter scenes=new Adapter(ClassicOrderPolicy.Surface.SCENE_LIST,"global",list("scene.1","scene.2"),store);
    check(scenes.shown.equals(list("scene.1","scene.2")),"scenes remain independent");
    check(ctrl.onUserMove(scenes,"scene.2",0),"scene reorder");
    Adapter rooms=new Adapter(ClassicOrderPolicy.Surface.ROOM_LIST,"global",list("r1","r2"),store);
    Adapter groups=new Adapter(ClassicOrderPolicy.Surface.GROUP_LIST,"global",list("g1","g2"),store);
    Adapter favs=new Adapter(ClassicOrderPolicy.Surface.FAVORITES,"global",list("f1","f2"),store);
    Adapter members=new Adapter(ClassicOrderPolicy.Surface.GROUP_MEMBERS,"g1",list("A","B"),store);
    Adapter path=new Adapter(ClassicOrderPolicy.Surface.LIGHT_PATH,"effect.1",list("A","B"),store);
    for(Adapter a: Arrays.asList(rooms,groups,favs,members,path))
      check(ctrl.onUserMove(a,a.shown.get(1),0),"all independent sortable kinds");
    check(new Adapter(ClassicOrderPolicy.Surface.GROUP_MEMBERS,"g2",list("A","B"),store)
        .shown.equals(list("A","B")),"per-group independence");
    check(new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"global",list("A","B","C","D"),store)
        .shown.equals(list("C","A","B","D")),"other scopes untouched");
    int writes=ctx.prefs.writes;
    scenes.editing=false;
    check(!ctrl.onUserMove(scenes,"scene.1",0),"editing mode gate");
    check(ctx.prefs.writes==writes,"disabled editing never writes");
    scenes.editing=true;
    scenes.shown=list("scene.1","scene.2");
    check(!ctrl.onUserMove(scenes,"scene.1",0),"stale rendered snapshot fails closed");
    check(ctx.prefs.writes==writes,"stale drop never writes");
    check(!ctrl.onUserMove(devices,"no such id",0),"missing stable ID cannot drag");
    check(!ctrl.onUserMove(devices,"B",-1),"negative target rejected");
    check(!ctrl.onUserMove(devices,"B",3),"out-of-range target rejected");
    check(ctx.prefs.writes==writes,"invalid events never write");
    ctx.prefs.allow=false;
    Adapter automations=new Adapter(ClassicOrderPolicy.Surface.AUTOMATIONS,"global",list("x","y"),store);
    check(!ctrl.onUserMove(automations,"y",0),"write failure is not UI success");
    check(automations.shown.equals(list("x","y")),"failure does not reorder UI");
    ctx.prefs.allow=true;
    boolean rejected=false;
    try {new ClassicLocalOrderStore(new Ctx("com.yeelight.cherry"));}
    catch(SecurityException expected){rejected=true;}
    check(rejected,"original app identity must never be written");
    rejected=false;
    try {ClassicOrderPolicy.project(list("A","A"),Collections.emptyList());}
    catch(IllegalArgumentException expected){rejected=true;}
    check(rejected,"duplicate stable IDs rejected");
    rejected=false;
    try {ClassicOrderPolicy.drop(list("A","B"),list("B","A"),
           Collections.emptyList(),"B","A");}
    catch(IllegalArgumentException expected){rejected=true;}
    check(rejected,"stale snapshot rejected");

    // Real production controller: a FRESH adapter must reject invalid drops
    // rather than reporting success for a no-op (an earlier test was stale).
    Adapter invalid=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"validation",
        list("i1","i2","i3"),store);
    int stableWrites=ctx.prefs.writes;
    check(!ctrl.onUserMove(invalid,"not-a-real-id",0),"fresh invalid id rejected");
    check(!ctrl.onUserMove(invalid,"i1",-1),"fresh negative row rejected");
    check(!ctrl.onUserMove(invalid,"i1",3),"fresh out-of-range row rejected");
    check(!ctrl.onUserDrop(invalid,"i1","missing"),"fresh invalid before-target rejected");
    check(!ctrl.onUserDrop(invalid,"missing","i1"),"fresh invalid dragged id rejected");
    check(!ctrl.onUserMoveRelative(invalid,"i1",-1),"top move-up disabled");
    check(!ctrl.onUserMoveRelative(invalid,"i3",1),"bottom move-down disabled");
    check(!ctrl.onUserMoveRelative(invalid,"i2",2),"only 1-step accessible moves allowed");
    check(ctx.prefs.writes==stableWrites,"invalid input cannot write preferences");
    check(invalid.shown.equals(list("i1","i2","i3")),"invalid input cannot reorder UI");

    // Accessible up/down and exact-scope Undo have the same real preference
    // route as touch drag. No interaction with original device membership.
    Adapter undo=new Adapter(ClassicOrderPolicy.Surface.DEVICE_LIST,"undo-scope",
        list("A","B","C"),store);
    check(!ctrl.onUndoLastMove(undo),"no previous edit to undo");
    check(ctrl.onUserMoveRelative(undo,"C",-1),"accessible move up");
    check(undo.shown.equals(list("A","C","B")),"accessible order");
    check(ctrl.onUndoLastMove(undo),"undo exact previous write");
    check(undo.shown.equals(list("A","B","C")),"undo restores row order");
    check(!ctrl.onUndoLastMove(undo),"undo receipt single-use");
    check(ctrl.onUserMoveRelative(undo,"B",1),"accessible move down");
    check(undo.shown.equals(list("A","C","B")),"move-down action");
    ctx.prefs.allow=false;
    check(!ctrl.onUndoLastMove(undo),"failed undo write must not be success");
    check(undo.shown.equals(list("A","C","B")),"failed undo leaves original display");
    ctx.prefs.allow=true;
    check(ctrl.onUndoLastMove(undo),"retry safely commits the exact undo");
    check(undo.shown.equals(list("A","B","C")),"undo committed");
    check(ctrl.onUserMove(undo,"A",2),"new touch drag");
    check(undo.shown.equals(list("B","C","A")),"new order persisted");
    Adapter otherScope=new Adapter(ClassicOrderPolicy.Surface.ROOM_LIST,"undo-scope",
        list("R1","R2"),store);
    check(!ctrl.onUndoLastMove(otherScope),"undo may not affect another list kind");
    Adapter anotherGroup=new Adapter(ClassicOrderPolicy.Surface.GROUP_MEMBERS,"group2",
        list("A","B"),store);
    check(!ctrl.onUndoLastMove(anotherGroup),"undo may not affect other groups");
    undo.shown=list("A","B","C"); // stale original adapter after OEM refresh
    check(!ctrl.onUndoLastMove(undo),"stale displayed snapshot must not undo");
    undo.shown=list("B","C","A");
    check(ctrl.onUndoLastMove(undo),"correct owner can still undo after refresh");
    check(undo.shown.equals(list("A","B","C")),"recovered order is exact");
    check(ctx.prefs.map.size()>3,"independent scopes persist simultaneously");

    // Real owned DJ card integration. OEM scene records and users' devices
    // are never modified, and no read/restart causes a new preference write.
    int writesBeforeEffects=ctx.prefs.writes;
    ClassicEffectOrder effects=new ClassicEffectOrder(ctx);
    check(effects.order().equals(list("SPECTRUM","AMBIENT","BEAT","PULSE",
        "SCANNER","METEOR","WAVE")),"default original DJ effect IDs");
    check(ctx.prefs.writes==writesBeforeEffects,"initial read never writes");
    check(effects.move("WAVE",0),"long-drop moves real card");
    check(effects.order().get(0).equals("WAVE"),"new gallery order");
    check(ctx.prefs.writes==writesBeforeEffects+1,"exact one user preference commit");
    ClassicEffectOrder music=new ClassicEffectOrder(ctx);
    check(music.order().equals(effects.order()),"original music shares persisted order");
    int safeWrites=ctx.prefs.writes;
    check(!music.move("foreign-scene",0),"unverified OEM scene rejected");
    check(!music.move("WAVE",-1),"out-of-range rejected");
    check(!music.moveRelative("WAVE",-1),"cannot move top element");
    check(ctx.prefs.writes==safeWrites,"invalid action never persists");
    check(effects.moveRelative("WAVE",1),"accessible action uses same controller");
    check(effects.order().get(1).equals("WAVE"),"accessibility changes real card");
    check(!music.move("PULSE",0),"stale other UI cannot overwrite");
    check(music.refresh(),"other UI detects external reorder");
    check(music.order().equals(effects.order()),"exact updated shared order");
    check(effects.undo(),"single-use undo accepted");
    check(effects.order().get(0).equals("WAVE"),"undo restores prior order");
    check(!effects.undo(),"second undo rejected");
    check(music.refresh(),"music sees undo without restarting capture");
    ctx.prefs.allow=false;
    check(!effects.move("WAVE",2),"write-failure returns false");
    check(effects.order().get(0).equals("WAVE"),"failed persist keeps visible order");
    ctx.prefs.allow=true;
    check(ClassicEffectOrder.sceneIndex("METEOR")==5,"stable symbolic ID");
    check(ClassicEffectOrder.sceneIndex("OEM-private-id")==-1,"no OEM scene ABI guess");
    check(new ClassicEffectOrder(ctx).order().get(0).equals("WAVE"),
        "order survives recreation");
    System.out.println("PASS actual Classic order, persistence, offline, scopes, stale, accessible up/down, exact Undo, safety");
  }
}
'''

class ClassicOrderedUi(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK unavailable")
    def test_real_sort_code_and_private_persistence_positive_negative(self):
        with tempfile.TemporaryDirectory(prefix="classic-order-") as folder:
            root=Path(folder)
            ctx=root/"android/content/Context.java"
            ctx.parent.mkdir(parents=True,exist_ok=True)
            ctx.write_text(CONTEXT,encoding="utf-8")
            prefs=root/"android/content/SharedPreferences.java"
            prefs.write_text(PREFS,encoding="utf-8")
            harness=root/"ClassicOrderHarness.java"
            harness.write_text(TEST,encoding="utf-8")
            output=root/"classes"
            compilation=subprocess.run(
                ["javac","--release","8","-d",str(output),
                 *[str(path) for path in SRC],str(ctx),str(prefs),str(harness)],
                capture_output=True,text=True,timeout=40)
            self.assertEqual(compilation.returncode,0,compilation.stderr)
            execution=subprocess.run(
                ["java","-cp",str(output),
                 "io.github.lxsdd.yeelightclassicdj.ui.ClassicOrderHarness"],
                capture_output=True,text=True,timeout=25)
            self.assertEqual(execution.returncode,0,execution.stderr)
            self.assertIn("PASS actual Classic order",execution.stdout)

    def test_no_device_cloud_oem_db_writes_in_sort_code(self):
        source="\n".join(path.read_text("utf-8") for path in SRC)
        self.assertIn("getSharedPreferences",source)
        self.assertIn("applyUserDropAtIndex",source)
        self.assertIn("onUserMove",source)
        self.assertIn("onUserMoveRelative",source)
        self.assertIn("onUndoLastMove",source)
        self.assertIn("undoLastUserDrop",source)
        self.assertIn("ClassicOrderPolicy.project(",source)
        for forbidden in ("sendBroadcast(", "set_power", "set_music", "query(", "ContentResolver",
                          "execSQL", "getRemoteDeviceList", "startDiscovery(", "SharedPreferencesImpl"):
            self.assertNotIn(forbidden,source)


if __name__ == "__main__":
    unittest.main()
