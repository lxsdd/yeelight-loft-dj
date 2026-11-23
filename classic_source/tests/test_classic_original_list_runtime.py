"""Execute the real Scene/Room original-model list bridge with synthetic OEM objects.

No APK/DEX, model data, account, database, scene or lamp is accessed.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_bridge_compile import STUBS

ROOT=Path(__file__).resolve().parents[1]
SCENE_MODEL='''package com.yeelight.yeelib.models;
public final class t {
 public final String id;
 public t(String id){this.id=id;}
 public String l(){return id;}
}'''
ROOM_MODEL='''package y3;
public final class a {
 public final String id;
 public a(String id){this.id=id;}
 public String n(){return id;}
}'''
SCENE_ADAPTER='''package com.yeelight.cherry.ui.fragment;
public final class SceneRecyclerViewAdapter {
 public void notifyDataSetChanged(){}
}'''
ROOM_ADAPTER='''package com.yeelight.cherry.ui.adapter;
public final class RoomRecyclerViewAdapter {
 public void notifyDataSetChanged(){}
}'''

HARNESS=r'''
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
import android.content.Context;
import android.content.SharedPreferences;
import com.yeelight.yeelib.models.t;
import com.yeelight.cherry.ui.fragment.SceneRecyclerViewAdapter;
import com.yeelight.cherry.ui.adapter.RoomRecyclerViewAdapter;
public final class OriginalModelListHarness {
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
 static final class Prefs implements SharedPreferences {
  final Map<String,String> values=new HashMap<>();
  int writes;
  boolean accept=true;
  public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){
   String key,value;
   public Editor putString(String k,String v){key=k;value=v;return this;}
   public boolean commit(){if(!accept)return false;values.put(key,value);writes++;return true;}
  };}
 }
 static final class Ctx extends Context {
  final String name;
  final Prefs prefs=new Prefs();
  Ctx(String name){this.name=name;}
  @Override public String getPackageName(){return name;}
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String name,int mode) {
   check("classic-local-ui-order-v1".equals(name),"shared clone-only preference store");
   check(mode==Context.MODE_PRIVATE,"private preference");
   return prefs;
  }
 }
 static t scene(List<?> list,int position){return (t)list.get(position);}
 static y3.a room(List<?> list,int position){return (y3.a)list.get(position);}
 public static void main(String[]args){
  Ctx app=new Ctx("io.lxsdd.yeelightdj");
  ClassicOriginalListOrder.registerCloneContext(app);
  SceneRecyclerViewAdapter sceneAdapter=new SceneRecyclerViewAdapter();
  List<t> original=new ArrayList<>(Arrays.asList(new t("s-1"),new t("s-2"),new t("s-3")));
  List<?> views=ClassicOriginalListOrder.wrapScene(sceneAdapter,original);
  check(views instanceof ClassicOriginalListOrder.OrderedList,"original scene source wraps");
  ClassicOriginalListOrder.OrderedList sceneOrder=(ClassicOriginalListOrder.OrderedList)views;
  check(scene(views,0)==original.get(0),"OEM order untouched initially");
  long revision=sceneOrder.revision();
  check(sceneOrder.moveTo("s-3","s-1",revision),"real scene drag");
  check(scene(views,0)==original.get(2),"scene display reorder only");
  check("s-1".equals(original.get(0).id),"original model list unchanged");
  check(app.prefs.writes==1,"exactly one explicit scene persist");
  check(!sceneOrder.moveTo("s-2","s-3",revision),"stale scene handle rejected");
  check(sceneOrder.moveRelative("s-3",1,sceneOrder.revision()),"relative move");
  check(sceneOrder.undo(sceneOrder.revision()),"scene undo");
  check(scene(views,0)==original.get(2),"scene undo restores prior order");
  List<?> reopened=ClassicOriginalListOrder.wrapScene(
      new SceneRecyclerViewAdapter(),new ArrayList<>(original));
  check("s-3".equals(scene(reopened,0).id),"scene restart local persistence");
  long stale=sceneOrder.revision();
  original.add(new t("s-4"));
  check(views.size()==4,"original list size refresh");
  check("s-4".equals(scene(views,3).id),"new OEM scene remains visible");
  check(!sceneOrder.moveTo("s-1","s-2",stale),"stale pre-refresh drag refused");
  RoomRecyclerViewAdapter roomAdapter=new RoomRecyclerViewAdapter();
  List<y3.a> rooms=new ArrayList<>(Arrays.asList(
      new y3.a("room-a"),new y3.a("room-b"),new y3.a("room-c")));
  List<?> orderedRooms=ClassicOriginalListOrder.wrapRoom(roomAdapter,rooms);
  check(orderedRooms instanceof ClassicOriginalListOrder.OrderedList,
      "original room list source wraps");
  ClassicOriginalListOrder.OrderedList roomOrder=(ClassicOriginalListOrder.OrderedList)orderedRooms;
  check(roomOrder.moveTo("room-c","room-a",roomOrder.revision()),"room drag");
  check("room-c".equals(room(orderedRooms,0).id),"room display reorder");
  check("room-a".equals(rooms.get(0).id),"original room collection untouched");
  int writes=app.prefs.writes;
  app.prefs.accept=false;
  check(!roomOrder.moveRelative("room-c",1,roomOrder.revision()),"failed commit denied");
  check(app.prefs.writes==writes,"failed room persistence no write");
  app.prefs.accept=true;
  List<?> duplicate=Arrays.asList(new t("same"),new t("same"));
  check(ClassicOriginalListOrder.wrapScene(new SceneRecyclerViewAdapter(),duplicate)
      ==duplicate,"duplicate scene ID fail-closed");
  List<?> malformed=Arrays.asList(new t("a"),new Object());
  check(ClassicOriginalListOrder.wrapScene(new SceneRecyclerViewAdapter(),malformed)
      ==malformed,"foreign OEM model fail-closed");
  List<?> empty=Collections.emptyList();
  check(ClassicOriginalListOrder.wrapScene(new RoomRecyclerViewAdapter(),empty)
      ==empty,"foreign adapter type cannot get scene sort");
  List<?> before=new ArrayList<>(original);
  check(ClassicOriginalListOrder.wrapScene(null,before)==before,
      "no unknown adapter writes");
  System.out.println("CLASSIC_ORIGINAL_SCENE_ROOM_LIST_RUNTIME_PASS");
 }
}
'''
class OriginalSceneRoomListRuntimeTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK needed for real original model bridge execution")
    def test_original_scene_room_list_source_and_persistence(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            files=[]
            sources={
                **STUBS,
                "com/yeelight/yeelib/models/t.java":SCENE_MODEL,
                "y3/a.java":ROOM_MODEL,
                "com/yeelight/cherry/ui/fragment/SceneRecyclerViewAdapter.java":SCENE_ADAPTER,
                "com/yeelight/cherry/ui/adapter/RoomRecyclerViewAdapter.java":ROOM_ADAPTER,
                "io/github/lxsdd/yeelightclassicdj/ui/OriginalModelListHarness.java":HARNESS
            }
            for name,body in sources.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text(body,encoding="utf-8");files.append(p)
            classes=root/"compiled"
            bridge=list((ROOT/"bridge/src").rglob("*.java"))
            result=subprocess.run(["javac","--release","8","-d",str(classes),
                                   *(str(p) for p in files+bridge)],
                                   capture_output=True,text=True,timeout=70)
            self.assertEqual(result.returncode,0,result.stderr[-12000:])
            result=subprocess.run(["java","-cp",str(classes),
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalModelListHarness"],
                capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,0,result.stderr[-12000:])
            self.assertIn("CLASSIC_ORIGINAL_SCENE_ROOM_LIST_RUNTIME_PASS",result.stdout)
