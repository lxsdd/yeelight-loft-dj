package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Pure deterministic source-only preview geometry. These eight abstract cells
 * are NOT the number, position or state of actual Yeelight lamps. No microphone,
 * original OEM audio-session data, permissions, networking or device writes.
 */
final class ClassicDjPatternMath {
    static final int CELLS = 8;
    private ClassicDjPatternMath() {}

    /** Real source-derived light intensity for the existing original OEM audio.
     * Never fabricates a beat when no actual original PCM/FFT event arrived.
     * This is the mathematically testable effect signal, NOT a device transport.
     */
    static float reactiveLevel(int scene, int index, float timedLevel,
                               boolean beatPulse, boolean fresh,
                               float measuredLevel, float[] measuredBands,
                               boolean measuredOnset) {
        if (index < 0 || index >= CELLS ||
            !Float.isFinite(timedLevel) || !Float.isFinite(measuredLevel) ||
            timedLevel < 0f || timedLevel > 1f ||
            measuredLevel < 0f || measuredLevel > 1f ||
            measuredBands == null || measuredBands.length != 4) return 0f;
        if (!fresh) return scene == 2 || (scene == 3 && beatPulse) ?
            0f : timedLevel;
        switch (scene) {
            case 0: return clamp(measuredBands[index % 4]);
            case 1: return clamp(.10f + .65f * measuredLevel);
            case 2: return measuredOnset ? 1f : .05f;
            case 3:
                return beatPulse ? (measuredOnset ? 1f : .06f) : timedLevel;
            case 4: case 5: case 6:
                return clamp(timedLevel * (.18f + .82f * measuredLevel));
            default: return 0f;
        }
    }

    private static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f;
    }

    static float level(int scene, int position, long time, boolean beat, int tempo) {
        if (position < 0 || position >= CELLS || time < 0) return 0f;
        switch (scene) {
            case 0: return (position % 4 == 0 ? .42f :
                           position % 4 == 1 ? .22f :
                           position % 4 == 2 ? .34f : .13f);
            case 1: return .12f + .04f * position;
            case 2: return position % 2 == 0 ? .46f : .08f;
            case 3:
                if (beat) return .18f; // Never fabricate a detected beat.
                if (tempo < 40 || tempo > 240) return 0f;
                double tick = (time % (60000L / tempo)) / (double)(60000L / tempo);
                return (float)(.1 + .9 * Math.pow(1.0 - tick, 2));
            case 4: {
                int point = (int)((time / 170L) % (2 * CELLS - 2));
                int head = point < CELLS ? point : (2 * CELLS - 2 - point);
                return position == head ? 1f : .08f;
            }
            case 5: {
                int head = (int)((time / 175L) % (CELLS + 5));
                int behind = head - position;
                return behind >= 0 && behind < 5 ?
                    (float)Math.exp(-behind * .72) : .04f;
            }
            case 6:
                return (float)(.10 + .90 * (1.0 + Math.sin(time / 360.0 -
                                                  position * .85)) / 2.0);
            default: return 0f;
        }
    }
}
