package io.github.lxsdd.yeelightclassicdj.ui;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Private, original-identity-only in-Classic entry registration.
 *
 * Does not touch OEM DEX, replace an OEM Activity, export a provider, issue
 * network requests or start playback. The existing OEM root/content views
 * remain in place; only a bounded native button is added to its content
 * FrameLayout when the unmodified app shows a different page from its home
 * layout. This provider is registered ONLY in the owner-gated identity build.
 */
public final class ClassicDjEntryProvider extends ContentProvider
        implements Application.ActivityLifecycleCallbacks {
    private static final String ORIGINAL_PACKAGE = "com.yeelight.cherry";
    private static final String ENTRY_TAG = "io.github.lxsdd.yeelightclassicdj.entry.overlay";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public boolean onCreate() {
        final Context context = getContext();
        if (context == null) return false;
        final Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return false;
        try {
            ((Application) app).registerActivityLifecycleCallbacks(this);
            return true;
        } catch (RuntimeException ignored) {
            // Never bring down OEM initialization for an optional UI entry.
            return false;
        }
    }

    @Override public void onActivityResumed(final Activity activity) {
        if (activity == null) return;
        // OEM may finish layout inflation during resume; schedule after it.
        handler.post(new Runnable() {
            @Override public void run() {
                attachOptionalButton(activity);
            }
        });
    }

    private static void attachOptionalButton(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !ORIGINAL_PACKAGE.equals(activity.getPackageName())
                || activity instanceof ClassicDjActivity
                || activity.getClass().getName().startsWith(
                    "io.github.lxsdd.yeelightclassicdj.")) {
            return;
        }
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;
            if (hasExistingEntry(root, 0)) return;

            ClassicDjEntryView button = new ClassicDjEntryView(activity);
            button.setTag(ENTRY_TAG);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            final float density = activity.getResources().getDisplayMetrics().density;
            int margin = (int) (12 * density + 0.5f);
            params.setMargins(margin, margin, margin, margin);
            root.addView(button, params);
        } catch (RuntimeException ignored) {
            // Fail without disturbing any OEM Activity, layout or login state.
        }
    }

    private static boolean hasExistingEntry(View view, int depth) {
        if (ENTRY_TAG.equals(view.getTag())) return true;
        if (view instanceof ClassicDjEntryView) {
            Rect visible = new Rect();
            if (view.isShown() && view.isClickable() &&
                    view.getGlobalVisibleRect(visible) &&
                    visible.width() > 0 && visible.height() > 0) return true;
        }
        if (!(view instanceof ViewGroup) || depth >= 16) return false;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() > 1000) return true; // fail closed
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasExistingEntry(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    @Override public void onActivityCreated(Activity a, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
    @Override public void onActivityDestroyed(Activity a) {}

    // No queryable state or remote control surface. Manifest exported=false.
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] args) { return 0; }
}
