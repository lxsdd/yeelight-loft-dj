"""Execute the genuine GroupMusic ListAdapter wrapper with synthetic Android/JDK.

No OEM APK, actual device, cloud or lamp writes. Unlike a source-string-only
test this runs the production read-through adapter and persisted drop/Undo.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_bridge_compile import STUBS

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
PRODUCTION = [
    "ClassicOriginalGroupMemberOrder.java",
    "ClassicOriginalGroupMemberWitness.java",
    "ClassicOriginalMusicSelection.java",
    "ClassicLocalOrderStore.java",
    "ClassicOrderPolicy.java",
]

OEM_MEMBER = """package com.yeelight.yeelib.device.base;
public class e {
  public final String key;
  public e(String key){this.key=key;}
  public String T(){return key;}
}"""

OEM_ADAPTER = """package com.yeelight.yeelib.ui.activity;
import android.widget.BaseAdapter;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.*;
import com.yeelight.yeelib.device.base.e;
public final class GroupMusicModeManagerActivity {
  public static final class g extends BaseAdapter {
    public final ArrayList<e> items=new ArrayList<>();
    private final Context context;
    public int lastDrawn=-1;
    public g(Context ctx, String... ids){
      context=ctx;
      for(String id:ids) items.add(new e(id));
    }
    @Override public int getCount(){return items.size();}
    @Override public e getItem(int pos){return items.get(pos);}
    @Override public long getItemId(int pos){return 1000+pos;}
    @Override public View getView(int pos,View reuse,ViewGroup parent){
      lastDrawn=pos;
      return reuse!=null ? reuse : new View(context);
    }
  }
}"""

HARNESS = """package io.github.lxsdd.yeelightclassicdj.ui;
import android.content.*;
import android.widget.*;
import android.view.*;
import java.util.*;
import com.yeelight.yeelib.device.base.e;
import com.yeelight.yeelib.ui.activity.GroupMusicModeManagerActivity.g;
public final class GroupMemberOrderHarness {
  static void check(boolean result,String why){if(!result)throw new AssertionError(why);}
  static class Prefs implements SharedPreferences {
    final Map<String,String> values=new HashMap<>();
    public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
    public Editor edit(){
      return new Editor() {
        String key,value;
        public Editor putString(String k,String v){key=k;value=v;return this;}
        public boolean commit(){values.put(key,value);return true;}
      };
    }
  }
  static final class CloneContext extends Context {
    final Prefs prefs = new Prefs();
    @Override public String getPackageName(){return "io.lxsdd.yeelightdj";}
    @Override public SharedPreferences getSharedPreferences(String name,int mode) {
      check(mode==Context.MODE_PRIVATE,"private preference");
      check(name.equals("classic-local-ui-order-v1"),"own prefs namespace");
      return prefs;
    }
  }
  static final class OriginalContext extends Context {
    @Override public String getPackageName(){return "com.yeelight.cherry";}
  }
  static void owner(String key){
    ClassicOriginalMusicSelection.observedFromOriginalDevice(key);
    ClassicOriginalMusicSelection.observedOriginalGroupEntry(key);
  }
  static ClassicOriginalGroupMemberOrder.OrderedGroupAdapter installed(ListView list) {
    check(list.getAdapter() instanceof ClassicOriginalGroupMemberOrder.OrderedGroupAdapter,
      "original ListView now owns wrapper");
    return (ClassicOriginalGroupMemberOrder.OrderedGroupAdapter)list.getAdapter();
  }
  public static void main(String[] args) {
    CloneContext context=new CloneContext();
    ListView view=new ListView(context);
    g original=new g(context,"a","b","c","d","e","f");
    owner("room-one");
    ClassicOriginalGroupMemberOrder.installGroupAdapter(view,original);
    ClassicOriginalGroupMemberOrder.OrderedGroupAdapter wrapped=installed(view);
    check(wrapped.getCount()==6,"all original members");
    check(wrapped.getItem(0)==original.getItem(0),"initial identity and item object");
    check(wrapped.getItemId(0)==1000,"initial id and original click contract");
    check(wrapped.getView(0,null,view)!=null && original.lastDrawn==0,
      "real original row renderer invoked");
    long initial=wrapped.revision();
    check(wrapped.moveTo("e","b",initial),"real persisted explicit drop");
    check(((e)wrapped.getItem(1)).key.equals("e"),"displayed item resolves source member");
    check(wrapped.getItemId(1)==1004,"index mapping preserves OEM item id");
    check(wrapped.getView(1,null,view)!=null && original.lastDrawn==4,
      "original onClick closure binds actual moved member");
    check(original.items.get(1).key.equals("b"),"original membership and order untouched");
    check(!wrapped.moveTo("c","a",initial),"old generation fenced");
    check(wrapped.undo(wrapped.revision()),"last user drop undone");
    check(((e)wrapped.getItem(1)).key.equals("b"),"original order restored");
    check(!wrapped.undo(wrapped.revision()),"single use undo");
    check(wrapped.moveRelative("f",-1,wrapped.revision()),"relative move persists");
    check(((e)wrapped.getItem(4)).key.equals("f"),"relative stable ID order");
    // A new adapter instance in this same group reads the preference, not DB.
    ListView recreated=new ListView(context);
    g sameGroup=new g(context,"a","b","c","d","e","f");
    ClassicOriginalGroupMemberOrder.installGroupAdapter(recreated,sameGroup);
    ClassicOriginalGroupMemberOrder.OrderedGroupAdapter persisted=installed(recreated);
    check(((e)persisted.getItem(4)).key.equals("f"),"reopen persisted display order");
    // No duplicate physical device target is created; the source is unchanged.
    check(sameGroup.items.get(5).key.equals("f"),"original model remains unchanged");
    long beforeRefresh=persisted.revision();
    sameGroup.items.add(new e("g"));
    check(!persisted.moveTo("a","b",beforeRefresh),"changed OEM list revokes gesture");
    check(persisted.getCount()==7,"new OEM member retained");
    check(((e)persisted.getItem(6)).key.equals("g"),"new item appended by projection");
    // Invalid duplicated OEM keys fail closed and display original items only.
    sameGroup.items.add(new e("g"));
    check(persisted.displayIdAt(0)==null,"duplicated identity disables handle");
    check(!persisted.moveRelative("a",1,persisted.revision()),"no drop with duplicates");
    check(persisted.getItem(0)==sameGroup.getItem(0),"falls back to OEM list");
    // A different original group cannot inherit the saved display preferences.
    owner("room-two");
    ListView second=new ListView(context);
    g another=new g(context,"a","b","c","d","e","f");
    ClassicOriginalGroupMemberOrder.installGroupAdapter(second,another);
    check(((e)installed(second).getItem(4)).key.equals("e"),
      "different original group scope isolated");
    check(!persisted.moveTo("b","a",persisted.revision()),
      "old GroupMusic session fenced after navigation");
    ClassicOriginalMusicSelection.originalGroupClosed();
    check(!installed(second).moveRelative("a",1,installed(second).revision()),
      "group close revokes sort and member IDs");
    // Real original app / unknown adapter ownership must always fall back.
    ListView originalPackage=new ListView(new OriginalContext());
    g unowned=new g(context,"x","y");
    ClassicOriginalGroupMemberOrder.installGroupAdapter(originalPackage,unowned);
    check(originalPackage.getAdapter()==unowned,"original package never modified");
    ListView foreignAdapter=new ListView(context);
    BaseAdapter unexpected=new BaseAdapter(){
      public int getCount(){return 0;}
      public Object getItem(int i){return null;}
      public long getItemId(int i){return i;}
      public View getView(int i,View r,ViewGroup p){return null;}
    };
    ClassicOriginalGroupMemberOrder.installGroupAdapter(foreignAdapter,unexpected);
    check(foreignAdapter.getAdapter()==unexpected,"unknown OEM adapter falls back");
    System.out.println("CLASSIC_GROUP_MEMBER_ORIGINAL_ADAPTER_RUNTIME_PASS");
  }
}"""

class GroupMemberOrderRuntimeTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK needed for actual production Java adapter runtime")
    def test_real_production_group_dnd_mapping_persistence_undo_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            stubs=dict(STUBS)
            original_list=stubs["android/widget/ListView.java"]
            self.assertIn("public void setAdapter(ListAdapter adapter){}",original_list)
            stubs["android/widget/ListView.java"]=original_list.replace(
                "public void setAdapter(ListAdapter adapter){}",
                """private ListAdapter current;
                public void setAdapter(ListAdapter adapter){current=adapter;}
                public ListAdapter getAdapter(){return current;}""")
            stubs["com/yeelight/yeelib/device/base/e.java"]=OEM_MEMBER
            stubs["com/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity.java"]=OEM_ADAPTER
            stubs["io/github/lxsdd/yeelightclassicdj/ui/GroupMemberOrderHarness.java"]=HARNESS
            paths=[]
            for file,body in stubs.items():
                # This standalone adapter JDK test intentionally does not
                # compile the unrelated Kotlin DSP Java ABI placeholder.
                # The complete bridge test still validates it separately.
                if file == "io/github/lxsdd/yeelightclassicdj/runtime/ClassicOriginalDspAnalysis.java":
                    continue
                p=root/file
                p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text(body)
                paths.append(p)
            cmd=["javac","--release","8","-d",str(root/"classes")]+[
                str(p) for p in paths]+[str(UI/f) for f in PRODUCTION]
            result=subprocess.run(cmd,capture_output=True,text=True,timeout=50)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(["java","-cp",str(root/"classes"),
                  "io.github.lxsdd.yeelightclassicdj.ui.GroupMemberOrderHarness"],
                  capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("CLASSIC_GROUP_MEMBER_ORIGINAL_ADAPTER_RUNTIME_PASS",run.stdout)

if __name__=="__main__":
    unittest.main()
