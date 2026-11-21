package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private final Button sourceButton;
    private boolean preferInternal;
    private boolean internalOwned;
    private final Button gainButton;
    private final Button pulseModeButton;
    private Button pulseTempoButton;
    private Button pulseShapeButton;
    private LinearLayout pulseControls;
    private LinearLayout pulseShapeControls;
    // These are DSP-only envelope presets. They never assert a lamp power state.
    private static final int[][] PULSE_SHAPES = {
        {12, 65, 160, 900}, {8, 100, 70, 330}, {12, 90, 90, 600}
    };
    private static final double[] PULSE_GAMMAS = {2.2, 1.2, 2.0};
    private static final String[] PULSE_SHAPE_LABELS = {"Sanft", "Kräftig", "Normal"};
    private int pulseShape = 2;
    private static final int[] PULSE_TEMPOS = {60, 90, 120, 150, 180};
    private int pulseTempo = 2;
    private boolean beatPulse = true;
    private int scene = 0;
    private int gain = 1;
    private boolean locallyStarted = false;
    private String sessionOwner;
    private long requestedAtMs;
    private boolean micPermissionRequested;
    private static final long START_ACK_TIMEOUT_MS = 8_000L;
    // Local explicit-button request; the OEM permission callback is not our start trigger.
    private static final int MICROPHONE_PERMISSION_REQUEST = 0x6D41;
    private boolean attached = false;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            if (locallyStarted) {
                String failure = ClassicDjService.startFailureForOwner(sessionOwner);
                if (failure != null) {
                    // A service start can be accepted by Android but fail later.
                    // Only the exact owner may clear its UI state; never restart implicitly.
                    locallyStarted = false;
                    sessionOwner = null;
                    status.setText(failure + " · nach Ende erneut starten");
                } else if (ClassicDjService.hasActiveSession()) {
                    status.setText(ClassicDjService.uiStatus());
                } else if (SystemClock.elapsedRealtime() - requestedAtMs >= START_ACK_TIMEOUT_MS) {
                    // Do not drop ownership of a start still queued by Android.
                    status.setText("Analyse-Start nicht bestätigt · erst Stopp, dann erneut");
                } else {
                    status.setText("Mikrofonanalyse wird vorbereitet · keine Lampenbefehle");
                }
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

        sourceButton = new Button(activity);
        sourceButton.setText("Quelle: Mikrofon");
        sourceButton.setContentDescription("Analysequelle zwischen Mikrofon und internem Android-Audio wählen");
        sourceButton.setOnClickListener(v -> {
            if (locallyStarted || ClassicDjService.hasActiveSession()) {
                status.setText("Quelle nur nach Stopp wechseln");
                return;
            }
            preferInternal = !preferInternal;
            sourceButton.setText(preferInternal ?
                "Quelle: Internes Android-Audio" : "Quelle: Mikrofon");
        });
        expanded.addView(sourceButton);

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
            pulseControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
            pulseShapeControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
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

        // DSP-only pulse controls in the original MusicActivity hierarchy.
        // Never create a second color picker or synthesize OEM device identities.
        pulseControls = new LinearLayout(activity);
        pulseControls.setOrientation(HORIZONTAL);
        pulseControls.setVisibility(View.GONE);
        pulseModeButton = new Button(activity);
        pulseModeButton.setText("Puls: Beat");
        pulseModeButton.setContentDescription("Puls zwischen Beat und Zeitsteuerung umschalten");
        pulseModeButton.setOnClickListener(v -> {
            beatPulse = !beatPulse;
            pulseModeButton.setText(beatPulse ? "Puls: Beat" : "Puls: Timer");
            pulseTempoButton.setEnabled(!beatPulse);
            configureIfOwned();
        });
        pulseControls.addView(pulseModeButton, new LinearLayout.LayoutParams(0, -2, 1f));
        pulseTempoButton = new Button(activity);
        pulseTempoButton.setText("120 BPM");
        pulseTempoButton.setEnabled(false);
        pulseTempoButton.setContentDescription("Puls-Tempo für Zeitsteuerung");
        pulseTempoButton.setOnClickListener(v -> {
            pulseTempo = (pulseTempo + 1) % PULSE_TEMPOS.length;
            pulseTempoButton.setText(PULSE_TEMPOS[pulseTempo] + " BPM");
            configureIfOwned();
        });
        pulseControls.addView(pulseTempoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        expanded.addView(pulseControls);

        // Perceptual brightness/envelope shaping on the SAME Classic Music UI.
        // No OEM color-picker replacement and no unverified lamp selection.
        pulseShapeControls = new LinearLayout(activity);
        pulseShapeControls.setOrientation(HORIZONTAL);
        pulseShapeControls.setVisibility(View.GONE);
        pulseShapeButton = new Button(activity);
        pulseShapeButton.setText("Puls: Normal");
        pulseShapeButton.setContentDescription("Pulsform zwischen Normal, Sanft und Kräftig wechseln");
        pulseShapeButton.setOnClickListener(v -> {
            pulseShape = (pulseShape + 1) % PULSE_SHAPES.length;
            pulseShapeButton.setText("Puls: " + PULSE_SHAPE_LABELS[pulseShape]);
            configureIfOwned();
        });
        pulseShapeControls.addView(pulseShapeButton);
        expanded.addView(pulseShapeControls);
    }

    public void onHostResumed() {
        attached = true;
        ClassicInternalAudioConsentActivity.Result result =
            ClassicInternalAudioConsentActivity.takeResult();
        if (result != null && internalOwned && result.owner.equals(sessionOwner)) {
            if (result.started) {
                status.setText("Internes Audio freigegeben · Analyse startet");
            } else {
                locallyStarted = false;
                internalOwned = false;
                sessionOwner = null;
                status.setText("Interne Android-Audiofreigabe nicht erteilt");
            }
        }
        // A MediaProjection service may continue while the owner opens a
        // separate music app. Only reattach the exact same-process owned session.
        if (!locallyStarted) {
            String owned = ClassicDjService.ownerForInternal();
            if (owned != null) {
                locallyStarted = true;
                internalOwned = true;
                preferInternal = true;
                sessionOwner = owned;
                sourceButton.setText("Quelle: Internes Android-Audio");
            }
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    /** Do not wait for network/device state or keep an orphaned analyzer alive. */
    public void onHostPaused() {
        attached = false;
        handler.removeCallbacks(refresh);
        // PlaybackCapture must remain alive while the foreground app plays
        // permitted audio. Microphone analysis still stops with MusicActivity.
        if (!internalOwned) stopOwned();
        expanded.setVisibility(View.GONE);
    }

    private void startAnalysisOnly() {
        if (locallyStarted) {
            status.setText("Analyse bereits angefordert · bei Bedarf zuerst Stopp");
            return;
        }
        if (ClassicDjService.hasActiveSession() || ClassicDjService.isStopping()) {
            status.setText("Vorherige DJ-Sitzung noch aktiv oder wird beendet");
            return;
        }
        // Explicit button gesture, no automatic start on a later permission result.
        // Android owns the prompt; do not override any OEM Activity callback.
        if (Build.VERSION.SDK_INT >= 23 &&
                host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
            if (!micPermissionRequested) {
                micPermissionRequested = true;
                try {
                    host.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MICROPHONE_PERMISSION_REQUEST);
                    status.setText("Mikrofon erlauben · danach Analyse erneut tippen");
                } catch (RuntimeException blocked) {
                    status.setText("Mikrofon-Anfrage nicht möglich · App-Berechtigungen prüfen");
                }
            } else {
                status.setText("Mikrofon noch nicht erlaubt · App-Berechtigungen prüfen");
            }
            return;
        }
        String newOwner = java.util.UUID.randomUUID().toString();
        Intent intent = new Intent(host, preferInternal ?
            ClassicInternalAudioConsentActivity.class : ClassicDjService.class);
        intent.setAction(preferInternal ?
            ClassicDjService.ACTION_INTERNAL : ClassicDjService.ACTION_MIC);
        intent.putExtra(ClassicDjService.EXTRA_UI_OWNER, newOwner);
        intent.putExtra(ClassicDjActivity.EXTRA_SELECTED_IPS, new String[0]);
        intent.putExtra(ClassicDjService.EXTRA_GAIN, GAINS[gain]);
        intent.putExtra(ClassicDjService.EXTRA_SCENE, SCENES[scene]);
        addPulseSettings(intent);
        try {
            if (preferInternal) {
                if (Build.VERSION.SDK_INT < 29) {
                    status.setText("Internes Audio erfordert Android 10 oder neuer");
                    return;
                }
                host.startActivity(intent); // transient Android consent broker; no DJ screen
            } else if (Build.VERSION.SDK_INT >= 26) {
                host.startForegroundService(intent);
            } else {
                host.startService(intent);
            }
            sessionOwner = newOwner;
            internalOwned = preferInternal;
            locallyStarted = true;
            requestedAtMs = SystemClock.elapsedRealtime();
            status.setText(preferInternal ?
                "Interne Android-Audiofreigabe angefordert · keine Lampen" :
                "Mikrofonanalyse angefordert · keine Lampenbefehle");
        } catch (RuntimeException error) {
            status.setText("Analyse nicht gestartet · keine Lampenbefehle");
        }
    }

    private void addPulseSettings(Intent intent) {
        // The existing OEM picker remains the color authority.
        intent.putExtra(ClassicDjService.EXTRA_PULSE_MODE, beatPulse ? "BEAT" : "TIMER");
        intent.putExtra(ClassicDjService.EXTRA_PULSE_BPM, (double) PULSE_TEMPOS[pulseTempo]);
        int[] envelope = PULSE_SHAPES[pulseShape];
        intent.putExtra(ClassicDjService.EXTRA_PULSE_MIN, (double) envelope[0]);
        intent.putExtra(ClassicDjService.EXTRA_PULSE_MAX, (double) envelope[1]);
        intent.putExtra(ClassicDjService.EXTRA_PULSE_ATTACK, (double) envelope[2]);
        intent.putExtra(ClassicDjService.EXTRA_PULSE_DECAY, (double) envelope[3]);
        intent.putExtra(ClassicDjService.EXTRA_PULSE_GAMMA, PULSE_GAMMAS[pulseShape]);
    }

    private void configureIfOwned() {
        if (!locallyStarted || sessionOwner == null ||
                !ClassicDjService.hasActiveSession()) return;
        Intent intent = new Intent(host, ClassicDjService.class);
        intent.setAction(ClassicDjService.ACTION_CONFIG);
        intent.putExtra(ClassicDjService.EXTRA_UI_OWNER, sessionOwner);
        intent.putExtra(ClassicDjService.EXTRA_GAIN, GAINS[gain]);
        intent.putExtra(ClassicDjService.EXTRA_SCENE, SCENES[scene]);
        addPulseSettings(intent);
        try {
            host.startService(intent);
        } catch (RuntimeException error) {
            status.setText("Einstellung nicht übernommen");
        }
    }

    private void stopOwned() {
        if (!locallyStarted) return;
        String oldOwner = sessionOwner;
        locallyStarted = false;
        internalOwned = false;
        sessionOwner = null;
        if (oldOwner == null) return;
        // This same-process path fences an already-running session before
        // Android restricts a background startService on Activity pause.
        if (!ClassicDjService.stopOwnedFromUi(oldOwner)) {
            // Service startup may still be queued; enqueue a generation-matched
            // stop rather than blindly terminating another Activity's service.
            Intent intent = new Intent(host, ClassicDjService.class);
            intent.setAction(ClassicDjService.ACTION_STOP);
            intent.putExtra(ClassicDjService.EXTRA_UI_OWNER, oldOwner);
            try {
                host.startService(intent);
            } catch (RuntimeException blockedInBackground) {
                status.setText("Stopp unbestätigt · Sitzungsstatus prüfen");
                return;
            }
        }
        status.setText("Analyse wird beendet · Classic-Musik unverändert");
    }
}
