package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Pure deterministic preview geometry. The legacy eight placeholder cells
 * are NOT a real bulb count; dynamic versions use the actual user-selected
 * original Classic cohort size (never infer physical position or state).
 * No microphone,
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
        return reactiveLevel(scene, index, CELLS, timedLevel, beatPulse,
            fresh, measuredLevel, measuredBands, measuredOnset);
    }

    /** Preview follows the ACTUAL selected display cohort, not a fixed
     * 4/8-lamp group. A missing original source cannot invent measured
     * spectrum, ambient level or beat-based lighting.
     */
    static float reactiveLevel(int scene, int index, int count, float timedLevel,
                               boolean beatPulse, boolean fresh,
                               float measuredLevel, float[] measuredBands,
                               boolean measuredOnset) {
        if (count < 1 || index < 0 || index >= count ||
            !Float.isFinite(timedLevel) || !Float.isFinite(measuredLevel) ||
            timedLevel < 0f || timedLevel > 1f ||
            measuredLevel < 0f || measuredLevel > 1f ||
            measuredBands == null || measuredBands.length != 4) return 0f;
        if (!fresh) return scene == 3 && !beatPulse ||
                scene == 4 || scene == 5 || scene == 6 ? timedLevel : 0f;
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

    /**
     * Draw a spatial preview for every selected original Classic member.
     * The scanner/meteor/wave interpolation mirrors the production
     * ClassicSpatialChoreography geometry and stored speed, trail, reverse
     * choices. This method owns NO selected ID, Music socket or device state.
     */
    static float previewLevel(int scene, int index, int count, long time,
                              boolean beatPulse, int tempo, int speedPercent,
                              boolean reverse, int trailLength) {
        if (count < 1 || index < 0 || index >= count || time < 0L ||
            speedPercent < 25 || speedPercent > 400 ||
            trailLength < 1 || trailLength > 8) return 0f;
        if (scene < 4) return level(scene, index % CELLS, time, beatPulse, tempo);
        int position = reverse ? count - 1 - index : index;
        switch (scene) {
            case 4: {
                long span = Math.max(1L, (long) count - 1L);
                long cycle = span * 2L;
                long step = Math.max(1L, 21000L / speedPercent);
                double phase = (time % (cycle * step)) / (double) step;
                double head = phase <= span ? phase : cycle - phase;
                double distance = Math.abs(position - head);
                return distance <= 1.0 ?
                    clamp((float) (1.0 - .78 * distance)) :
                    distance <= trailLength ?
                        clamp((float) (.22 * Math.exp(-(distance - 1.0) * 1.5))) :
                        0f;
            }
            case 5: {
                long cycle = (long) count + trailLength;
                long step = Math.max(1L, 16500L / speedPercent);
                double head = (time % (cycle * step)) / (double) step;
                double age = head - position;
                if (age < -1.0 || age > trailLength) return 0f;
                return clamp((float) (age < 0.0 ?
                    1.0 + age : Math.exp(-age * (3.0 / trailLength))));
            }
            case 6: {
                double phase = time * speedPercent / 50000.0 -
                    position * (2.8 / trailLength);
                return clamp((float) (.5 + .5 * Math.sin(phase)));
            }
            default: return 0f;
        }
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
