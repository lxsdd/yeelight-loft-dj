package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Exact-owner, read-only snapshot of genuine source-local PCM analysis. This
 * class has no Android, OEM, LAN or file dependencies and is Java-8 testable.
 */
public final class ClassicDjLiveFrame {
    public static final long STALE_AFTER_MS = 2000L;
    public final boolean owned;
    public final boolean internal;
    public final long frames;
    public final long lastFrameAgeMs;
    public final long lastBeatAgeMs;
    public final double bpm;
    public final float level;
    public final float bass;
    public final float mid;
    public final float presence;
    public final float treble;
    public final float confidence;

    public ClassicDjLiveFrame(boolean owned, boolean internal, long frames,
                              long lastFrameAgeMs, long lastBeatAgeMs,
                              double bpm, float level, float bass, float mid,
                              float presence, float treble, float confidence) {
        this.owned = owned;
        this.internal = internal;
        this.frames = Math.max(0L, frames);
        this.lastFrameAgeMs = Math.max(0L, lastFrameAgeMs);
        this.lastBeatAgeMs = Math.max(0L, lastBeatAgeMs);
        this.bpm = Double.isFinite(bpm) && bpm > 0 && bpm <= 300 ? bpm : 0.0;
        this.level = bound(level);
        this.bass = bound(bass);
        this.mid = bound(mid);
        this.presence = bound(presence);
        this.treble = bound(treble);
        this.confidence = bound(confidence);
    }

    public static ClassicDjLiveFrame none() {
        return new ClassicDjLiveFrame(false, false, 0, Long.MAX_VALUE,
                                      Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean fresh() {
        return owned && frames > 0 && lastFrameAgeMs <= STALE_AFTER_MS;
    }

    public boolean audible() {
        return fresh() && Math.max(Math.max(bass, mid),
                                  Math.max(presence, treble)) >= 0.01f;
    }

    public boolean recentBeat() {
        return fresh() && lastBeatAgeMs < 350L;
    }

    public float[] bands() {
        return new float[]{bass, mid, presence, treble};
    }

    public String summary() {
        if (!owned) return "Keine eigene Audioanalyse aktiv";
        if (frames == 0) return "PCM-Analyse wartet auf erste Audiodaten";
        if (!fresh()) return "Keine aktuellen Audiodaten · Quelle/Wiedergabe prüfen";
        if (!audible()) return "Audioframes empfangen · zurzeit Stille oder gesperrte Quelle";
        String tempo = bpm > 0 ? Math.round(bpm) + " BPM" : "BPM noch nicht stabil";
        return (internal ? "Internes Audio" : "Mikrofon") +
            " · PCM aktiv · " + tempo + " · Pegel " +
            Math.round(level * 100f) + "% · Konfidenz " +
            Math.round(confidence * 100f) + "%";
    }

    private static float bound(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
