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
 * SPECTRUM / AMBIENT / BEAT / beat-PULSE are intentionally static without a
 * verified OEM PCM session. Spatial and timed modes render distinct geometry.
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

    public ClassicDjPatternPreviewView(Context context) { super(context); }

    public void setMode(int selected, boolean beat, int bpm) {
        if (selected < 0 || selected > 6 || bpm < 40 || bpm > 240) return;
        mode = selected;
        beatPulse = beat;
        pulseTempo = bpm;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w * .018f;
        float cell = (w - gap * (CELLS - 1)) / CELLS;
        float radius = Math.min(h / 2f, cell / 2f);
        long clock = SystemClock.elapsedRealtime();
        for (int i = 0; i < CELLS; i++) {
            float left = i * (cell + gap);
            bounds.set(left, 2f, left + cell, h - 2f);
            paint.setColor(EMPTY);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            float intensity = Math.max(0f, Math.min(1f,
                ClassicDjPatternMath.level(mode, i, clock, beatPulse, pulseTempo)));
            paint.setColor(Color.argb(40 + Math.round(215f * intensity),
                Color.red(PINK), Color.green(PINK), Color.blue(PINK)));
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }
}
