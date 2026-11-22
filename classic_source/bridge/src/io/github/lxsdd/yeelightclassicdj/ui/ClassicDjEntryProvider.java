package io.github.lxsdd.yeelightclassicdj.ui;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import java.lang.ref.WeakReference;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String CLONE_PACKAGE = "io.lxsdd.yeelightdj";
    // A real resumed original MusicActivity, held weakly, is the only return target.
    private static volatile WeakReference<Activity> observedMusicHost =
        new WeakReference<>(null);

    /** Never create a standalone-DJ or login-screen notification fallback. */
    static Intent verifiedMusicReturnIntent(Context caller) {
        if (caller == null || !CLONE_PACKAGE.equals(caller.getPackageName())) return null;
        Activity observed = observedMusicHost.get();
        if (observed == null || observed.isFinishing() || observed.isDestroyed()) return null;
        String activityName = observed.getClass().getName();
        if (!isOriginalMusicHost(observed.getPackageName(), activityName)) return null;
        return new Intent().setClassName(caller.getPackageName(), activityName);
    }

    // Match only a trusted original OEM MusicActivity in the isolated clone.
    // This is NOT an exported intent and never invokes an OEM private method.
    static boolean isOriginalMusicHost(String appPackage, String activityClass) {
        return CLONE_PACKAGE.equals(appPackage)
            && activityClass != null
            && activityClass.startsWith("com.yeelight.cherry.")
            && activityClass.endsWith(".MusicActivity")
            && activityClass.indexOf('/') == -1
            && activityClass.indexOf('$') == -1;
    }
    private static final String ENTRY_TAG = "io.github.lxsdd.yeelightclassicdj.entry.overlay";
    private static final String DIAG_TAG = "ClassicDjProbe";
    private static final AtomicInteger DIAG_EVENTS = new AtomicInteger();
    private static void probe(String fixedMarker) {
        // Private original-identity QA only: callers use literal fixed strings,
        // never OEM symbols, activity names, user/device data or UI text.
        if (DIAG_EVENTS.getAndIncrement() >= 40) return;
        try { Log.i(DIAG_TAG, fixedMarker); }
        catch (RuntimeException ignored) { /* never affect OEM startup */ }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Some OEM screens replace their content view after onResume. Perform only
    // bounded, cancelable in-Activity rechecks; no global overlay or OEM patch.
    private static final long[] LAYOUT_RECHECK_MS = {0L, 180L, 700L, 1600L, 2800L};
    private final java.util.WeakHashMap<Activity, java.util.List<Runnable>> pending =
        new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<Activity, ClassicMusicInlineControls> musicPanels =
        new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<Activity, MusicDockLease> musicDocks =
        new java.util.WeakHashMap<>();

    /** Preserve the OEM root and reserve an actual viewport below it. */
    private static final class MusicDockLease {
        final FrameLayout root;
        final View original;
        final ClassicMusicInlineControls controls;
        final int baseline;
        int reserved;
        MusicDockLease(FrameLayout root, View original,
                       ClassicMusicInlineControls controls, int baseline) {
            this.root = root;
            this.original = original;
            this.controls = controls;
            this.baseline = baseline;
        }
        void resize(int requested) {
            if (original.getParent() != root || controls.getParent() != root ||
                !(original.getLayoutParams() instanceof FrameLayout.LayoutParams))
                return;
            FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) original.getLayoutParams();
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.bottomMargin != baseline + reserved ||
                requested < 0 ||
                requested > root.getResources().getDisplayMetrics().heightPixels * 0.6f)
                return;
            lp.bottomMargin = baseline + requested;
            original.setLayoutParams(lp);
            reserved = requested;
        }
        void detach() {
            controls.setDockHeightListener(null);
            if (original.getParent() == root &&
                original.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) original.getLayoutParams();
                if (lp.bottomMargin == baseline + reserved) {
                    lp.bottomMargin = baseline;
                    original.setLayoutParams(lp);
                }
            }
            if (controls.getParent() == root) root.removeView(controls);
        }
    }


    @Override public boolean onCreate() {
        final Context context = getContext();
        if (context == null) return false;
        final Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return false;
        try {
            ((Application) app).registerActivityLifecycleCallbacks(this);
            probe("ENTRY_PROVIDER_REGISTERED");
            return true;
        } catch (RuntimeException ignored) {
            // Never bring down OEM initialization for an optional UI entry.
            return false;
        }
    }

    @Override public void onActivityResumed(final Activity activity) {
        if (activity == null || activity instanceof ClassicDjActivity) return;
        if (isOriginalMusicHost(activity.getPackageName(),
                                activity.getClass().getName())) {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            observedMusicHost = new WeakReference<>(activity);
            scheduleMusicPanel(activity);
            return;
        }
        // Original-package identity control remains private and separate.
        // No cloned welcome/login/home/activity receives the debug button.
        if (!ORIGINAL_PACKAGE.equals(activity.getPackageName())) return;
        probe("ENTRY_ACTIVITY_RESUMED");
        cancelPending(activity);
        java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        pending.put(activity, tasks);
        for (long delayMs : LAYOUT_RECHECK_MS) {
            Runnable attempt = new Runnable() {
                @Override public void run() {
                    // onResume may precede the first focused/layout frame.
                    // Attaching to this Activity's own content is safe before
                    // it has focus; Android performs the layout afterwards.
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    // A missing Activity window focus is only diagnostic.
                    // Never suppress the app-local attach on this basis.
                    probe(activity.hasWindowFocus() ?
                          "ENTRY_HOST_FOCUSED" : "ENTRY_HOST_UNFOCUSED");
                    // The tagged entry is idempotent. If OEM content was
                    // replaced, the later pass sees the new content root.
                    attachOptionalButton(activity);
                }
            };
            tasks.add(attempt);
            handler.postDelayed(attempt, delayMs);
        }
    }

    private void scheduleMusicPanel(final Activity activity) {
        cancelPending(activity);
        java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        pending.put(activity, tasks);
        for (long delayMs : LAYOUT_RECHECK_MS) {
            Runnable attempt = new Runnable() {
                @Override public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    attachMusicPanel(activity);
                }
            };
            tasks.add(attempt);
            handler.postDelayed(attempt, delayMs);
        }
    }

    private void attachMusicPanel(Activity activity) {
        if (!isOriginalMusicHost(activity.getPackageName(),
                                 activity.getClass().getName())) return;
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;
            ClassicMusicInlineControls existing = findMusicPanel(root, 0);
            MusicDockLease prior = musicDocks.get(activity);
            if (existing != null) {
                if (prior == null || prior.root != root || prior.controls != existing)
                    return;
                musicPanels.put(activity, existing);
                existing.onHostResumed();
                return;
            }
            // No arbitrary toolbar offsets, additional windows or OEM reparenting.
            if (root.getChildCount() != 1) return;
            View original = root.getChildAt(0);
            if (original == null ||
                !(original.getLayoutParams() instanceof FrameLayout.LayoutParams))
                return;
            FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) original.getLayoutParams();
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.bottomMargin < 0) return;
            if (prior != null) { prior.detach(); musicDocks.remove(activity); }
            ClassicMusicInlineControls controls = new ClassicMusicInlineControls(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            root.addView(controls, params);
            MusicDockLease lease = new MusicDockLease(root, original, controls,
                lp.bottomMargin);
            musicDocks.put(activity, lease);
            controls.setDockHeightListener(lease::resize);
            musicPanels.put(activity, controls);
            controls.onHostResumed();
            probe("CLASSIC_MUSIC_INLINE_CONTROLS_ATTACHED");
        } catch (RuntimeException ignored) {
            // Optional music controls cannot take down the OEM MusicActivity.
        }
    }

    private static ClassicMusicInlineControls findMusicPanel(View view, int depth) {
        if (view instanceof ClassicMusicInlineControls)
            return (ClassicMusicInlineControls) view;
        if (!(view instanceof ViewGroup) || depth >= 12) return null;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() > 300) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            ClassicMusicInlineControls match =
                findMusicPanel(group.getChildAt(i), depth + 1);
            if (match != null) return match;
        }
        return null;
    }

    private void pauseMusicPanel(Activity activity) {
        ClassicMusicInlineControls panel = musicPanels.remove(activity);
        if (panel != null) panel.onHostPaused();
    }

    private void cancelPending(Activity activity) {
        java.util.List<Runnable> tasks = pending.remove(activity);
        if (tasks == null) return;
        for (Runnable task : tasks) handler.removeCallbacks(task);
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
            if (!(content instanceof FrameLayout)) {
                probe("ENTRY_ROOT_UNAVAILABLE");
                return;
            }
            FrameLayout root = (FrameLayout) content;
            if (hasExistingEntry(root, 0)) {
                probe("ENTRY_ALREADY_PRESENT");
                return;
            }

            ClassicDjEntryView button = new ClassicDjEntryView(activity);
            button.setTag(ENTRY_TAG);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            final float density = activity.getResources().getDisplayMetrics().density;
            int margin = (int) (12 * density + 0.5f);
            params.setMargins(margin, margin, margin, margin);
            // Above siblings within THIS Activity, never a global window.
            button.setElevation(8f * density);
            root.addView(button, params);
            button.bringToFront();
            probe("ENTRY_ADDED_TO_ACTIVITY");
        } catch (RuntimeException ignored) {
            // Fail without disturbing any OEM Activity, layout or login state.
        }
    }

    private static boolean hasExistingEntry(View view, int depth) {
        if (ENTRY_TAG.equals(view.getTag())) {
            if (view instanceof ClassicDjEntryView) {
                view.bringToFront();
                Rect visible = new Rect();
                boolean laidOut = view.isAttachedToWindow() && view.isShown()
                    && view.isClickable() && view.getGlobalVisibleRect(visible)
                    && visible.width() > 0 && visible.height() > 0;
                // Android-side layout is not a proof of top-window
                // accessibility or UI navigation.
                probe(laidOut ? "ENTRY_VIEW_LAID_OUT"
                              : "ENTRY_VIEW_NOT_LAID_OUT");
            }
            return true;
        }
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
    @Override public void onActivityPaused(Activity a) {
        cancelPending(a);
        pauseMusicPanel(a);
        // A paused MusicActivity is no longer a verified foreground return
        // target. Revoke the previously issued notification PendingIntent too:
        // otherwise Android can retain its old explicit Activity target after
        // Welcome/Login, task switching, or an Activity replacement.
        if (observedMusicHost.get() == a) {
            observedMusicHost = new WeakReference<>(null);
            revokeMusicReturnPendingIntent(a);
        }
    }

    private static void revokeMusicReturnPendingIntent(Activity activity) {
        if (activity == null || !CLONE_PACKAGE.equals(activity.getPackageName())) return;
        try {
            int flags = android.app.PendingIntent.FLAG_NO_CREATE |
                (android.os.Build.VERSION.SDK_INT >= 23 ?
                    android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            // FLAG_NO_CREATE never manufactures a navigation destination.
            // PendingIntent identity is requestCode + component + intent action;
            // the service's NEW_TASK/CLEAR_TOP flags do not affect equality.
            Intent target = new Intent().setClassName(
                activity.getPackageName(), activity.getClass().getName());
            android.app.PendingIntent existing = android.app.PendingIntent.getActivity(
                activity, 32, target, flags);
            if (existing != null) existing.cancel();
        } catch (RuntimeException ignored) {
            // Optional notification cleanup must not crash original Classic.
        }
    }
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
    @Override public void onActivityDestroyed(Activity a) {
        cancelPending(a);
        pauseMusicPanel(a);
        MusicDockLease lease = musicDocks.remove(a);
        if (lease != null) lease.detach();
        // A previous host must not invalidate a newer configuration-change host.
        if (observedMusicHost.get() == a) {
            observedMusicHost = new WeakReference<>(null);
            // Original selection is a transient read-only witness, not a
            // transferable authorization from a destroyed MusicActivity.
            ClassicOriginalMusicSelection.clear();
        }
    }

    // No queryable state or remote control surface. Manifest exported=false.
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] args) { return 0; }
}
