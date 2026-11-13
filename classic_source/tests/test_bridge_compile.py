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
  public <T> T getSystemService(Class<T> serviceClass){return null;} public int checkSelfPermission(String p){return 0;}
}''',
'android/content/Intent.java': '''package android.content; public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x1, FLAG_ACTIVITY_CLEAR_TOP=0x2, FLAG_ACTIVITY_SINGLE_TOP=0x4;
 public Intent(Context c, Class<?> k){} public Intent addFlags(int f){return this;}
 public Intent setAction(String a){return this;} public String getAction(){return "";}
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
}''',
'android/app/PendingIntent.java': '''package android.app; public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1, FLAG_IMMUTABLE=2;
 public static PendingIntent getActivity(android.content.Context c,int i,android.content.Intent a,int f){return null;}
 public static PendingIntent getService(android.content.Context c,int i,android.content.Intent a,int f){return null;}
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


# These stubs cover the new original-identity-only lifecycle adapter.
# The production build still uses the real Android SDK and D8, not these fakes.
STUBS.update({
'android/R.java': '''package android; public final class R {
 public static class drawable { public static final int ic_media_play=1, ic_media_pause=2; }
 public static class id { public static final int content=16908290; }
}''',
'android/content/Context.java': '''package android.content; public class Context {
 public void startActivity(Intent i) {} public void startService(Intent i){} public void startForegroundService(Intent i){}
 public <T> T getSystemService(Class<T> serviceClass){return null;} public int checkSelfPermission(String p){return 0;}
 public Context getApplicationContext(){return null;} public String getPackageName(){return "";}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}''',
'android/content/res/Resources.java': '''package android.content.res; public class Resources {
 public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}
}''',
'android/util/DisplayMetrics.java': '''package android.util; public class DisplayMetrics { public float density=1.0f; }''',
'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
 protected void onCreate(android.os.Bundle b){} protected void onResume(){} protected void onPause(){}
 public void setContentView(android.view.View v){} public boolean isFinishing(){return false;}
 public boolean isDestroyed(){return false;} public android.view.View findViewById(int id){return null;}
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
 public android.content.Context getContext(){return c;}
 public void setTag(Object o){tag=o;} public Object getTag(){return tag;}
 public boolean isShown(){return true;} public boolean isClickable(){return true;}
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
 public static final int TOP=48, BOTTOM=80, END=8388613;
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
            self.assertGreaterEqual(len(sources), 4)
            result = subprocess.run(['javac', '--release', '8', '-Xlint:all', '-d', str(root/'classes'), *(str(p) for p in paths+sources)], text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            klass = root/'classes/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.class'
            self.assertTrue(klass.exists())

if __name__ == '__main__':
    unittest.main()
