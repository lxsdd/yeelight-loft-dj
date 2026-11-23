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
FAVORITE_MODEL='''package com.yeelight.yeelib.models;
public final class m {
 public final String id;
 public m(String id){this.id=id;}
 public String W(){return id;}
}'''
FAVORITE_ADAPTER='''package com.yeelight.yeelib.ui.adapter;
import java.util.*;
import com.yeelight.yeelib.models.m;
import io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalListOrder;
public final class FavoriteSceneAdapter {
 private final ArrayList<m> current=new ArrayList<>();
 public FavoriteSceneAdapter(String category,boolean mode){
  ClassicOriginalListOrder.registerFavoriteAdapter(this,category,mode);
 }
 public void e(List<?> source){
  List<?> prepared=ClassicOriginalListOrder.wrapFavorite(this,source);
  current.clear();
  for(Object item:prepared)current.add((m)item);
  notifyDataSetChanged();
 }
 public m get(int index){return current.get(index);}
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
import com.yeelight.yeelib.models.m;
import com.yeelight.yeelib.ui.adapter.FavoriteSceneAdapter;
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
  // An OEM constructor may forward the same wrapped List back through the
  // hook; the active adapter must keep its gesture owner and source identity.
  List<?> same=ClassicOriginalListOrder.wrapScene(sceneAdapter,views);
  check(same==views,"same adapter preserves original wrapper and drag owner");
  long oldGeneration=sceneOrder.revision();
  List<t> nextSource=new ArrayList<>(Arrays.asList(
      new t("next-1"),new t("next-2"),new t("next-3")));
  List<?> next=ClassicOriginalListOrder.wrapScene(sceneAdapter,nextSource);
  check(next!=views,"same adapter fresh source gets new read-through view");
  check(!sceneOrder.moveTo("s-4","s-1",oldGeneration),
      "old holder cannot mutate local order after adapter replacement");
  check("next-1".equals(scene(next,0).id),"new OEM list is displayed unchanged");
  ClassicOriginalListOrder.OrderedList nextOrder=
      (ClassicOriginalListOrder.OrderedList)next;
  check(nextOrder.moveTo("next-3","next-1",nextOrder.revision()),
      "new adapter model list remains draggable");
  check("next-3".equals(scene(next,0).id),
      "new list order projects without mutating underlying OEM model list");
  List<?> foreign=ClassicOriginalListOrder.wrapRoom(roomAdapter, next);
  check(foreign==nextSource,"room adapter cannot inherit a scene model wrapper");
  check("next-1".equals(nextSource.get(0).id),"original model objects remain untouched");
  // The real FavoriteSceneAdapter.e(List) copies a sorted DETACHED snapshot.
  FavoriteSceneAdapter favorite=new FavoriteSceneAdapter("favorite-catalog-A",true);
  List<m> savedSource=new ArrayList<>(Arrays.asList(
      new m("favorite-a"),new m("favorite-b"),new m("favorite-c")));
  favorite.e(savedSource);
  check(favorite.get(0)==savedSource.get(0),"original favorite order starts unchanged");
  ClassicOriginalListOrder.OrderedList active=
      ClassicOriginalListOrder.favoriteListForAdapter(favorite);
  check(active!=null,"original favorite adapter bound");
  long favoriteRevision=active.revision();
  check(active.moveTo("favorite-c","favorite-a",favoriteRevision),
      "favorite gesture persists local stable ID preference");
  ClassicOriginalListOrder.redraw(favorite);
  check(favorite.get(0)==savedSource.get(2),
      "original e(List) re-entered to reflect saved favorite order");
  check(savedSource.get(0).id.equals("favorite-a"),
      "original favorite model source never mutated");
  check(!active.moveTo("favorite-a","favorite-c",favoriteRevision),
      "previous Favorite adapter generation revoked after refresh");
  ClassicOriginalListOrder.OrderedList nextFavorite=
      ClassicOriginalListOrder.favoriteListForAdapter(favorite);
  check(nextFavorite!=null && nextFavorite != active,"new favorite adapter owner");
  check(nextFavorite.undo(nextFavorite.revision()),"favorite undo");
  ClassicOriginalListOrder.redraw(favorite);
  check(favorite.get(0)==savedSource.get(0),"favorite undo visible in original adapter");
  FavoriteSceneAdapter another=new FavoriteSceneAdapter("favorite-catalog-B",true);
  another.e(savedSource);
  check(another.get(0)==savedSource.get(0),"independent original favorite scope");
  FavoriteSceneAdapter invalid=new FavoriteSceneAdapter(null,true);
  invalid.e(savedSource);
  check(ClassicOriginalListOrder.favoriteListForAdapter(invalid)==null,
      "unverified favorite category never wraps");
  List<m> duplicate=new ArrayList<>(Arrays.asList(new m("same"),new m("same")));
  another.e(duplicate);
  check(ClassicOriginalListOrder.favoriteListForAdapter(another)==null,
      "ambiguous stable favorite IDs rejected");
  System.out.println("CLASSIC_ORIGINAL_SCENE_ROOM_FAVORITE_LIST_RUNTIME_PASS");
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
                "com/yeelight/yeelib/models/m.java":FAVORITE_MODEL,
                "com/yeelight/yeelib/ui/adapter/FavoriteSceneAdapter.java":FAVORITE_ADAPTER,
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
            self.assertIn("CLASSIC_ORIGINAL_SCENE_ROOM_FAVORITE_LIST_RUNTIME_PASS",result.stdout)
