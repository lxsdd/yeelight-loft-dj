package io.github.lxsdd.yeelightclassicdj.ui;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.List;
import java.util.ArrayList;
import io.github.lxsdd.yeelightclassicdj.runtime.CheckpointRuntime;
import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;
import io.github.lxsdd.yeelightclassicdj.runtime.ClassicOriginalDspAnalysis;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Embedded effect-only controls in the genuine original Classic MusicActivity.
 *
 * The original DiscoFragment is the ONLY visible owner of audio source,
 * microphone permission, Play/Pause and its music-device controls. Original
 * AudioRecord/Visualizer frames now feed the local effect signal directly,
 * with NO second capture engine or independent ClassicDjService session.
 * Timing geometry still uses abstract preview cells, never device targets.
 *
 * The old diagnostic Class/Service remains a separately gated test path; it
 * must never be wired back into this primary Original-Classic music surface.
 * Programmatic tones are consistent with the Classic pink visual identity,
 * not a claim that OEM Classic has been wholly migrated to Material 3.
 */
public final class ClassicMusicInlineControls extends LinearLayout {
    private static final int PINK = Color.rgb(255, 46, 121);
    private static final int INK = Color.rgb(54, 48, 62);
    private static final int MUTED = Color.rgb(106, 98, 112);
    private static final int TINT = Color.rgb(255, 236, 244);
    private static final String[] NAMES = ClassicSceneUiState.LABELS;

    private final Activity host;
    // Observe the genuine OEM recorder/Visualizer with Loft DJ's shared FFT/beat core.
    private final ClassicOriginalDspAnalysis originalDsp = new ClassicOriginalDspAnalysis();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView header;
    private final ScrollView inlineSheet;
    private final LinearLayout content;
    private final TextView originalMusicSelectionStatus;
    private final TextView description;
    private final TextView previewCohortStatus;
    private final TextView stateNotice;
    private final TextView lightControl;
    private final Object lightGuard = new Object();
    private volatile CheckpointRuntime lightRuntime;
    // The confirmed owner cannot silently switch between original audio and
    // clock-only playback when the OEM player/source or scene changes.
    private volatile int lightMode = ClassicOriginalLightMode.DENY;
    private volatile ClassicOriginalMusicSelection.OutputLease lightLease;
    private int lightEpoch;
    // One process-wide output lease survives Activity recreation and async STOP.
    private long lightSessionToken;
    // OEM layout rechecks cannot install the same capture observer again.
    private final ClassicOriginalMusicHostLifecycle hostLifecycle =
        new ClassicOriginalMusicHostLifecycle();
    private volatile long originalObserverEpoch;
    private volatile String lightStatus = "";
    private volatile boolean lightConfirmed; // Kotlin OWNED group ACK, not a proposed selection
    private volatile int lightConnectedCount; // Partial reverse ACK, never an active group
    private volatile int lastStopReason = ClassicOriginalLightReport.NONE;
    private final ClassicDjPatternPreviewView preview;
    private final TextView[] choices = new TextView[NAMES.length];
    private final TextView[] favoriteChips =
        new TextView[ClassicEffectSettingsStore.FAVORITE_SLOTS];
    private final ClassicEffectOrder effectOrder;
    private final ClassicEffectSettingsStore effectSettings;
    private final LinearLayout chips;
    private final int compactHeightPx;
    private final int detailsHeightPx;
    private volatile boolean attached;
    private long shownRevision = -1L;
    private int shownGroupCount = Integer.MIN_VALUE;
    private int shownVerifiedIdentityCount = Integer.MIN_VALUE;
    private int shownDjIntentCount = Integer.MIN_VALUE;
    private int shownLanVerifiedCount = Integer.MIN_VALUE;
    private boolean shownDeviceWitness;
    private boolean shownLightRunning;
    private boolean shownLightConfirmed;
    private int activeScene = -1;
    interface DockHeightListener { void onHeightChanged(int pixels); }
    private DockHeightListener dockHeightListener;

    private static GradientDrawable rounded(int color, int corner, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(corner);
        if (stroke != 0) d.setStroke(1, stroke);
        return d;
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView caption(String text, int sp, int color) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    public ClassicMusicInlineControls(Activity activity) {
        super(activity);
        host = activity;
        effectOrder = new ClassicEffectOrder(activity);
        effectSettings = new ClassicEffectSettingsStore(activity);
        effectSettings.restoreOnFirstHost();
        setTag("io.github.lxsdd.yeelightclassicdj.music.inline");
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        setPadding(dp(10), dp(5), dp(10), dp(5));

        // A smaller, genuinely reserved viewport avoids clipping the original
        // WaveView and gives the OEM player back most of the display height.
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        compactHeightPx = dp(46) + dp(10);
        detailsHeightPx = Math.min(dp(242), Math.max(dp(170), (int)(screen * 0.26f)));

        header = caption("Licht-Effekte  ·  Vorschau    ﹀", 15, Color.WHITE);
        header.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        header.setBackground(rounded(PINK, dp(22), 0));
        header.setContentDescription("Licht-Effekte innerhalb der Original-Classic-Musikansicht öffnen");
        header.setClickable(true);
        addView(header, new LinearLayout.LayoutParams(-1, dp(46)));

        inlineSheet = new ScrollView(activity);
        inlineSheet.setFillViewport(false);
        content = new LinearLayout(activity);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(4), dp(12), dp(4), dp(8));
        content.setBackground(rounded(Color.WHITE, dp(18), 0));
        inlineSheet.addView(content);
        inlineSheet.setVisibility(View.GONE);
        addView(inlineSheet, new LinearLayout.LayoutParams(-1, detailsHeightPx));
        header.setOnClickListener(v -> {
            if (inlineSheet.getVisibility() == View.VISIBLE) closeSheet();
            else openSheet();
        });

        TextView source = caption(
            "Audioquelle und Start/Stopp: oben in Classic. Keine zweite Aufnahme.", 12, MUTED);
        source.setContentDescription(
            "Originale Classic-Musiksteuerung ist die einzige Audioquellen- und Wiedergabesteuerung");
        content.addView(source);

        TextView title = caption("Lichteffekt auswählen", 16, INK);
        title.setPadding(0, dp(10), 0, dp(7));
        content.addView(title);

        HorizontalScrollView effectsScroll = new HorizontalScrollView(activity);
        effectsScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(activity);
        chips.setOrientation(HORIZONTAL);
        for (int i = 0; i < NAMES.length; i++) {
            final int choice = i;
            TextView chip = caption(NAMES[i], 14, INK);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(10), dp(15), dp(10));
            chip.setClickable(true);
            chip.setContentDescription("Effekt " + NAMES[i] + " als Vorschau auswählen");
            chip.setOnClickListener(v -> selectScene(choice));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.setMargins(0, 0, dp(7), 0);
            chips.addView(chip, p);
            choices[i] = chip;
        }
        effectsScroll.addView(chips);
        content.addView(effectsScroll);
        renderEffectChips();
        TextView favoriteHeading = caption(
            "DJ-Favoriten aus der Classic-Szenengalerie", 12, MUTED);
        content.addView(favoriteHeading);
        HorizontalScrollView savedScroll = new HorizontalScrollView(activity);
        savedScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout savedRow = new LinearLayout(activity);
        savedRow.setOrientation(HORIZONTAL);
        for (int i = 0; i < favoriteChips.length; i++) {
            final int slot = i;
            TextView chip = caption("★ " + (i + 1) + " Leer", 13, INK);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setClickable(true);
            chip.setOnClickListener(v -> selectFavorite(slot));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.setMargins(0, 0, dp(6), 0);
            savedRow.addView(chip, p);
            favoriteChips[i] = chip;
        }
        savedScroll.addView(savedRow);
        content.addView(savedScroll);

        preview = new ClassicDjPatternPreviewView(activity);
        // The selected 14/32+ positions occupy responsive rows, not a fake
        // eight-cell strip. The sheet scrolls without covering original OEM UI.
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(-1, dp(84));
        pv.setMargins(0, dp(9), 0, dp(5));
        content.addView(preview, pv);
        preview.setContentDescription(
            "Schematische Lichtfolge; weder physische Geräteposition noch gemessener Lampenzustand");
        previewCohortStatus = caption(
            ClassicDjPreviewCohort.from(0, 0, false).label(), 12, MUTED);
        previewCohortStatus.setContentDescription(
            "Anzahl und Nachweisart der originalen Classic-DJ-Auswahl");
        content.addView(previewCohortStatus);

        description = caption("", 13, INK);
        content.addView(description);

        originalMusicSelectionStatus = caption("", 12, MUTED);
        originalMusicSelectionStatus.setPadding(0, dp(9), 0, 0);
        originalMusicSelectionStatus.setContentDescription(
            "Lesende Original-Classic-Gruppenliste; keine autorisierte DJ-Auswahl");
        content.addView(originalMusicSelectionStatus);

        lightControl = caption("DJ-Lichtausgabe: LAN-Abgleich erforderlich", 14, Color.WHITE);
        lightControl.setGravity(Gravity.CENTER);
        lightControl.setPadding(dp(10),dp(10),dp(10),dp(10));
        lightControl.setBackground(rounded(PINK,dp(14),0));
        lightControl.setContentDescription(
            "Nur explizit ausgewählte und per Yeelight-LAN verifizierte Classic-Lampen steuern; " +
            "Original-Classic ist alleiniger Audio-Player");
        lightControl.setOnClickListener(v -> {
            if (lightRuntime != null) stopLightOutput();
            else confirmLightStart();
        });
        content.addView(lightControl);

        stateNotice = caption(
            "Nur Simulation ohne Original-Audio; keine Lampe wird angesteuert.", 12, MUTED);
        stateNotice.setPadding(0, dp(7), 0, 0);
        content.addView(stateNotice);

        // Original OEM owns microphone, internal playback and transport.
        // No independent interactive recording controls are added here.
        redraw();
    }

    void setDockHeightListener(DockHeightListener callback) {
        dockHeightListener = callback;
        notifyDockHeight();
    }

    private void notifyDockHeight() {
        if (dockHeightListener != null) dockHeightListener.onHeightChanged(
            compactHeightPx + (inlineSheet.getVisibility() == View.VISIBLE ? detailsHeightPx : 0));
    }

    private void openSheet() {
        if (host.isFinishing() || host.isDestroyed()) return;
        if (inlineSheet.getVisibility() == View.VISIBLE) return;
        inlineSheet.setVisibility(View.VISIBLE);
        header.setText("Licht-Effekte  ·  Vorschau    ﹀");
        notifyDockHeight();
        redraw();
    }

    private void closeSheet() {
        if (inlineSheet.getVisibility() == View.GONE) return;
        inlineSheet.setVisibility(View.GONE);
        notifyDockHeight();
    }

    /** Original MusicActivity reads SceneFragment's DJ effect order without
     * opening another audio source or writing original OEM scenes.
     */
    private void renderEffectChips() {
        chips.removeAllViews();
        for (String id : effectOrder.order()) {
            int index = ClassicEffectOrder.sceneIndex(id);
            if (index < 0 || choices[index] == null) continue;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(0, 0, dp(7), 0);
            chips.addView(choices[index], params);
        }
    }

    private void selectScene(int index) {
        if (!attached || index < 0 || index >= NAMES.length) return;
        // Atomic revisioned shared scene UI (same original SceneFragment); no
        // fabricated OEM scene record, remote effect or secondary database.
        if (!effectSettings.selectScene(index)) return;
        boolean saved = effectSettings.saveExplicit(ClassicSceneUiState.snapshot());
        // Only redraw may reconfigure an existing Music output: it first
        // revalidates the live source/mode and participant lease.
        redraw();
        if (!saved) stateNotice.setText(
            "DJ-Effekt gewählt, aber lokale Speicherung fehlgeschlagen.");
    }

    /** Load a full clone-local DJ profile inside the ORIGINAL MusicActivity.
     * Never call OEM favourites, create a recorder or start Music transport.
     */
    private void selectFavorite(int slot) {
        if (!attached || !effectSettings.selectFavorite(slot)) return;
        boolean saved = effectSettings.saveExplicit(ClassicSceneUiState.snapshot());
        // Only redraw may reconfigure an existing Music output: it first
        // revalidates the live source/mode and participant lease.
        redraw();
        if (!saved) stateNotice.setText(
            "DJ-Favorit geladen, aber lokale Speicherung fehlgeschlagen.");
    }

    /** The original Classic SceneFragment and MusicActivity share this exact
     * state. In-place scene updates do not change participant ownership or
     * negotiate another Music socket. Rejected settings keep the prior frame.
     */
    private boolean applyLightSettings() {
        CheckpointRuntime runtime = lightRuntime;
        if (runtime == null) return false;
        // Apply the exact saved Original-SceneFragment pulse shape, gain,
        // timer mode and geometry to the OWNED Music output in one update.
        // Never silently substitute an unrelated beat envelope.
        return runtime.applySceneSettings(
            ClassicSceneOutputProfile.fromSelection(ClassicSceneUiState.snapshot()));
    }

    /** No source fabrication: timed spatial/timer-pulse scenes can use the
     * existing owned Music output WITHOUT starting the original audio player.
     */
    private boolean modeStillAllowed(int mode) {
        ClassicSceneUiState.Selection selected = ClassicSceneUiState.snapshot();
        return ClassicOriginalLightMode.stillPermitted(mode,
            ClassicSceneUiState.SCENES[selected.scene], selected.beatPulse,
            ClassicOriginalMusicAudioSignal.snapshot().fresh);
    }

    /** Only an explicit in-app confirmation may start reverse Music output.
     * LAN identity proof itself never implies permission to light a bulb.
     */
    private void confirmLightStart() {
        if (!attached || host.isFinishing() || host.isDestroyed()) return;
        final ClassicOriginalMusicSelection.OutputLease consent =
            ClassicOriginalMusicSelection.leaseForOriginalOutput();
        final List<String> proposed = consent == null ? new ArrayList<>() : consent.targets();
        ClassicSceneUiState.Selection selected = ClassicSceneUiState.snapshot();
        final long sceneRevision = ClassicSceneUiState.revision();
        final int mode = ClassicOriginalLightMode.choose(
            ClassicSceneUiState.SCENES[selected.scene], selected.beatPulse,
            ClassicOriginalMusicAudioSignal.snapshot().fresh);
        if (proposed.isEmpty() || mode == ClassicOriginalLightMode.DENY) {
            lightStatus = proposed.isEmpty()
                ? "Eindeutiger LAN-Abgleich der ausgewählten Lampen erforderlich"
                : "Original-Audio erforderlich oder zeitgesteuerten Effekt wählen";
            redraw();
            return;
        }
        final boolean timed = mode == ClassicOriginalLightMode.TIMED;
        new AlertDialog.Builder(host)
            .setTitle(timed ? "Zeitgesteuerte Lichtszene starten" :
                              "DJ-Musiklicht starten")
            .setMessage((timed
                ? "Diese Lichtszene läuft nach Zeitsteuerung ohne Mikrofon oder Visualizer. "
                : "Die Original-Classic-Aufnahme bleibt alleiniger Audio-Player. ") +
                "Für " + proposed.size() + " ausdrücklich ausgewählte, eindeutig " +
                "bestätigte Yeelight-Lampen wird der echte Music-Rückkanal geöffnet. " +
                "Andere Lampen bleiben unverändert. Beim Verlassen der Musikansicht wird gestoppt.")
            .setNegativeButton("Abbrechen",null)
            .setPositiveButton("Ausgewählte Lampen steuern",(dialog,which) -> {
                if (!attached || ClassicSceneUiState.revision() != sceneRevision ||
                    !modeStillAllowed(mode) ||
                    !ClassicOriginalMusicSelection.isOutputLeaseCurrent(consent) ||
                    !proposed.equals(ClassicOriginalMusicSelection.resolvedOriginalLanIps())) {
                    lightStatus = "Auswahl, Lichtszene oder Audioquelle nicht mehr gültig";
                    redraw();
                    return;
                }
                beginLightOutput(consent, mode);
            }).show();
    }

    private void beginLightOutput(ClassicOriginalMusicSelection.OutputLease consent,
                                  int mode) {
        final List<String> targets = consent.targets();
        final CheckpointRuntime runtime = new CheckpointRuntime();
        final int epoch;
        final long token;
        synchronized (lightGuard) {
            if (lightRuntime != null || !attached || targets.isEmpty() ||
                !ClassicOriginalMusicSelection.isOutputLeaseCurrent(consent) ||
                !modeStillAllowed(mode)) return;
            // Do not race a previous Activity's still-closing owned sockets.
            token = ClassicOriginalLightOutputGate.tryAcquire();
            if (token == 0L) {
                lightStatus = "Vorherige DJ-Lichtsession noch nicht sicher beendet";
                return;
            }
            lightSessionToken = token;
            lightMode = mode;
            lightRuntime = runtime;
            lightLease = consent;
            epoch = ++lightEpoch;
            lightConfirmed = false;
            lightConnectedCount = 0;
            lastStopReason = ClassicOriginalLightReport.NONE;
            lightStatus = ClassicOriginalLightReport.starting(targets.size());
        }
        redraw();
        new Thread(() -> {
            try {
                if (!applyLightSettings()) throw new IllegalStateException("Scene rejected");
                synchronized (lightGuard) {
                    if (lightRuntime != runtime || epoch != lightEpoch)
                        throw new IllegalStateException("Original host revoked");
                }
                DjRuntime.Listener outputListener = new DjRuntime.Listener() {
                    @Override public void onState(String state) {
                        handler.post(() -> {
                            synchronized (lightGuard) {
                                if (lightRuntime != runtime || epoch != lightEpoch) return;
                                // Only an exact, full-cohort OWNED ACK makes a lamp count active.
                                boolean verified = state.equals(
                                    (mode == ClassicOriginalLightMode.TIMED ?
                                        "SCENE_TIMED_" : "ORIGINAL_AUDIO_") +
                                    targets.size() + "_LAMPS");
                                if (verified) {
                                    lightConnectedCount = targets.size();
                                    lightConfirmed = true;
                                    lightStatus = ClassicOriginalLightReport.active(
                                        targets.size(), mode == ClassicOriginalLightMode.TIMED);
                                } else if ("STOP_UNCONFIRMED".equals(state)) {
                                    lightConfirmed = false;
                                    lightConnectedCount = 0;
                                    lightStatus = ClassicOriginalLightReport.uncertain(runtime.lanFailureCode());
                                } else {
                                    int progress = ClassicOriginalLightReport.confirmedProgress(
                                        state, targets.size());
                                    if (!lightConfirmed && progress > lightConnectedCount) {
                                        lightConnectedCount = progress;
                                        lightStatus = ClassicOriginalLightReport.connecting(
                                            progress, targets.size());
                                    }
                                }
                            }
                            if (attached) redraw();
                        });
                    }
                    @Override public void onBeat(double bpm,float level) {}
                    @Override public void onError(String error) {
                        handler.post(() -> {
                            synchronized (lightGuard) {
                                if (lightRuntime != runtime || epoch != lightEpoch) return;
                                lightStatus = ClassicOriginalLightReport.stopping(reasonForCurrentOutput());
                            }
                            stopLightOutput(runtime, epoch);
                        });
                    }
                };
                final DjRuntime.ConsentGuard outputGuard = () ->
                    attached && lightRuntime == runtime && lightMode == mode &&
                    ClassicOriginalMusicSelection.isOutputLeaseCurrent(consent) &&
                    modeStillAllowed(mode);
                if (mode == ClassicOriginalLightMode.TIMED)
                    runtime.startOriginalTimedScene(targets, outputListener, outputGuard);
                else
                    runtime.startOriginalAudio(targets, outputListener, outputGuard);
                synchronized (lightGuard) {
                    if (lightRuntime != runtime || epoch != lightEpoch) runtime.stop();
                }
            } catch (Exception | LinkageError failed) {
                // A partially negotiated group must finish owned cleanup BEFORE
                // a subsequent Activity or start attempt can acquire output.
                synchronized (lightGuard) {
                    if (lightRuntime == runtime && epoch == lightEpoch) {
                        lightRuntime = null;
                        lightLease = null;
                        lightMode = ClassicOriginalLightMode.DENY;
                        lightSessionToken = 0L;
                        lightConfirmed = false;
                        lightConnectedCount = 0;
                        ++lightEpoch;
                        lightStatus = ClassicOriginalLightReport.startFailure(runtime.lanFailureCode());
                    }
                }
                finishLightSession(runtime, token, -1);
                handler.post(() -> { if (attached) redraw(); });
            }
        },"Classic-DJ-Original-Output").start();
    }

    private void finishLightSession(CheckpointRuntime runtime, long token, int stopEpoch) {
        if (token == 0L || !ClassicOriginalLightOutputGate.beginClose(token)) return;
        Runnable cleanup = () -> {
            boolean clean = false;
            try {
                runtime.stop();
                clean = true;
            } catch (RuntimeException | LinkageError ignored) {
                // An uncertain reverse-session cleanup must block re-acquisition.
            } finally {
                final boolean finished =
                    ClassicOriginalLightOutputGate.finishClose(token, clean);
                handler.post(() -> {
                    synchronized (lightGuard) {
                        if (stopEpoch >= 0 && lightEpoch == stopEpoch &&
                            lightRuntime == null) {
                            lightStatus = finished ?
                                ClassicOriginalLightReport.stopped(lastStopReason) :
                                ClassicOriginalLightReport.uncertain(runtime.lanFailureCode());
                        }
                    }
                    if (attached) redraw();
                });
            }
        };
        try {
            new Thread(cleanup, "Classic-DJ-Original-Stop").start();
        } catch (RuntimeException threadUnavailable) {
            // Last-resort synchronous close is safer than leaving owned sockets.
            cleanup.run();
        }
    }

    private void stopLightOutput() {
        stopLightOutput(null, 0, ClassicOriginalLightReport.USER_STOP);
    }
    private void stopLightOutput(int reason) {
        stopLightOutput(null, 0, reason);
    }

    /** Only fixed, non-identifying reasons from the actual Original UI/session. */
    private int reasonForCurrentOutput() {
        ClassicOriginalMusicSelection.OutputLease lease = lightLease;
        if (lease == null || !ClassicOriginalMusicSelection.isOutputLeaseCurrent(lease))
            return ClassicOriginalMusicSelection.outputLeaseChangeReason(lease);
        if (!attached) return ClassicOriginalLightReport.HOST_PAUSED;
        if (!modeStillAllowed(lightMode))
            return lightMode == ClassicOriginalLightMode.ORIGINAL_AUDIO &&
                !ClassicOriginalMusicAudioSignal.snapshot().fresh ?
                ClassicOriginalLightReport.AUDIO_LOST :
                ClassicOriginalLightReport.SCENE_CHANGED;
        return ClassicOriginalLightReport.TRANSPORT_LOST;
    }

    /** Stale error callbacks must never stop a subsequently acquired session. */
    private void stopLightOutput(CheckpointRuntime expected, int expectedEpoch) {
        stopLightOutput(expected, expectedEpoch, reasonForCurrentOutput());
    }
    private void stopLightOutput(CheckpointRuntime expected, int expectedEpoch,
                                 int reason) {
        final CheckpointRuntime old;
        final long token;
        final int stoppedEpoch;
        synchronized (lightGuard) {
            if (expected != null &&
                (lightRuntime != expected || lightEpoch != expectedEpoch)) return;
            stoppedEpoch = ++lightEpoch;
            old = lightRuntime;
            token = lightSessionToken;
            lightRuntime = null;
            lightLease = null;
            lightMode = ClassicOriginalLightMode.DENY;
            lightSessionToken = 0L;
            lightConfirmed = false;
            lightConnectedCount = 0;
            lastStopReason = reason;
            lightStatus = ClassicOriginalLightReport.stopping(reason);
        }
        if (old != null) finishLightSession(old, token, stoppedEpoch);
        if (attached) redraw();
    }

    private void redraw() {
        // An already-owned reverse Music socket may outlive the one-time
        // 90-second UDP discovery proof, but never a changed original cohort,
        // vanished original audio source or paused original Music host.
        final ClassicOriginalMusicSelection.OutputLease live = lightLease;
        if (lightRuntime != null && (live == null || !attached ||
            !ClassicOriginalMusicSelection.isOutputLeaseCurrent(live) ||
            !modeStillAllowed(lightMode))) {
            stopLightOutput(reasonForCurrentOutput());
            return;
        }
        if (effectOrder.refresh()) renderEffectChips();
        for (int slot = 0; slot < favoriteChips.length; slot++) {
            ClassicSceneUiState.Selection saved = effectSettings.readFavorite(slot);
            String title = saved == null ? "Leer" :
                ClassicSceneUiState.LABELS[saved.scene];
            TextView chip = favoriteChips[slot];
            chip.setText("★ " + (slot + 1) + " " + title);
            chip.setEnabled(saved != null);
            chip.setContentDescription("DJ-Favorit " + (slot + 1) +
                (saved == null ? ", nicht belegt" : ", " + title + " laden"));
        }
        ClassicSceneUiState.Selection selected = ClassicSceneUiState.snapshot();
        boolean changed = shownRevision != ClassicSceneUiState.revision() ||
                          selected.scene != activeScene;
        if (changed) {
            activeScene = selected.scene;
            shownRevision = ClassicSceneUiState.revision();
            for (int i = 0; i < choices.length; i++) {
                boolean active = i == activeScene;
                TextView v = choices[i];
                v.setBackground(rounded(active ? PINK : TINT, dp(18),
                    active ? 0 : Color.rgb(255, 214, 233)));
                v.setTextColor(active ? Color.WHITE : INK);
                v.setText((active ? "✓ " : "") + NAMES[i]);
                v.setSelected(active);
            }
            preview.setMode(selected.scene, selected.beatPulse,
                new int[]{60, 90, 120, 150, 180}[selected.pulseTempoIndex],
                ClassicSceneUiState.COLORS[selected.colorIndex],
                selected.paletteIndex == 1,
                ClassicSceneUiState.SPEEDS[selected.speedIndex], selected.reverse,
                ClassicSceneUiState.TRAILS[selected.trailIndex]);
            String explanation = ClassicDjEffectPreview.explanation(
                ClassicSceneUiState.SCENES[selected.scene], selected.beatPulse);
            description.setText(explanation +
                (selected.scene >= 3 ? " · Farbe " +
                    (selected.scene >= 4 && selected.paletteIndex == 1 ?
                        "Regenbogen" : ClassicSceneUiState.COLOR_NAMES[selected.colorIndex]) : "") +
                " · nur Effektvorschau, keine Rückmeldung der Lampen");
            header.setText("Licht-Effekte  ·  " + NAMES[selected.scene] + "    ﹀");
            // A SceneFragment edit updates the currently OWNED real effect too.
            // Neither changing scene nor reading state starts a new capture.
            applyLightSettings();
            // Audio provenance is rendered from an actual callback below;
            // scene changes cannot manufacture amplitude or beat events.
        }

        final boolean runningOutput = lightRuntime != null;
        boolean witnessed = runningOutput || ClassicOriginalMusicSelection.fresh();
        int members = runningOutput ? live.size() :
            ClassicOriginalMusicSelection.originalGroupMemberCount();
        int verifiedIds = runningOutput ? live.size() :
            ClassicOriginalMusicSelection.originalGroupVerifiedIdentityCount();
        int marked = runningOutput ? live.size() :
            ClassicOriginalMusicSelection.djIntentCount();
        int verifiedLan = runningOutput ? live.size() :
            ClassicOriginalMusicSelection.resolvedOriginalLanCount();
        // Before OWNED acknowledgement, only explicit DJ intent is displayed;
        // after exact ACK the count represents Music connections, never an
        // optical verification. No OEM IDs, addresses or coordinates are read.
        ClassicDjPreviewCohort.Snapshot previewCohort =
            ClassicDjPreviewCohort.from(marked,
                runningOutput && live != null ? live.size() : 0,
                runningOutput && lightConfirmed);
        preview.setCohort(previewCohort);
        previewCohortStatus.setText(previewCohort.label());
        preview.setContentDescription(previewCohort.label() +
            " · schematische Reihenfolge, kein Grundriss oder Lampenfeedback");
        if (shownDeviceWitness != witnessed || shownGroupCount != members ||
            shownVerifiedIdentityCount != verifiedIds || shownDjIntentCount != marked ||
            shownLanVerifiedCount != verifiedLan || shownLightRunning != runningOutput ||
            shownLightConfirmed != lightConfirmed) {
            shownDeviceWitness = witnessed;
            shownGroupCount = members;
            shownVerifiedIdentityCount = verifiedIds;
            shownDjIntentCount = marked;
            shownLanVerifiedCount = verifiedLan;
            shownLightRunning = runningOutput;
            shownLightConfirmed = lightConfirmed;
            originalMusicSelectionStatus.setText(
                runningOutput ? (lightConfirmed ?
                    "Original-Classic Live-DJ: " + live.size() +
                        " bestätigte, ausdrücklich ausgewählte Music-Teilnehmer." :
                    "Original-Classic DJ-Start: " + live.size() +
                        " ausgewählte Lampen werden erst geprüft.") :
                !witnessed ? "Original-Classic-Gruppenwahl noch nicht bestätigt." :
                members < 0 ? "Original-Gerätekontext gelesen · Gruppenzuordnung offen." :
                "Original-Classic-Gruppenliste: " + members + " Mitglieder · " +
                    (verifiedIds < 0 ? "Geräteidentitäten ungeprüft" :
                     verifiedIds + " Geräteidentitäten gelesen") +
                    " · " + marked + " für DJ vorgemerkt" +
                    (verifiedLan > 0 ? " · " + verifiedLan +
                      " Color4-LAN-Ziele geprüft, Lichtausgabe braucht Bestätigung." :
                      " · keine bestätigte Auswahl für Lampensteuerung; " +
                      "LAN-/Lampenfreigabe weiterhin gesperrt."));
        }
        ClassicOriginalMusicAudioSignal.Snapshot audio =
            ClassicOriginalMusicAudioSignal.snapshot();
        preview.setOriginalFrame(audio);
        if (audio.fresh) {
            stateNotice.setText("Echtes Original-Classic-" +
                ("MIC".equals(audio.source) ? "Mikrofonsignal" : "Visualizer-FFT") +
                " erkannt · Pegel " + Math.round(audio.level * 100f) +
                "% · " + (audio.bpm > 0f ? Math.round(audio.bpm) + " BPM · Konfidenz " +
                    Math.round(audio.confidence * 100f) + "%" : "BPM noch nicht stabil") +
                " · noch keine bestätigten DJ-Lampenziele.");
        } else {
            stateNotice.setText("Nur Simulation ohne Original-Audio; " +
                "keine Lampe wird angesteuert.");
        }
        final int endpoints=verifiedLan;
        final boolean running=runningOutput;
        final boolean outputBusy = ClassicOriginalLightOutputGate.isBusy();
        final boolean outputUncertain = ClassicOriginalLightOutputGate.isQuarantined();
        final int availableMode = ClassicOriginalLightMode.choose(
            ClassicSceneUiState.SCENES[selected.scene], selected.beatPulse, audio.fresh);
        lightControl.setText(running ? (lightConfirmed ?
            "DJ-Lichtausgabe beenden" : "DJ-Start abbrechen") :
            outputUncertain ? "DJ-Lichtausgabe gesperrt · Sitzungszustand unklar" :
            outputBusy ? "Vorherige DJ-Lichtsession wird beendet" :
            (endpoints>0 && availableMode == ClassicOriginalLightMode.TIMED
                ? "Zeitgesteuerte Lichtszene für geprüfte Lampen starten" :
             endpoints>0 && availableMode == ClassicOriginalLightMode.ORIGINAL_AUDIO
                ? "Musiklicht für geprüfte Lampen starten" :
             endpoints>0 ? "Original-Audio starten oder Zeit-Effekt wählen" :
             "DJ-Lichtausgabe · zuerst Gruppenmitglieder im LAN prüfen"));
        lightControl.setEnabled(running || (!outputBusy && endpoints>0 &&
            availableMode != ClassicOriginalLightMode.DENY));
        if (outputUncertain && !running)
            lightStatus = ClassicOriginalLightReport.uncertain();
        if (running || !lightStatus.isEmpty()) {
            stateNotice.setText(lightStatus);
            stateNotice.setContentDescription(lightStatus);
        }
        // Visible with collapsed effects sheet, without covering original OEM player.
        header.setText(ClassicOriginalLightReport.header(lightStatus,
            running && live != null ? live.size() : 0,
            running && lightConfirmed, running ? lightConnectedCount : 0));
        header.setContentDescription((running || !lightStatus.isEmpty() ?
            lightStatus : "Licht-Effekte") + " · Details öffnen");
        if (inlineSheet.getVisibility() == View.VISIBLE) preview.invalidate();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            redraw();
            handler.postDelayed(this,
                inlineSheet.getVisibility() == View.VISIBLE ? 120L : 650L);
        }
    };

    public void onHostResumed() {
        // The genuine MusicActivity's view is checked repeatedly after resume.
        // Only a real foreground transition may replace its FFT/PCM observer.
        if (!hostLifecycle.enterResumed()) return;
        // A callback from the previous OEM capture must not revoke a newly
        // resumed Activity or feed its new light session.
        final long observerEpoch = ++originalObserverEpoch;
        attached = true;
        try {
        originalDsp.setFrameObserver(new ClassicOriginalMusicAudioSignal.FrameObserver() {
            @Override public void onFrame(ClassicOriginalMusicAudioSignal.Frame frame,long atMs) {
                if (!attached || observerEpoch != originalObserverEpoch) return;
                CheckpointRuntime runtime = lightRuntime;
                if (runtime != null && lightMode == ClassicOriginalLightMode.ORIGINAL_AUDIO)
                    runtime.submitOriginalFrame(frame);
            }
            @Override public void onCaptureClosed() {
                handler.post(() -> {
                    if (attached && observerEpoch == originalObserverEpoch &&
                        lightMode == ClassicOriginalLightMode.ORIGINAL_AUDIO)
                        stopLightOutput(ClassicOriginalLightReport.AUDIO_LOST);
                });
            }
        });
        ClassicOriginalMusicAudioSignal.installAnalyzer(originalDsp);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        } catch (RuntimeException | LinkageError unavailable) {
            // A bridge/DSP linkage failure must never crash the OEM MusicActivity
            // or leave a failed recheck holding the only capture observer lease.
            ++originalObserverEpoch;
            attached = false;
            try { originalDsp.setFrameObserver(null); }
            catch (RuntimeException | LinkageError ignored) { }
            try { ClassicOriginalMusicAudioSignal.clearAnalyzer(originalDsp); }
            catch (RuntimeException | LinkageError ignored) { }
            handler.removeCallbacks(refresh);
            hostLifecycle.leaveResumed();
            if (lightRuntime != null)
                stopLightOutput(ClassicOriginalLightReport.HOST_PAUSED);
            stateNotice.setText(
                "Original-Audioanalyse nicht verfügbar · Classic-Musik bleibt bedienbar");
        }
    }

    public void onHostPaused() {
        // A subsequent onDestroy must not stop/detach this already-paused host.
        if (!hostLifecycle.leaveResumed()) return;
        ++originalObserverEpoch;
        attached = false;
        // Independent best-effort cleanups: a broken optional DSP bridge
        // must not prevent revocation of its registered global analyzer.
        try { originalDsp.setFrameObserver(null); }
        catch (RuntimeException | LinkageError ignored) { }
        try { ClassicOriginalMusicAudioSignal.clearAnalyzer(originalDsp); }
        catch (RuntimeException | LinkageError ignored) { }
        // A host that never started DJ lights must not display a fake
        // completed/stopped light session when it loses focus.
        if (lightRuntime != null)
            stopLightOutput(ClassicOriginalLightReport.HOST_PAUSED);
        handler.removeCallbacks(refresh);
        closeSheet(); // original player controls remain visible on return
    }
}
