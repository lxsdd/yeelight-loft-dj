package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Dynamic, bounded drawing of the count explicitly selected in original
 * Classic MusicActivity. A missing selection uses eight labeled placeholders.
 * Never enumerate or address real bulbs, and never infer physical placement.
 * SPECTRUM / AMBIENT / BEAT / beat-PULSE animate ONLY when a real
 * original OEM microphone/Visualizer frame has been observed; otherwise
 * frequency/beat strength remains zero, never invented.
 * This is animation only: no OEM, microphone, LAN, scene or device side effects.
 */
public final class ClassicDjPatternPreviewView extends View {
    private static final int PLACEHOLDER_CELLS =
        ClassicDjPreviewCohort.PLACEHOLDER_CELLS;
    private static final int PINK = Color.rgb(255, 46, 121);
    private static final int EMPTY = Color.rgb(255, 229, 240);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private int mode;
    private boolean beatPulse = true;
    private int pulseTempo = 120;
    private int previewRgb = PINK;
    private boolean rainbow;
    private int spatialSpeed = 100;
    private int spatialTrail = 4;
    private boolean reverse;
    private ClassicDjPreviewCohort.Snapshot cohort =
        ClassicDjPreviewCohort.from(0, 0, false);
    private ClassicOriginalMusicAudioSignal.Snapshot originalFrame;

    public ClassicDjPatternPreviewView(Context context) { super(context); }

    public void setMode(int selected, boolean beat, int bpm) {
        setMode(selected, beat, bpm, PINK);
    }
    public void setMode(int selected, boolean beat, int bpm, int rgb) {
        setMode(selected, beat, bpm, rgb, false, 100, false, 4);
    }
    public void setMode(int selected, boolean beat, int bpm, int rgb,
                        boolean rainbow, int speed, boolean reverse) {
        setMode(selected, beat, bpm, rgb, rainbow, speed, reverse, 4);
    }

    /** Preview consumes the SAME original-scene spatial options as OWNED output. */
    public void setMode(int selected, boolean beat, int bpm, int rgb,
                        boolean rainbow, int speed, boolean reverse, int trail) {
        if (selected < 0 || selected > 6 || bpm < 40 || bpm > 240 ||
            rgb < 0 || rgb > 0xFFFFFF || speed < 25 || speed > 400 ||
            trail < 1 || trail > 8) return;
        mode = selected;
        beatPulse = beat;
        pulseTempo = bpm;
        previewRgb = rgb;
        this.rainbow = rainbow;
        spatialSpeed = speed;
        this.reverse = reverse;
        spatialTrail = trail;
        invalidate();
    }

    /** The original UI supplies only a count/provenance, NEVER model IDs or IPs. */
    public void setCohort(ClassicDjPreviewCohort.Snapshot current) {
        if (current == null || current.cells < 1) return;
        if (cohort.cells != current.cells || cohort.proof != current.proof) {
            cohort = current;
            invalidate();
        }
    }

    /** True source provenance only: data already captured by original Classic. */
    public void setOriginalFrame(ClassicOriginalMusicAudioSignal.Snapshot frame) {
        originalFrame = frame;
        invalidate();
    }

    /** Same modular rainbow geometry as the owned, dynamically sized output. */
    private int rainbowRgb(int index, int count, long timestampMs) {
        int position = reverse ? count - 1 - index : index;
        long phase = (timestampMs / 45L % 360L) * spatialSpeed / 100L;
        double hue = ((position * 360L / count + phase) % 360L) / 60.0;
        int x = (int) (255.0 * (1.0 - Math.abs(hue % 2.0 - 1.0)));
        int red, green, blue;
        switch ((int) hue) {
            case 0: red = 255; green = x; blue = 0; break;
            case 1: red = x; green = 255; blue = 0; break;
            case 2: red = 0; green = 255; blue = x; break;
            case 3: red = 0; green = x; blue = 255; break;
            case 4: red = x; green = 0; blue = 255; break;
            default: red = 255; green = 0; blue = x;
        }
        return (red << 16) | (green << 8) | blue;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int count = cohort.cells;
        // Responsive rows preserve EVERY selected position: 14 means 14,
        // 32 means 32. No fixed four/eight-lamp limit or fake device positions.
        int columns = Math.max(1, Math.min(count,
            (int) Math.ceil(Math.sqrt((double) count * w / h))));
        int rows = 1 + (count - 1) / columns;
        float gap = Math.min(2f, Math.min(w / (columns * 9f),
            h / (rows * 9f)));
        float cellWidth = (w - gap * (columns - 1)) / columns;
        float cellHeight = (h - gap * (rows - 1)) / rows;
        if (cellWidth <= 0f || cellHeight <= 0f) return;
        float radius = Math.min(cellHeight / 2f, cellWidth / 2f);
        long clock = SystemClock.elapsedRealtime();
        ClassicOriginalMusicAudioSignal.Snapshot source = originalFrame;
        for (int i = 0; i < count; i++) {
            int col = i % columns, row = i / columns;
            float left = col * (cellWidth + gap), top = row * (cellHeight + gap);
            bounds.set(left, top, left + cellWidth, top + cellHeight);
            paint.setColor(EMPTY);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            float timed = ClassicDjPatternMath.previewLevel(mode, i, count, clock,
                beatPulse, pulseTempo, spatialSpeed, reverse, spatialTrail);
            float intensity = ClassicDjPatternMath.reactiveLevel(mode, i, count,
                timed, beatPulse, source != null && source.fresh,
                source == null ? 0f : source.level,
                source == null ? new float[4] : source.bands,
                source != null && source.beat);
            int rgb = rainbow && mode >= 4 ? rainbowRgb(i, count, clock) : previewRgb;
            paint.setColor(Color.argb(40 + Math.round(215f * intensity),
                Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }
}
