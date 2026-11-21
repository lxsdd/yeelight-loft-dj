"""Host-only Java type smoke using tiny test stubs. NOT a real Android SDK or DEX build."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
STUBS = {
'android/Manifest.java': '''package android; public class Manifest { public static class permission { public static final String RECORD_AUDIO="android.permission.RECORD_AUDIO"; } }''',
'android/R.java': '''package android; public final class R { public static class drawable { public static final int ic_media_play=1, ic_media_pause=2; }}''',
'android/content/Context.java': '''package android.content; public class Context {
  public void startActivity(Intent i) {} public void startService(Intent i){} public void startForegroundService(Intent i){}
 public boolean stopService(Intent i){return true;}
  public <T> T getSystemService(Class<T> serviceClass){return null;} public int checkSelfPermission(String p){return 0;}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x1, FLAG_ACTIVITY_CLEAR_TOP=0x2, FLAG_ACTIVITY_SINGLE_TOP=0x4; public static final String ACTION_MAIN="android.intent.action.MAIN", CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";
 public Intent(){} public Intent(Context c, Class<?> k){} public Intent setClassName(String p,String k){return this;} public Intent addFlags(int f){return this;}
 public Intent addCategory(String c){return this;} public Intent setAction(String a){return this;} public String getAction(){return "";}
 public Intent putExtra(String k,String[] a){return this;} public String[] getStringArrayExtra(String k){return null;}
}''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }''',
'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
 protected void onCreate(android.os.Bundle b){} protected void onResume(){} protected void onPause(){} public void setContentView(android.view.View v){}
 public void requestPermissions(String[] p,int r){} public void onRequestPermissionsResult(int code,String[] p,int[] results){}
}''',
'android/app/Service.java': '''package android.app; public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2;
 public android.os.IBinder onBind(android.content.Intent i){return null;}
 public int onStartCommand(android.content.Intent i,int f,int s){return 0;}
 public void onDestroy(){} public void stopSelf(){} public void startForeground(int id,Notification n){}
 public void stopForeground(boolean remove){}
 public void startForeground(int id,Notification notice,int types){}
}''',
'android/app/PendingIntent.java': '''package android.app; public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1, FLAG_IMMUTABLE=2, FLAG_NO_CREATE=4;
 public static PendingIntent getActivity(android.content.Context c,int i,android.content.Intent a,int f){return null;}
 public static PendingIntent getService(android.content.Context c,int i,android.content.Intent a,int f){return null;}
 public void cancel(){}
}''',
'android/app/Notification.java': '''package android.app; public class Notification {
 public static class Builder {
 public Builder(android.content.Context c){} public Builder(android.content.Context c,String channel){}
 public Builder setContentTitle(CharSequence x){return this;} public Builder setContentText(CharSequence x){return this;}
 public Builder setSmallIcon(int x){return this;} public Builder setContentIntent(PendingIntent x){return this;}
 public Builder setOngoing(boolean x){return this;}
 public Builder addAction(int icon,CharSequence text,PendingIntent intent){return this;}
 public Notification build(){return new Notification();}
 }}''',
'android/app/NotificationChannel.java': '''package android.app; public class NotificationChannel {
 public NotificationChannel(String i,CharSequence name,int importance){}
}''',
'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_LOW=2;
 public void createNotificationChannel(NotificationChannel c){}
}''',
'android/os/Build.java': '''package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}}''',
'android/os/Bundle.java': '''package android.os; public class Bundle {}''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){ return 0L; } }''',
'android/os/Looper.java': '''package android.os; public class Looper { public static Looper getMainLooper(){return null;} }''',
'android/os/Handler.java': '''package android.os; public class Handler { public Handler(Looper l){} public void post(Runnable r){} public void postDelayed(Runnable r,long t){} public void removeCallbacks(Runnable r){} }''',
'android/os/IBinder.java': '''package android.os; public interface IBinder {}''',
'android/util/AttributeSet.java': '''package android.util; public interface AttributeSet {}''',
'android/util/Log.java': '''package android.util; public class Log {
 public static int w(String a,String b){return 0;} public static int i(String a,String b){return 0;}
 public static int e(String a,String b,Throwable c){return 0;}
 }''',
'android/view/View.java': '''package android.view; public class View {
 private final android.content.Context c; public View(android.content.Context c){this.c=c;}
 public void setContentDescription(CharSequence a){} public void setOnClickListener(OnClickListener x){}
  public void setEnabled(boolean enabled){}
 public android.content.Context getContext(){return c;}
 public interface OnClickListener {void onClick(View v);} }''',
'android/view/Gravity.java': '''package android.view; public class Gravity {public static final int TOP=48;}''',
'android/widget/TextView.java': '''package android.widget; public class TextView extends android.view.View {
 public TextView(android.content.Context c){super(c);} public TextView(android.content.Context c,android.util.AttributeSet a){super(c);}
 public void setText(CharSequence c){} public void setTextSize(float s){}
}''',
'android/widget/EditText.java': '''package android.widget; public class EditText extends TextView {
 public EditText(android.content.Context c){super(c);} public void setSingleLine(boolean v){} public void setHint(CharSequence x){} public CharSequence getText(){return "";}
}''',
'android/widget/CheckBox.java': '''package android.widget; public class CheckBox extends Button {
 public CheckBox(android.content.Context c){super(c);} public boolean isChecked(){return false;}
}''',
'android/widget/Button.java': '''package android.widget; public class Button extends TextView {
 public Button(android.content.Context c){super(c);} public Button(android.content.Context c,android.util.AttributeSet attrs){super(c,attrs);}
 public void setAllCaps(boolean b){}
}''',
'android/widget/ScrollView.java': '''package android.widget; public class ScrollView extends android.view.View {
 public ScrollView(android.content.Context c){super(c);} public void setFillViewport(boolean v){} public void addView(android.view.View v){}
}''',
'android/widget/LinearLayout.java': '''package android.widget; public class LinearLayout extends android.view.View {
 public static final int VERTICAL=1; public LinearLayout(android.content.Context c){super(c);}
 public void setOrientation(int v){} public void setPadding(int a,int b,int c,int d){}
 public void setGravity(int g){} public void addView(android.view.View v){}
}''',
}


# Synthetic only: support in-place live config Intent values and horizontal
# native control rows; the production Android SDK remains authoritative.
STUBS.update({
'android/content/Intent.java': '''package android.content; public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x1, FLAG_ACTIVITY_CLEAR_TOP=0x2, FLAG_ACTIVITY_SINGLE_TOP=0x4; public static final String ACTION_MAIN="android.intent.action.MAIN", CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";
 public Intent(){} public Intent(Context c, Class<?> k){} public Intent setClassName(String p,String k){return this;} public Intent addFlags(int f){return this;}
 public Intent addCategory(String c){return this;} public Intent setAction(String a){return this;} public String getAction(){return "";}
 public Intent putExtra(String k,String[] a){return this;}
 public Intent putExtra(String k,String v){return this;}
 public Intent putExtra(String k,double v){return this;} public Intent putExtra(String k,int v){return this;} public Intent putExtra(String k,Intent v){return this;}
 public String[] getStringArrayExtra(String k){return null;}
 public String getStringExtra(String k){return null;}
 public double getDoubleExtra(String k,double fallback){return fallback;} public int getIntExtra(String k,int fallback){return fallback;} public <T> T getParcelableExtra(String k,Class<T> c){return null;} public <T> T getParcelableExtra(String k){return null;}
}''',
'android/widget/LinearLayout.java': '''package android.widget;
public class LinearLayout extends android.view.View {
 public static final int VERTICAL=1, HORIZONTAL=0;
 public LinearLayout(android.content.Context c){super(c);}
 public void setOrientation(int v){} public void setPadding(int a,int b,int c,int d){}
 public void setGravity(int g){} public void addView(android.view.View v){}
 public void addView(android.view.View v,LayoutParams p){}
 public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
  public LayoutParams(int w,int h,float weight){super(w,h);}
 }
}''',
})

# These stubs cover the new original-identity-only lifecycle adapter.
# The production build still uses the real Android SDK and D8, not these fakes.
STUBS.update({
'android/R.java': '''package android; public final class R {
 public static class drawable { public static final int ic_media_play=1, ic_media_pause=2; }
 public static class id { public static final int content=16908290; }
}''',
'android/content/Context.java': '''package android.content; public class Context {
 public void startActivity(Intent i) {} public void startService(Intent i){} public void startForegroundService(Intent i){}
 public boolean stopService(Intent i){return true;}
 public <T> T getSystemService(Class<T> serviceClass){return null;} public int checkSelfPermission(String p){return 0;}
 public Context getApplicationContext(){return null;} public String getPackageName(){return "";}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public static final int MODE_PRIVATE=0;
 public SharedPreferences getSharedPreferences(String name,int mode){return null;}
}''',
'android/content/res/Resources.java': '''package android.content.res; public class Resources {
 public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}
}''',
'android/util/DisplayMetrics.java': '''package android.util; public class DisplayMetrics { public float density=1.0f; }''',
'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
 protected void onCreate(android.os.Bundle b){} protected void onResume(){} protected void onPause(){}
 public void setContentView(android.view.View v){} public boolean isFinishing(){return false;}
 public boolean isDestroyed(){return false;} public boolean hasWindowFocus(){return true;}
 public void finish(){} public android.content.ComponentName getComponentName(){return new android.content.ComponentName();}
 public static final int RESULT_OK=-1;
 public android.content.Intent getIntent(){return new android.content.Intent();}
 public void startActivityForResult(android.content.Intent intent,int request){}
 protected void onActivityResult(int request,int result,android.content.Intent data){}
 public android.view.View findViewById(int id){return null;}
 public void requestPermissions(String[] p,int r){} public void onRequestPermissionsResult(int code,String[] p,int[] results){}
}''',
'android/app/Application.java': '''package android.app; public class Application extends android.content.Context {
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a,android.os.Bundle b);
  void onActivityStarted(Activity a); void onActivityResumed(Activity a);
  void onActivityPaused(Activity a); void onActivityStopped(Activity a);
  void onActivitySaveInstanceState(Activity a,android.os.Bundle b);
  void onActivityDestroyed(Activity a);
 }
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){}
}''',
'android/content/ContentValues.java': '''package android.content; public class ContentValues {}''',
'android/database/Cursor.java': '''package android.database; public interface Cursor {}''',
'android/net/Uri.java': '''package android.net; public class Uri {}''',
'android/content/ContentProvider.java': '''package android.content;
public abstract class ContentProvider {
 public android.content.Context getContext(){return null;}
 public abstract boolean onCreate();
 public abstract android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] args,String order);
 public abstract String getType(android.net.Uri uri);
 public abstract android.net.Uri insert(android.net.Uri uri,ContentValues values);
 public abstract int delete(android.net.Uri uri,String selection,String[] args);
 public abstract int update(android.net.Uri uri,ContentValues values,String selection,String[] args);
}''',
'android/graphics/Rect.java': '''package android.graphics; public class Rect {public int width(){return 1;} public int height(){return 1;}}''',
'android/view/View.java': '''package android.view; public class View {
 private final android.content.Context c; private Object tag;
 public View(android.content.Context c){this.c=c;}
 public void setContentDescription(CharSequence a){} public void setOnClickListener(OnClickListener x){}
  public void setEnabled(boolean enabled){}
 public android.content.Context getContext(){return c;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public void setTag(Object o){tag=o;} public Object getTag(){return tag;}
 public boolean isShown(){return true;} public boolean isClickable(){return true;}
 public boolean isAttachedToWindow(){return true;}
 public static final int IMPORTANT_FOR_ACCESSIBILITY_YES = 1;
 public static final int VISIBLE = 0, GONE = 8;
 public void setVisibility(int visibility){} public int getVisibility(){return VISIBLE;}
 public void setImportantForAccessibility(int value){}
 public int getWidth(){return 1;} public int getHeight(){return 1;}
 public void setElevation(float v){} public void bringToFront(){}
 public boolean getGlobalVisibleRect(android.graphics.Rect r){return true;}
 public interface OnClickListener {void onClick(View v);}
}''',
'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
 public ViewGroup(android.content.Context c){super(c);}
 public int getChildCount(){return 0;} public View getChildAt(int i){return null;}
 public static class LayoutParams {
  public static final int WRAP_CONTENT=-2;
  public LayoutParams(int width,int height){}
 }
}''',
'android/view/Gravity.java': '''package android.view; public class Gravity {
 public static final int TOP=48, BOTTOM=80, END=8388613, CENTER_VERTICAL=16, CENTER_HORIZONTAL=1;
}''',
'android/widget/FrameLayout.java': '''package android.widget;
public class FrameLayout extends android.view.ViewGroup {
 public FrameLayout(android.content.Context c){super(c);}
 public void addView(android.view.View v,LayoutParams params){}
 public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
  public LayoutParams(int w,int h,int gravity){super(w,h);}
  public void setMargins(int left,int top,int right,int bottom){}
 }
}''',
})


# New first-launch clone-only Activity requires platform APIs that the
# previous minimal Activity/PackageManager stubs did not model.
STUBS.update({
'android/Manifest.java': '''package android; public class Manifest { public static class permission {
 public static final String RECORD_AUDIO="android.permission.RECORD_AUDIO";
 public static final String BLUETOOTH_SCAN="android.permission.BLUETOOTH_SCAN";
 public static final String BLUETOOTH_CONNECT="android.permission.BLUETOOTH_CONNECT";
 public static final String NEARBY_WIFI_DEVICES="android.permission.NEARBY_WIFI_DEVICES";
 }}''',
'android/content/pm/ActivityInfo.java': '''package android.content.pm; public class ActivityInfo {
 public android.os.Bundle metaData=new android.os.Bundle();
}''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager {
 public static final int PERMISSION_GRANTED=0, GET_META_DATA=128;
 public static class NameNotFoundException extends Exception {}
 public ActivityInfo getActivityInfo(android.content.ComponentName c,int flags) throws NameNotFoundException {return new ActivityInfo();}
}''',
'android/content/ComponentName.java': '''package android.content; public class ComponentName {}''',
'android/os/Bundle.java': '''package android.os; public class Bundle {
 public String getString(String key){return null;}
}''',
})

# Synthetic compile support for Android MediaProjection.
STUBS.update({
'android/media/projection/MediaProjection.java': '''package android.media.projection;
public class MediaProjection {
 public static abstract class Callback { public void onStop(){} }
 public void registerCallback(Callback callback,android.os.Handler handler){}
 public void unregisterCallback(Callback callback){}
 public void stop(){}
}''',
'android/media/projection/MediaProjectionManager.java': '''package android.media.projection;
public class MediaProjectionManager {
 public android.content.Intent createScreenCaptureIntent(){return new android.content.Intent();}
 public MediaProjection getMediaProjection(int result,android.content.Intent data){return new MediaProjection();}
}''',
'android/content/pm/ServiceInfo.java': '''package android.content.pm; public class ServiceInfo {
 public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION=32;
 public static final int FOREGROUND_SERVICE_TYPE_MICROPHONE=128;
}''',
})

# Local-only drag order requires Android private preferences, not an OEM DB.
STUBS.update({
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback);
 Editor edit();
 interface Editor { Editor putString(String key,String value); boolean commit(); }
}''',
})

class BridgeCompileTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'javac not present')
    def test_all_original_bridge_java_sources_typecheck_against_synthetic_api(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            paths = []
            for file, data in STUBS.items():
                p = root / file
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(data)
                paths.append(p)
            sources = list((ROOT/'bridge/src').rglob('*.java'))
            self.assertGreaterEqual(len(sources), 7)
            self.assertTrue(any(p.name == 'ClassicMusicInlineControls.java' for p in sources))
            result = subprocess.run(['javac', '--release', '8', '-Xlint:all', '-d', str(root/'classes'), *(str(p) for p in paths+sources)], text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            klass = root/'classes/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.class'
            self.assertTrue(klass.exists())
            self.assertTrue((root/'classes/io/github/lxsdd/yeelightclassicdj/ui/ClassicMusicInlineControls.class').exists())
            if shutil.which('java'):
                # Executable Java host/notification contract with synthetic Android only.
                oem = root/'com/yeelight/cherry/ui/MusicActivity.java'
                oem.parent.mkdir(parents=True, exist_ok=True)
                oem.write_text("""package com.yeelight.cherry.ui;
public final class MusicActivity extends android.app.Activity {
    @Override public String getPackageName() { return "io.lxsdd.yeelightdj"; }
}""")
                probe = root/'NativeMusicHostContract.java'
                probe.write_text("""package io.github.lxsdd.yeelightclassicdj.ui;
public final class NativeMusicHostContract {
    private static void check(String packageName, String host, boolean expected) {
        if (ClassicDjEntryProvider.isOriginalMusicHost(packageName, host) != expected)
            throw new AssertionError("NativeMusicHostContract");
    }
    public static void main(String[] args) {
        check("io.lxsdd.yeelightdj", "com.yeelight.cherry.ui.MusicActivity", true);
        check("io.lxsdd.yeelightdj", "com.yeelight.cherry.MainActivity", false);
        check("io.lxsdd.yeelightdj", "com.yeelight.cherry.WelcomeGuideNewActivity", false);
        check("io.lxsdd.yeelightdj", "com.yeelight.cherry.LocaleSelectionLoginActivity", false);
        check("io.lxsdd.yeelightdj", "com.yeelight.cherry.ui.MusicActivity$Wrapped", false);
        check("io.lxsdd.yeelightdj", "com.other.MusicActivity", false);
        check("com.yeelight.cherry", "com.yeelight.cherry.ui.MusicActivity", false);
        check("io.lxsdd.yeelightdj", null, false);
        android.content.Context clone = new android.content.Context() {
            @Override public String getPackageName() { return "io.lxsdd.yeelightdj"; }
        };
        android.content.Context original = new android.content.Context() {
            @Override public String getPackageName() { return "com.yeelight.cherry"; }
        };
        ClassicDjEntryProvider provider = new ClassicDjEntryProvider();
        if (ClassicDjEntryProvider.verifiedMusicReturnIntent(clone) != null)
            throw new AssertionError("Unobserved host must not authorize notification");
        provider.onActivityResumed(new android.app.Activity());
        if (ClassicDjEntryProvider.verifiedMusicReturnIntent(clone) != null)
            throw new AssertionError("Unknown host must not authorize notification");
        com.yeelight.cherry.ui.MusicActivity host = new com.yeelight.cherry.ui.MusicActivity();
        provider.onActivityResumed(host);
        if (ClassicDjEntryProvider.verifiedMusicReturnIntent(clone) == null)
            throw new AssertionError("Observed Classic Music must authorize notification");
        if (ClassicDjEntryProvider.verifiedMusicReturnIntent(original) != null)
            throw new AssertionError("Original identity must not authorize clone intent");
        provider.onActivityDestroyed(host);
        if (ClassicDjEntryProvider.verifiedMusicReturnIntent(clone) != null)
            throw new AssertionError("Destroyed host must revoke notification");
    }
}""")
                probe_build = subprocess.run(
                    ['javac', '--release', '8', '-cp', str(root/'classes'),
                     '-d', str(root/'classes'), str(probe), str(oem)],
                    text=True, capture_output=True, timeout=20)
                self.assertEqual(probe_build.returncode, 0, probe_build.stderr)
                check = subprocess.run(
                    ['java', '-cp', str(root/'classes'),
                     'io.github.lxsdd.yeelightclassicdj.ui.NativeMusicHostContract'],
                    text=True, capture_output=True, timeout=15)
                self.assertEqual(check.returncode, 0, check.stderr)

if __name__ == '__main__':
    unittest.main()
