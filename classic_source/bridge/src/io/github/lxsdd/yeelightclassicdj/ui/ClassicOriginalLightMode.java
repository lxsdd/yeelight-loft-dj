package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Original-Classic light start policy. Pure decision, NO AudioRecord, LAN or
 * OEM writes: only the original MusicActivity and its explicit output dialog
 * may use a non-DENY decision to acquire the global Music output lease.
 */
public final class ClassicOriginalLightMode {
    public static final int DENY = 0;
    public static final int ORIGINAL_AUDIO = 1;
    public static final int TIMED = 2;

    private ClassicOriginalLightMode() {}

    static boolean timedCapable(String scene, boolean beatPulse) {
        return "SCANNER".equals(scene) || "METEOR".equals(scene) ||
            "WAVE".equals(scene) || ("PULSE".equals(scene) && !beatPulse);
    }

    private static boolean known(String scene) {
        return "SPECTRUM".equals(scene) || "AMBIENT".equals(scene) ||
            "BEAT".equals(scene) || "PULSE".equals(scene) ||
            "SCANNER".equals(scene) || "METEOR".equals(scene) ||
            "WAVE".equals(scene);
    }

    /** A timer-pulse is *always* clock-owned; spatial scenes use measured
     * original music when available and become clock-only when it is absent.
     * Spectrum/ambient/beat and beat-pulse can NEVER invent a source.
     */
    public static int choose(String scene, boolean beatPulse, boolean audioFresh) {
        if (!known(scene)) return DENY;
        if ("PULSE".equals(scene) && !beatPulse) return TIMED;
        if (audioFresh) return ORIGINAL_AUDIO;
        return timedCapable(scene, beatPulse) ? TIMED : DENY;
    }

    /** Immutable start-mode ownership: no silent timer<->audio switch after
     * confirmation. A timer cannot transform into a beat-only effect.
     */
    public static boolean stillPermitted(int mode, String scene,
                                         boolean beatPulse, boolean audioFresh) {
        if (!known(scene)) return false;
        if (mode == TIMED) return timedCapable(scene, beatPulse);
        return mode == ORIGINAL_AUDIO && audioFresh;
    }
}
