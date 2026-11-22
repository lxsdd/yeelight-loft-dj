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

    /** Mathematical intensity for each abstract position, never an actual bulb. */
    static float level(int scene, int position, long time, boolean beat, int tempo) {
        if (position < 0 || position >= CELLS || time < 0) return 0f;
        switch (scene) {
            case 0: return (position % 4 == 0 ? .42f :
                           position % 4 == 1 ? .22f :
                           position % 4 == 2 ? .34f : .13f); // static example
            case 1: return .12f + .04f * position; // ambient shape only
            case 2: return position % 2 == 0 ? .46f : .08f; // not a detected beat
            case 3:
                if (beat) return .18f; // do NOT fake a detected beat
                if (tempo < 40 || tempo > 240) return 0f;
                double tick = (time % (60000L / tempo)) / (double)(60000L / tempo);
                return (float)(.1 + .9 * Math.pow(1.0 - tick, 2));
            case 4: { // classic scanner reverses direction at both ends
                int positionOnLoop = (int)((time / 170L) % (2 * CELLS - 2));
                int head = positionOnLoop < CELLS ? positionOnLoop :
                           (2 * CELLS - 2 - positionOnLoop);
                return position == head ? 1.0f : .08f;
            }
            case 5: { // moving meteor with a decaying directional tail
                int head = (int)((time / 175L) % (CELLS + 5));
                int behind = head - position;
                return behind >= 0 && behind < 5 ?
                    (float)Math.exp(-behind * .72) : .04f;
            }
            case 6: // phase-shifted sinusoidal wave
                return (float)(.10 + .90 * (1.0 + Math.sin(time / 360.0 -
                                                  position * .85)) / 2.0);
            default: return 0f;
        }
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
                level(mode, i, clock, beatPulse, pulseTempo)));
            paint.setColor(Color.argb(40 + Math.round(215f * intensity),
                Color.red(PINK), Color.green(PINK), Color.blue(PINK)));
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }
}
