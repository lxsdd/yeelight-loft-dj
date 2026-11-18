package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Real controls inside the ORIGINAL Classic MusicActivity view tree.
 *
 * Never launches another Activity, invents a device list or replaces the OEM
 * DiscoFragment. Until the OEM Music/Device session contract is verified,
 * the explicit start action runs microphone analysis with ZERO lamp targets.
 * Existing Classic music, scenes, color picker and navigation remain intact.
 */
public final class ClassicMusicInlineControls extends LinearLayout {
    private static final String[] SCENES =
        {"SPECTRUM", "AMBIENT", "BEAT", "PULSE"};
    private static final String[] SCENE_LABELS =
        {"Spektrum", "Ruhig", "Beat", "Puls"};
    private static final double[] GAINS = {0.5, 1.0, 2.0, 4.0};
    private final Activity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final LinearLayout expanded;
    private final Button sceneButton;
    private final Button gainButton;
    private int scene = 0;
    private int gain = 1;
    private boolean locallyStarted = false;
    private boolean attached = false;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            if (locallyStarted) {
                status.setText(ClassicDjService.hasActiveSession() ?
                    ClassicDjService.uiStatus() :
                    "Mikrofonanalyse wird vorbereitet · keine Lampen");
            }
            handler.postDelayed(this, 650L);
        }
    };

    public ClassicMusicInlineControls(Activity activity) {
        super(activity);
        host = activity;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (6 * getResources().getDisplayMetrics().density + 0.5f);
        setPadding(pad, pad, pad, pad);
        setTag("io.github.lxsdd.yeelightclassicdj.music.inline");

        Button toggle = new Button(activity);
        toggle.setText("Loft DJ");
        toggle.setContentDescription("Loft DJ Steuerung in Classic-Musik einblenden");
        addView(toggle);
        expanded = new LinearLayout(activity);
        expanded.setOrientation(VERTICAL);
        expanded.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> expanded.setVisibility(
            expanded.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addView(expanded);

        status = new TextView(activity);
        status.setText("Loft DJ · Classic-Musik · nur Mikrofonanalyse, keine Lampen");
        status.setContentDescription("Loft DJ Status innerhalb Classic Music");
        expanded.addView(status);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(HORIZONTAL);
        Button start = new Button(activity);
        start.setText("Analyse");
        start.setContentDescription("Mikrofonanalyse starten, ohne Lampen");
        start.setOnClickListener(v -> startAnalysisOnly());
        controls.addView(start, new LinearLayout.LayoutParams(0, -2, 1f));

        sceneButton = new Button(activity);
        sceneButton.setText(SCENE_LABELS[scene]);
        sceneButton.setOnClickListener(v -> {
            scene = (scene + 1) % SCENES.length;
            sceneButton.setText(SCENE_LABELS[scene]);
            configureIfOwned();
        });
        controls.addView(sceneButton, new LinearLayout.LayoutParams(0, -2, 1f));

        gainButton = new Button(activity);
        gainButton.setText("1×");
        gainButton.setOnClickListener(v -> {
            gain = (gain + 1) % GAINS.length;
            gainButton.setText(String.valueOf(GAINS[gain]) + "×");
            configureIfOwned();
        });
        controls.addView(gainButton, new LinearLayout.LayoutParams(0, -2, 1f));

        Button stop = new Button(activity);
        stop.setText("Stopp");
        stop.setContentDescription("Loft DJ Mikrofonanalyse stoppen");
        stop.setOnClickListener(v -> stopOwned());
        controls.addView(stop, new LinearLayout.LayoutParams(0, -2, 1f));
        expanded.addView(controls);
    }

    public void onHostResumed() {
        attached = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    /** Do not wait for network/device state or keep an orphaned analyzer alive. */
    public void onHostPaused() {
        attached = false;
        handler.removeCallbacks(refresh);
        stopOwned();
        expanded.setVisibility(View.GONE);
    }

    private void startAnalysisOnly() {
        if (locallyStarted || ClassicDjService.hasActiveSession()) {
            status.setText("Eine DJ-Sitzung ist bereits aktiv");
            return;
        }
        // Do not request an unknown OEM permission request code and do not
        // silently start on any subsequent permission grant.
        if (Build.VERSION.SDK_INT >= 23 &&
                host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
            status.setText("Mikrofonberechtigung in Classic aktivieren, dann erneut starten");
            return;
        }
        Intent intent = new Intent(host, ClassicDjService.class);
        intent.setAction(ClassicDjService.ACTION_MIC);
        intent.putExtra(ClassicDjActivity.EXTRA_SELECTED_IPS, new String[0]);
        intent.putExtra(ClassicDjService.EXTRA_GAIN, GAINS[gain]);
        intent.putExtra(ClassicDjService.EXTRA_SCENE, SCENES[scene]);
        try {
            if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(intent);
            else host.startService(intent);
            locallyStarted = true;
            status.setText("Mikrofonanalyse startet · keine Lampenbefehle");
        } catch (RuntimeException error) {
            status.setText("Analyse nicht gestartet · keine Lampenbefehle");
        }
    }

    private void configureIfOwned() {
        if (!locallyStarted || !ClassicDjService.hasActiveSession()) return;
        Intent intent = new Intent(host, ClassicDjService.class);
        intent.setAction(ClassicDjService.ACTION_CONFIG);
        intent.putExtra(ClassicDjService.EXTRA_GAIN, GAINS[gain]);
        intent.putExtra(ClassicDjService.EXTRA_SCENE, SCENES[scene]);
        try {
            host.startService(intent);
        } catch (RuntimeException error) {
            status.setText("Einstellung nicht übernommen");
        }
    }

    private void stopOwned() {
        if (!locallyStarted) return;
        locallyStarted = false;
        Intent intent = new Intent(host, ClassicDjService.class);
        intent.setAction(ClassicDjService.ACTION_STOP);
        try {
            host.startService(intent);
        } catch (RuntimeException blockedInBackground) {
            // Android may prohibit startService as the OEM MusicActivity
            // backgrounds. Destroy only our existing, locally owned service
            // as a fallback: onDestroy() performs fenced owned cleanup.
            try {
                host.stopService(new Intent(host, ClassicDjService.class));
            } catch (RuntimeException ignored) {
                status.setText("Stopp unbestätigt · Sitzungsstatus prüfen");
                return;
            }
        }
        status.setText("Analyse gestoppt · Classic-Musik unverändert");
    }
}
