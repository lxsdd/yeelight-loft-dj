package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Eight abstract preview cells. Never count, enumerate or address real bulbs.
 * SPECTRUM / AMBIENT / BEAT / beat-PULSE animate ONLY when a real
 * original OEM microphone/Visualizer frame has been observed; otherwise
 * frequency/beat strength remains zero, never invented.
 * This is animation only: no OEM, microphone, LAN, scene or device side effects.
 */
public final class ClassicDjPatternPreviewView extends View {
    private static final int CELLS = 8;
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
    private boolean reverse;
    private ClassicOriginalMusicAudioSignal.Snapshot originalFrame;

    public ClassicDjPatternPreviewView(Context context) { super(context); }

    public void setMode(int selected, boolean beat, int bpm) {
        setMode(selected, beat, bpm, PINK);
    }
    public void setMode(int selected, boolean beat, int bpm, int rgb) {
        setMode(selected, beat, bpm, rgb, false, 100, false);
    }
    public void setMode(int selected, boolean beat, int bpm, int rgb,
                        boolean rainbow, int speed, boolean reverse) {
        if (selected < 0 || selected > 6 || bpm < 40 || bpm > 240 ||
            rgb < 0 || rgb > 0xFFFFFF || speed < 25 || speed > 400) return;
        mode = selected;
        beatPulse = beat;
        pulseTempo = bpm;
        previewRgb = rgb;
        this.rainbow = rainbow;
        spatialSpeed = speed;
        this.reverse = reverse;
        invalidate();
    }

    /** True source provenance only: data already captured by original Classic. */
    public void setOriginalFrame(ClassicOriginalMusicAudioSignal.Snapshot frame) {
        originalFrame = frame;
        invalidate();
    }

    /** Same palette geometry as the owned output, on eight imaginary cells. */
    private int rainbowRgb(int index, long timestampMs) {
        int position = reverse ? CELLS - 1 - index : index;
        long phase = (timestampMs / 45L % 360L) * spatialSpeed / 100L;
        double hue = ((position * 360L / CELLS + phase) % 360L) / 60.0;
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
        float gap = w * .018f;
        float cell = (w - gap * (CELLS - 1)) / CELLS;
        float radius = Math.min(h / 2f, cell / 2f);
        long clock = SystemClock.elapsedRealtime();
        ClassicOriginalMusicAudioSignal.Snapshot source = originalFrame;
        for (int i = 0; i < CELLS; i++) {
            float left = i * (cell + gap);
            bounds.set(left, 2f, left + cell, h - 2f);
            paint.setColor(EMPTY);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            float timed = ClassicDjPatternMath.level(mode, i, clock,
                beatPulse, pulseTempo);
            float intensity = ClassicDjPatternMath.reactiveLevel(mode, i, timed,
                beatPulse, source != null && source.fresh,
                source == null ? 0f : source.level,
                source == null ? new float[4] : source.bands,
                source != null && source.beat);
            int rgb = rainbow && mode >= 4 ? rainbowRgb(i, clock) : previewRgb;
            paint.setColor(Color.argb(40 + Math.round(215f * intensity),
                Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }
}
