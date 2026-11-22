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
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
        {"SPECTRUM", "AMBIENT", "BEAT", "PULSE", "SCANNER", "METEOR", "WAVE"};
    private static final String[] SCENE_LABELS =
        {"Spektrum", "Ruhig", "Beat", "Puls", "Scanner", "Meteor", "Welle"};
    private static final double[] GAINS = {0.5, 1.0, 2.0, 4.0};
    private final Activity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final LinearLayout expanded;
    // In-place bounded controls. They reserve the OEM player's real viewport
    // instead of opening a modal dialog or overlapping the player.
    private final ScrollView inlineSheet;
    private final int compactHeightPx;
    private final int detailsHeightPx;
    interface DockHeightListener { void onHeightChanged(int pixels); }
    private DockHeightListener dockHeightListener;
    void setDockHeightListener(DockHeightListener callback) {
        dockHeightListener = callback;
        notifyDockHeight();
    }
    private void notifyDockHeight() {
        if (dockHeightListener != null)
            dockHeightListener.onHeightChanged(compactHeightPx +
                (inlineSheet.getVisibility() == View.VISIBLE ? detailsHeightPx : 0));
    }
    private final Button entryButton;
    private final Button startButton;
    private final TextView sourceHelp;
    private final TextView liveFeedback;
    private final TextView effectFeedback;
    private final ProgressBar[] bandMeters = new ProgressBar[4];
    private final ProgressBar effectMeter;
    private boolean consentFlowPending;
    private final TextView sceneButton;
    private final Button[] sceneChoices = new Button[SCENES.length];
    // UI-only controls of the SAME atomic SceneSettings transaction.
    private final LinearLayout spatialControls;
    private final Button spatialSpeedButton, spatialDirectionButton;
    private final Button spatialTrailButton, spatialRangeButton;
    private static final int[] SPATIAL_SPEEDS = {50, 75, 100, 150, 200};
    private static final int[] SPATIAL_TRAILS = {1, 2, 4, 6, 8};
    private static final int[][] SPATIAL_RANGES = {{1, 100}, {5, 60}, {20, 85}};
    private int spatialSpeedIndex = 2, spatialTrailIndex = 2, spatialRangeIndex = 0;
    private boolean spatialReverse;
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
    private long observedSceneRevision = -1L;
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
                    internalOwned = false;
                    sessionOwner = null;
                    status.setText(failure + " · erneut starten möglich");
                    entryButton.setText("Loft DJ · Fehler");
                } else if (ClassicDjService.hasActiveSession()) {
                    status.setText((internalOwned ? "Internes Audio" : "Mikrofon") +
                        " · " + ClassicDjService.uiStatus());
                } else if (SystemClock.elapsedRealtime() - requestedAtMs >= START_ACK_TIMEOUT_MS) {
                    // Do not drop ownership of a start still queued by Android.
                    status.setText("Analyse-Start nicht bestätigt · erst Stopp, dann erneut");
                } else {
                    status.setText((internalOwned ? "Interne Audiofreigabe" :
                        "Mikrofonanalyse") + " wird vorbereitet · keine Lampenbefehle");
                }
            }
            // A SceneFragment edit can occur while an internal-audio session
            // remains foreground-owned. Refresh visible controls on the real
            // MusicActivity, without implicitly starting or reconfiguring audio.
            if (ClassicSceneUiState.revision() != observedSceneRevision)
                syncSceneFromGallery();
            // Genuine FFT/beat-derived data, never synthetic device state.
            renderLiveFeedback();
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

        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        compactHeightPx = (int) (56f * dm.density + 0.5f) + pad * 2;
        detailsHeightPx = Math.max((int) (180f * dm.density + 0.5f),
            Math.min((int) (dm.heightPixels * 0.42f), (int) (360f * dm.density + 0.5f)));
        entryButton = new Button(activity);
        entryButton.setText("Licht & Musik");
        entryButton.setAllCaps(false);
        entryButton.setContentDescription("Loft DJ Steuerung in Classic-Musik einblenden");
        entryButton.setLayoutParams(new LinearLayout.LayoutParams(
            -1, (int) (56f * dm.density + 0.5f), 0f));
        addView(entryButton);

        // The original OEM view remains the direct child of android.R.id.content.
        expanded = new LinearLayout(activity);
        expanded.setOrientation(VERTICAL);
        expanded.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
        inlineSheet = new ScrollView(activity);
        inlineSheet.setFillViewport(false);
        inlineSheet.addView(expanded);
        inlineSheet.setVisibility(View.GONE);
        addView(inlineSheet, new LinearLayout.LayoutParams(-1, detailsHeightPx, 0f));
        entryButton.setOnClickListener(v -> {
            if (inlineSheet.getVisibility() == View.VISIBLE) closeControlSheet();
            else openControlSheet();
        });

        status = new TextView(activity);
        status.setText("Quelle wählen · keine Loft-DJ-Lampen freigegeben");
        status.setContentDescription("Loft DJ Status innerhalb Classic Music");
        expanded.addView(status);

        sourceButton = new Button(activity);
        sourceButton.setText("Quelle: Mikrofon");
        sourceButton.setContentDescription("Analysequelle zwischen Mikrofon und internem Android-Audio wählen");
        expanded.addView(sourceButton);
        sourceHelp = new TextView(activity);
        sourceHelp.setText("Mikrofon: Start verwendet nur den Ton der Umgebung, ohne Bildschirmfreigabe.");
        expanded.addView(sourceHelp);
        TextView zeroTargets = new TextView(activity);
        zeroTargets.setText("Effekte wirken hier zunächst nur auf die Auswertung. " +
            "Keine zusätzlichen Lampen freigegeben; Original-Musikfluss bleibt unabhängig.");
        expanded.addView(zeroTargets);

        // These four bars display real FFT bands from the existing DSP.
        // No made-up frequency bins, scanning, AudioRecord or lamp side effects.
        liveFeedback = new TextView(activity);
        liveFeedback.setText("PCM-Analyse noch nicht gestartet");
        liveFeedback.setContentDescription("Tatsächlich gemessener Audiostatus");
        expanded.addView(liveFeedback);
        String[] labels = {"Bass", "Mitten", "Präsenz", "Höhen"};
        for (int i = 0; i < labels.length; i++) {
            TextView caption = new TextView(activity);
            caption.setText(labels[i]);
            expanded.addView(caption);
            ProgressBar meter = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
            meter.setMax(100);
            meter.setProgress(0);
            meter.setContentDescription("Echtes FFT-Band " + labels[i]);
            bandMeters[i] = meter;
            expanded.addView(meter);
        }
        effectFeedback = new TextView(activity);
        effectFeedback.setText("Vorschau: Spektrum · vier tatsächlich gemessene Frequenzbänder");
        expanded.addView(effectFeedback);
        effectMeter = new ProgressBar(activity, null,
            android.R.attr.progressBarStyleHorizontal);
        effectMeter.setMax(100);
        effectMeter.setProgress(0);
        effectMeter.setContentDescription(
            "Nur berechnete Effektvorschau, keine Steuerung von Lampen");
        expanded.addView(effectMeter);

        // Two readable rows instead of four tightly packed action buttons.
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(HORIZONTAL);
        startButton = new Button(activity);
        startButton.setText("Analyse starten");
        startButton.setAllCaps(false);
        startButton.setContentDescription("Gewählte Audioquelle starten, ohne Lampen");
        startButton.setOnClickListener(v -> startAnalysisOnly());
        // Listener registration must follow construction of both final views;
        // otherwise javac correctly rejects uninitialized captured fields.
        sourceButton.setOnClickListener(v -> {
            if (locallyStarted || ClassicDjService.hasActiveSession()) {
                status.setText("Quelle nur nach Stopp wechseln");
                return;
            }
            preferInternal = !preferInternal;
            sourceButton.setText(preferInternal ?
                "Quelle: Internes Android-Audio" : "Quelle: Mikrofon");
            sourceHelp.setText(preferInternal ?
                "Interner Ton: Start öffnet die Android-Bildschirmfreigabe. " +
                "Wir verarbeiten nur erlaubtes App-Audio, kein Bildschirmvideo. " +
                "Eine Freigabe ist pro Sitzung erforderlich." :
                "Mikrofon: Start verwendet nur den Ton der Umgebung, ohne Bildschirmfreigabe.");
            startButton.setText(preferInternal ? "Audio freigeben" : "Analyse starten");
            entryButton.setText("Licht & Musik");
        });
        actions.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout settings = new LinearLayout(activity);
        settings.setOrientation(HORIZONTAL);
        sceneButton = new TextView(activity);
        sceneButton.setText("Lichtszene: " + SCENE_LABELS[scene]);
        settings.addView(sceneButton, new LinearLayout.LayoutParams(0, -2, 1f));
        // Readable two-column choices within the OEM MusicActivity viewport.
        // No second scene database or lamp selection.
        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            if (rowIndex == 0 || rowIndex == 2) {
                TextView heading = new TextView(activity);
                heading.setText(rowIndex == 0 ? "Musikreaktionen" : "Räumliche Lichtszene");
                expanded.addView(heading);
            }
            LinearLayout choices = new LinearLayout(activity);
            choices.setOrientation(HORIZONTAL);
            int first = rowIndex * 2;
            int limit = Math.min(first + 2, SCENES.length);
            for (int index = first; index < limit; index++) {
                final int selectedScene = index;
                Button choice = new Button(activity);
                choice.setAllCaps(false);
                choice.setOnClickListener(v -> selectScene(selectedScene));
                sceneChoices[index] = choice;
                choices.addView(choice, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            expanded.addView(choices);
        }
        refreshSceneChoices();

        spatialControls = new LinearLayout(activity);
        spatialControls.setOrientation(VERTICAL);
        spatialControls.setVisibility(View.GONE);
        TextView hint = new TextView(activity);
        hint.setText("Szenenparameter · Farbe bleibt beim Classic-Effekt. " +
            "Ohne verifizierte Lampenauswahl nur Vorschau.");
        spatialControls.addView(hint);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(HORIZONTAL);
        spatialSpeedButton = new Button(activity);
        spatialSpeedButton.setAllCaps(false);
        spatialSpeedButton.setContentDescription("Szenengeschwindigkeit einstellen");
        spatialSpeedButton.setOnClickListener(v -> {
            spatialSpeedIndex = (spatialSpeedIndex + 1) % SPATIAL_SPEEDS.length;
            updateSpatialLabels();
            publishSharedScene();
            configureIfOwned();
        });
        top.addView(spatialSpeedButton, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialDirectionButton = new Button(activity);
        spatialDirectionButton.setAllCaps(false);
        spatialDirectionButton.setContentDescription("Richtung der Lichtszene umschalten");
        spatialDirectionButton.setOnClickListener(v -> {
            spatialReverse = !spatialReverse;
            updateSpatialLabels();
            publishSharedScene();
            configureIfOwned();
        });
        top.addView(spatialDirectionButton, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialControls.addView(top);

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(HORIZONTAL);
        spatialTrailButton = new Button(activity);
        spatialTrailButton.setAllCaps(false);
        spatialTrailButton.setContentDescription("Schweiflänge der Lichtszene einstellen");
        spatialTrailButton.setOnClickListener(v -> {
            spatialTrailIndex = (spatialTrailIndex + 1) % SPATIAL_TRAILS.length;
            updateSpatialLabels();
            publishSharedScene();
            configureIfOwned();
        });
        bottom.addView(spatialTrailButton, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialRangeButton = new Button(activity);
        spatialRangeButton.setAllCaps(false);
        spatialRangeButton.setContentDescription("Helligkeitsbereich der Lichtszene einstellen");
        spatialRangeButton.setOnClickListener(v -> {
            spatialRangeIndex = (spatialRangeIndex + 1) % SPATIAL_RANGES.length;
            updateSpatialLabels();
            publishSharedScene();
            configureIfOwned();
        });
        bottom.addView(spatialRangeButton, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialControls.addView(bottom);
        expanded.addView(spatialControls);
        updateSpatialLabels();

        gainButton = new Button(activity);
        gainButton.setText("Empfindlichkeit: 1×");
        gainButton.setOnClickListener(v -> {
            gain = (gain + 1) % GAINS.length;
            gainButton.setText("Empfindlichkeit: " + GAINS[gain] + "×");
            configureIfOwned();
        });
        settings.addView(gainButton, new LinearLayout.LayoutParams(0, -2, 1f));

        Button stop = new Button(activity);
        stop.setText("Stopp");
        stop.setContentDescription("Eigene Loft DJ Audioanalyse stoppen");
        stop.setOnClickListener(v -> stopOwned());
        actions.addView(stop, new LinearLayout.LayoutParams(0, -2, 1f));
        expanded.addView(actions);
        expanded.addView(settings);

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
            effectFeedback.setText("Vorschau: " +
                ClassicDjEffectPreview.explanation(SCENES[scene], beatPulse));
            renderLiveFeedback();
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
            renderLiveFeedback();
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

    private void refreshSceneChoices() {
        for (int i = 0; i < sceneChoices.length; i++) {
            Button choice = sceneChoices[i];
            if (choice == null) continue;
            boolean selected = i == scene;
            choice.setText((selected ? "✓ " : "") + SCENE_LABELS[i]);
            choice.setContentDescription(SCENE_LABELS[i] +
                (selected ? ", ausgewählte Lichtszene" : ", Lichtszene auswählen"));
        }
    }

    private void updateSpatialLabels() {
        spatialSpeedButton.setText("Tempo " + SPATIAL_SPEEDS[spatialSpeedIndex] + "%");
        spatialDirectionButton.setText(spatialReverse ? "Rückwärts" : "Vorwärts");
        spatialTrailButton.setText("Schweif " + SPATIAL_TRAILS[spatialTrailIndex]);
        spatialRangeButton.setText("Helligkeit " +
            SPATIAL_RANGES[spatialRangeIndex][0] + "–" +
            SPATIAL_RANGES[spatialRangeIndex][1] + "%");
    }

    /** The original SceneFragment and MusicActivity share one transient scene model. */
    private void publishSharedScene() {
        ClassicSceneUiState.update(scene, spatialSpeedIndex, spatialReverse,
            spatialTrailIndex, spatialRangeIndex);
        ClassicSceneUiState.updateAudio(gain, beatPulse, pulseTempo, pulseShape);
        observedSceneRevision = ClassicSceneUiState.revision();
    }

    private void syncSceneFromGallery() {
        ClassicSceneUiState.Selection stored = ClassicSceneUiState.snapshot();
        scene = stored.scene;
        spatialSpeedIndex = stored.speedIndex;
        spatialReverse = stored.reverse;
        spatialTrailIndex = stored.trailIndex;
        spatialRangeIndex = stored.rangeIndex;
        gain = stored.gainIndex;
        beatPulse = stored.beatPulse;
        pulseTempo = stored.pulseTempoIndex;
        pulseShape = stored.pulseShapeIndex;
        gainButton.setText("Empfindlichkeit: " + GAINS[gain] + "×");
        pulseModeButton.setText(beatPulse ? "Puls: Beat" : "Puls: Timer");
        pulseTempoButton.setText(PULSE_TEMPOS[pulseTempo] + " BPM");
        pulseTempoButton.setEnabled(!beatPulse);
        pulseShapeButton.setText("Puls: " + PULSE_SHAPE_LABELS[pulseShape]);
        refreshSceneChoices();
        sceneButton.setText("Lichtszene: " + SCENE_LABELS[scene]);
        spatialControls.setVisibility(scene >= 4 ? View.VISIBLE : View.GONE);
        pulseControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
        pulseShapeControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
        updateSpatialLabels();
        observedSceneRevision = ClassicSceneUiState.revision();
        renderLiveFeedback();
    }

    private void selectScene(int requested) {
        if (requested < 0 || requested >= SCENES.length) return;
        scene = requested;
        publishSharedScene();
        refreshSceneChoices();
        sceneButton.setText("Lichtszene: " + SCENE_LABELS[scene]);
        spatialControls.setVisibility(scene >= 4 ? View.VISIBLE : View.GONE);
        pulseControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
        pulseShapeControls.setVisibility(scene == 3 ? View.VISIBLE : View.GONE);
        effectFeedback.setText("Vorschau: " +
            ClassicDjEffectPreview.explanation(SCENES[scene], beatPulse));
        renderLiveFeedback();
        configureIfOwned();
    }

    /** Poll only this session's fresh source-local measurements. */
    private void renderLiveFeedback() {
        ClassicDjLiveFrame frame = ClassicDjService.frameForOwner(sessionOwner);
        liveFeedback.setText(frame.summary());
        float[] bands = frame.fresh() ? frame.bands() : new float[4];
        for (int i = 0; i < bandMeters.length; i++) {
            bandMeters[i].setProgress(Math.round(bands[i] * 100f));
        }
        effectFeedback.setText("Nur Vorschau · " +
            ClassicDjEffectPreview.explanation(SCENES[scene], beatPulse));
        effectMeter.setProgress(ClassicDjEffectPreview.intensity(
            SCENES[scene], frame, beatPulse, PULSE_TEMPOS[pulseTempo],
            SystemClock.elapsedRealtime()));
    }

    /** No dialog or new Activity; the embedded view reserves OEM viewport space. */
    private void openControlSheet() {
        if (host.isFinishing() || host.isDestroyed() ||
            inlineSheet.getVisibility() == View.VISIBLE) return;
        inlineSheet.setVisibility(View.VISIBLE);
        notifyDockHeight();
    }
    private void closeControlSheet() {
        if (inlineSheet.getVisibility() == View.GONE) return;
        inlineSheet.setVisibility(View.GONE);
        notifyDockHeight();
    }

    public void onHostResumed() {
        attached = true;
        syncSceneFromGallery();
        ClassicInternalAudioConsentActivity.Result result =
            ClassicInternalAudioConsentActivity.takeResult();
        boolean returnFromConsent = consentFlowPending && result != null;
        if (result != null) consentFlowPending = false;
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
                startButton.setText("Audio freigeben");
                sourceHelp.setText(
                    "Interner Ton: Start öffnet die Android-Bildschirmfreigabe. " +
                    "Wir verarbeiten nur erlaubtes App-Audio, kein Bildschirmvideo. " +
                    "Eine Freigabe ist pro Sitzung erforderlich.");
            }
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        renderLiveFeedback();
        if (returnFromConsent) openControlSheet();
    }

    /** Do not wait for network/device state or keep an orphaned analyzer alive. */
    public void onHostPaused() {
        attached = false;
        handler.removeCallbacks(refresh);
        // PlaybackCapture must remain alive while the foreground app plays
        // permitted audio. Microphone analysis still stops with MusicActivity.
        if (!internalOwned) stopOwned();
        // Collapse only our embedded controls while the original host pauses.
        closeControlSheet();
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
        addSpatialSettings(intent);
        try {
            if (preferInternal) {
                if (Build.VERSION.SDK_INT < 29) {
                    status.setText("Internes Audio erfordert Android 10 oder neuer");
                    return;
                }
                consentFlowPending = true;
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
            consentFlowPending = false;
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

    private void addSpatialSettings(Intent intent) {
        // The Service reads these as doubles, validates the complete snapshot
        // and applies it ONCE. Do not supply a fake original picker colour.
        intent.putExtra(ClassicDjService.EXTRA_SPATIAL_SPEED,
            (double) SPATIAL_SPEEDS[spatialSpeedIndex]);
        intent.putExtra(ClassicDjService.EXTRA_SPATIAL_REVERSE, spatialReverse);
        intent.putExtra(ClassicDjService.EXTRA_SPATIAL_TRAIL,
            (double) SPATIAL_TRAILS[spatialTrailIndex]);
        intent.putExtra(ClassicDjService.EXTRA_SPATIAL_MIN,
            (double) SPATIAL_RANGES[spatialRangeIndex][0]);
        intent.putExtra(ClassicDjService.EXTRA_SPATIAL_MAX,
            (double) SPATIAL_RANGES[spatialRangeIndex][1]);
    }

    private void configureIfOwned() {
        // Every explicit UI change updates one shared, transient scene model,
        // even when audio is stopped. Never auto-start a source or lamp.
        publishSharedScene();
        if (!locallyStarted || sessionOwner == null ||
                !ClassicDjService.hasActiveSession()) return;
        Intent intent = new Intent(host, ClassicDjService.class);
        intent.setAction(ClassicDjService.ACTION_CONFIG);
        intent.putExtra(ClassicDjService.EXTRA_UI_OWNER, sessionOwner);
        intent.putExtra(ClassicDjService.EXTRA_GAIN, GAINS[gain]);
        intent.putExtra(ClassicDjService.EXTRA_SCENE, SCENES[scene]);
        addPulseSettings(intent);
        addSpatialSettings(intent);
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
        entryButton.setText("Licht & Musik");
    }
}
