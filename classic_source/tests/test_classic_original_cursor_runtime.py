"""Execute the real Classic device/group OrderedCursor with fake Android/OEM data.

No OEM APK, device, network, account, database, login or lamp is accessed.
Only the production Java bridge and synthetic Android API stubs are compiled.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(Path(__file__).parent))
from test_bridge_compile import STUBS

HARNESS=r'''
package io.github.lxsdd.yeelightclassicdj.ui;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class ClassicOriginalCursorHarness {
 static void ok(boolean value,String reason) {
  if(!value)throw new AssertionError(reason);
 }
 static final class MemoryPrefs implements SharedPreferences {
  final Map<String,String> values=new HashMap<>();
  boolean allow=true;
  int writes;
  public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){
   String key,value;
   public Editor putString(String k,String v){key=k;value=v;return this;}
   public boolean commit(){if(!allow)return false;values.put(key,value);writes++;return true;}
  };}
 }
 static final class Ctx extends Context {
  final String packageName;
  final MemoryPrefs prefs=new MemoryPrefs();
  Ctx(String packageName){this.packageName=packageName;}
  @Override public String getPackageName(){return packageName;}
  @Override public SharedPreferences getSharedPreferences(String name,int mode){
   ok("classic-local-ui-order-v1".equals(name),"store namespace");
   ok(mode==Context.MODE_PRIVATE,"private namespace");
   return prefs;
  }
 }
 static final class Data implements Cursor {
  long[] values;
  boolean closed=false;
  boolean hasId=true;
  int pos=-1;
  Data(long...values){this.values=values;}
  public boolean isClosed(){return closed;}
  public int getCount(){return values.length;}
  public int getPosition(){return pos;}
  public int getColumnIndex(String name){return hasId&&"_id".equals(name)?0:-1;}
  public boolean isNull(int col){return false;}
  public long getLong(int col){return values[pos];}
  public boolean moveToPosition(int index){pos=index;return index>=0&&index<values.length;}
  public boolean move(int offset){return moveToPosition(pos+offset);}
  public boolean moveToFirst(){return moveToPosition(0);}
  public boolean moveToLast(){return moveToPosition(values.length-1);}
  public boolean moveToNext(){return moveToPosition(pos+1);}
  public boolean moveToPrevious(){return moveToPosition(pos-1);}
  public boolean isBeforeFirst(){return pos<0;}
  public boolean isAfterLast(){return pos>=values.length;}
  public boolean isFirst(){return pos==0;}
  public boolean isLast(){return pos==values.length-1;}
 }
 public static void main(String[]args){
  Ctx ctx=new Ctx("io.lxsdd.yeelightdj");
  Data source=new Data(10,20,30);
  Cursor wrapped=ClassicOriginalCursorOrder.wrapDevices(ctx,source);
  ok(wrapped instanceof ClassicOriginalCursorOrder.OrderedCursor,"real cursor wrapper");
  ClassicOriginalCursorOrder.OrderedCursor adapter=
      (ClassicOriginalCursorOrder.OrderedCursor)wrapped;
  ok(ctx.prefs.writes==0,"read does not persist");
  ok(source.pos==-1,"constructor restores OEM cursor position");
  ok(wrapped.moveToPosition(2)&&wrapped.getLong(0)==30,"OEM row before user reorder");
  ok(adapter.moveTo("30","10"),"drag last to first");
  ok(ctx.prefs.writes==1,"exactly one explicit local preference write");
  ok(wrapped.moveToPosition(0)&&wrapped.getLong(0)==30,"original cursor yields moved row");
  ok(wrapped.moveToPosition(1)&&wrapped.getLong(0)==10,"other OEM row preserved");
  ok(adapter.moveRelative("30",1),"accessible move");
  ok(ctx.prefs.writes==2,"move writes exactly once");
  ok(wrapped.moveToPosition(0)&&wrapped.getLong(0)==10,"relative order");
  ok(adapter.undo(),"one move undo");
  ok(wrapped.moveToPosition(0)&&wrapped.getLong(0)==30,"undo restores previous");
  ok(!adapter.undo(),"undo cannot replay");
  Cursor reopened=ClassicOriginalCursorOrder.wrapDevices(ctx,new Data(10,20,30,40));
  ok(reopened.moveToPosition(0)&&reopened.getLong(0)==30,"persistent restart");
  ok(reopened.moveToPosition(3)&&reopened.getLong(0)==40,"new OEM device retained");
  Cursor group=ClassicOriginalCursorOrder.wrapGroups(ctx,new Data(10,20,30));
  ok(group.moveToPosition(0)&&group.getLong(0)==10,"independent original group scope");
  int writes=ctx.prefs.writes;
  ctx.prefs.allow=false;
  ok(!adapter.moveRelative("10",1),"failed storage is a failure");
  ok(ctx.prefs.writes==writes,"failed storage no write");
  ctx.prefs.allow=true;
  source.values[1]=200;
  ok(!adapter.moveTo("20","30"),"changed OEM identity invalidates old adapter");
  ok(ctx.prefs.writes==writes,"stale source did not mutate preference");
  Data duplicates=new Data(1,1);
  ok(ClassicOriginalCursorOrder.wrapDevices(ctx,duplicates)==duplicates,
     "duplicate OEM ids refuse wrapper");
  Data unknown=new Data(1,2);unknown.hasId=false;
  ok(ClassicOriginalCursorOrder.wrapDevices(ctx,unknown)==unknown,
     "missing _id refuses wrapper");
  Data invalid=new Data(-1,4);
  ok(ClassicOriginalCursorOrder.wrapGroups(ctx,invalid)==invalid,
     "invalid original key refuses wrapper");
  Ctx originalCtx=new Ctx("com.yeelight.cherry");
  Data originalCursor=new Data(1,2);
  ok(ClassicOriginalCursorOrder.wrapDevices(originalCtx,originalCursor)==originalCursor,
     "never alter real OEM app identity");
  ok(ClassicOriginalCursorOrder.wrapDevices(ctx,null)==null,
     "null OEM list remains null");
  System.out.println("CLASSIC_ORIGINAL_DEVICE_GROUP_CURSOR_RUNTIME_PASS");
 }
}
'''

class ClassicOriginalCursorRuntime(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK needed for actual production Java execution")
    def test_production_ordered_cursor_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)
            paths=[]
            for name,body in STUBS.items():
                target=base/name
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_text(body,encoding="utf-8")
                paths.append(target)
            java_sources=list((ROOT/"bridge/src").rglob("*.java"))
            self.assertTrue(any(p.name=="ClassicOriginalCursorOrder.java"
                                for p in java_sources))
            source=base/"ClassicOriginalCursorHarness.java"
            source.write_text(HARNESS,encoding="utf-8")
            out=base/"classes"
            build=subprocess.run(["javac","--release","8","-d",str(out),
                                  *(str(p) for p in paths+java_sources+[source])],
                                  text=True,capture_output=True,timeout=60)
            self.assertEqual(build.returncode,0,build.stderr[-12000:])
            run=subprocess.run(["java","-cp",str(out),
                "io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalCursorHarness"],
                text=True,capture_output=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stderr[-12000:])
            self.assertIn("CLASSIC_ORIGINAL_DEVICE_GROUP_CURSOR_RUNTIME_PASS",run.stdout)
