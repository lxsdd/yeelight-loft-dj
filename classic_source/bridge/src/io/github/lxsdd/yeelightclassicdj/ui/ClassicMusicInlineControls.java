package io.github.lxsdd.yeelightclassicdj.ui;

import android.app.Activity;
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
    private final TextView stateNotice;
    private final ClassicDjPatternPreviewView preview;
    private final TextView[] choices = new TextView[NAMES.length];
    private final int compactHeightPx;
    private final int detailsHeightPx;
    private boolean attached;
    private long shownRevision = -1L;
    private int shownGroupCount = Integer.MIN_VALUE;
    private boolean shownDeviceWitness;
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
        LinearLayout chips = new LinearLayout(activity);
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

        preview = new ClassicDjPatternPreviewView(activity);
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(-1, dp(48));
        pv.setMargins(0, dp(9), 0, dp(5));
        content.addView(preview, pv);
        preview.setContentDescription(
            "Animierte theoretische Lichtfolge; keine echten Lampen, Audiodaten oder Gerätepositionen");

        description = caption("", 13, INK);
        content.addView(description);

        originalMusicSelectionStatus = caption("", 12, MUTED);
        originalMusicSelectionStatus.setPadding(0, dp(9), 0, 0);
        originalMusicSelectionStatus.setContentDescription(
            "Lesende Original-Classic-Gruppenliste; keine autorisierte DJ-Auswahl");
        content.addView(originalMusicSelectionStatus);

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

    private void selectScene(int index) {
        if (index < 0 || index >= NAMES.length) return;
        // Atomic revisioned shared scene UI (same original SceneFragment); no
        // fabricated OEM scene record, remote effect or secondary database.
        ClassicSceneUiState.Selection current = ClassicSceneUiState.snapshot();
        if (!ClassicSceneUiState.update(index, current.speedIndex, current.reverse,
                                        current.trailIndex, current.rangeIndex))
            return;
        redraw();
    }

    private void redraw() {
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
                new int[]{60, 90, 120, 150, 180}[selected.pulseTempoIndex]);
            String explanation = ClassicDjEffectPreview.explanation(
                ClassicSceneUiState.SCENES[selected.scene], selected.beatPulse);
            description.setText(explanation + " · Vorschau ohne Lampen");
            header.setText("Licht-Effekte  ·  " + NAMES[selected.scene] + "    ﹀");
            // Audio provenance is rendered from an actual callback below;
            // scene changes cannot manufacture amplitude or beat events.
        }

        boolean witnessed = ClassicOriginalMusicSelection.fresh();
        int members = ClassicOriginalMusicSelection.originalGroupMemberCount();
        if (shownDeviceWitness != witnessed || shownGroupCount != members) {
            shownDeviceWitness = witnessed;
            shownGroupCount = members;
            originalMusicSelectionStatus.setText(
                !witnessed ? "Original-Classic-Gruppenwahl noch nicht bestätigt." :
                members < 0 ? "Original-Gerätekontext gelesen · Gruppenzuordnung offen." :
                "Zuletzt gelesene Original-Classic-Gruppenliste: " + members +
                  " Mitglieder (keine bestätigte Auswahl).");
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
        // Never start a second audio source; install only the read-only original callback DSP.
        ClassicOriginalMusicAudioSignal.installAnalyzer(originalDsp);
        attached = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    public void onHostPaused() {
        ClassicOriginalMusicAudioSignal.clearAnalyzer(originalDsp);
        attached = false;
        handler.removeCallbacks(refresh);
        closeSheet(); // original player controls remain visible on return
    }
}
