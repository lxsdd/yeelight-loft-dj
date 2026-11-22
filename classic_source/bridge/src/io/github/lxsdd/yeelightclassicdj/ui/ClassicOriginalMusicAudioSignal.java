package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Read-only tap on the EXISTING original Classic audio worker/callback.
 * Does not create AudioRecord, Visualizer, MediaProjection, a music session,
 * a second source selector, an OEM device identity or a lamp command.
 * No raw PCM/FFT buffers or OEM user/device data are retained or logged.
 */
public final class ClassicOriginalMusicAudioSignal {
    static final long FRESH_MS = 1250L;
    private static final long BEAT_COOLDOWN_MS = 195L;
    private static final ThreadLocal<int[]> MIC_VALID_COUNT =
        new ThreadLocal<int[]>() {
            @Override protected int[] initialValue() { return new int[1]; }
        };

    public static final class Snapshot {
        public final boolean fresh, beat;
        public final String source;
        public final float level;
        public final float[] bands;
        Snapshot(boolean fresh, boolean beat, String source, float level, float[] bands) {
            this.fresh = fresh;
            this.beat = beat;
            this.source = source;
            this.level = level;
            this.bands = bands.clone();
        }
    }

    private static String source = "";
    private static float energy, movingAverage = .07f;
    private static final float[] bands = new float[4];
    private static long observedAtMs = Long.MIN_VALUE;
    private static long lastBeatAtMs = Long.MIN_VALUE;

    private ClassicOriginalMusicAudioSignal() {}

    /** First half of a same-thread, original AudioRecord.read([SII) result. */
    public static void observedOriginalMicResult(int readCount) {
        MIC_VALID_COUNT.get()[0] = readCount > 0 && readCount <= 131072 ? readCount : 0;
    }

    /** Second half: the original short[] is processed synchronously, not stored. */
    public static void observedOriginalMicSamples(short[] originalBuffer) {
        int[] count = MIC_VALID_COUNT.get();
        int valid = count[0];
        count[0] = 0;
        if (originalBuffer == null || valid < 1 || valid > originalBuffer.length)
            return;
        try {
            observedPcmAt(originalBuffer, valid, clockMs());
        } catch (RuntimeException ignored) {
            // A read-only observer must never interrupt the OEM audio worker.
        }
    }

    static synchronized void observedPcmAt(short[] pcm, int valid, long nowMs) {
        if (pcm == null || valid < 1 || valid > pcm.length || valid > 131072 || nowMs < 0)
            return;
        int stride = Math.max(1, valid / 2048);
        double squares = 0d;
        int used = 0;
        for (int i = 0; i < valid; i += stride) {
            double sample = pcm[i] / 32768d;
            squares += sample * sample;
            used++;
        }
        float level = clamp((float)Math.sqrt(Math.sqrt(squares / Math.max(used, 1))));
        // Mic AudioRecord supplies actual amplitude, NOT a verified FFT rate.
        accept("MIC", level, new float[]{level, 0f, 0f, 0f}, nowMs);
    }

    /** Actual original Visualizer callback; samplingRate is in millihertz. */
    public static void observedOriginalVisualizerFft(byte[] actualFft, int samplingMilliHz) {
        try {
            observedFftAt(actualFft, samplingMilliHz, clockMs());
        } catch (RuntimeException ignored) {
            // No original callbacks, controls or audio transport are replaced.
        }
    }

    static synchronized void observedFftAt(byte[] fft, int milliHz, long nowMs) {
        if (fft == null || fft.length < 16 || fft.length > 32768 ||
            (fft.length & 1) != 0 || milliHz < 8000000 || milliHz > 192000000 ||
            nowMs < 0) return;
        float[] measured = new float[4];
        int[] counts = new int[4];
        float sampleHz = milliHz / 1000f;
        for (int bin = 1; bin < fft.length / 2; bin++) {
            float freq = bin * sampleHz / fft.length;
            if (freq < 20f || freq > 20000f) continue;
            int band = freq < 180f ? 0 : freq < 1200f ? 1 :
                freq < 6000f ? 2 : 3;
            float real = fft[bin * 2], imaginary = fft[bin * 2 + 1];
            measured[band] += Math.min(1f, (float)Math.hypot(real, imaginary) / 181f);
            counts[band]++;
        }
        float strongest = 0f;
        for (int i = 0; i < 4; i++) {
            measured[i] = counts[i] == 0 ? 0f :
                clamp((float)Math.sqrt(measured[i] / counts[i]));
            strongest = Math.max(strongest, measured[i]);
        }
        accept("FFT", strongest, measured, nowMs);
    }

    private static synchronized void accept(String nextSource, float measured,
                                            float[] measuredBands, long nowMs) {
        if (!Float.isFinite(measured) || nowMs < 0) return;
        if (!source.equals(nextSource) || observedAtMs == Long.MIN_VALUE ||
            nowMs < observedAtMs || nowMs - observedAtMs > FRESH_MS) {
            movingAverage = .07f;
            lastBeatAtMs = Long.MIN_VALUE;
        }
        boolean onset = measured >= .22f && measured > movingAverage * 1.62f &&
            (lastBeatAtMs == Long.MIN_VALUE ||
             nowMs - lastBeatAtMs >= BEAT_COOLDOWN_MS);
        if (onset) lastBeatAtMs = nowMs;
        movingAverage = .88f * movingAverage + .12f * measured;
        source = nextSource;
        observedAtMs = nowMs;
        energy = clamp(measured);
        for (int i = 0; i < 4; i++) bands[i] = clamp(measuredBands[i]);
    }

    public static synchronized Snapshot snapshot() {
        return snapshotAt(clockMs());
    }

    static synchronized Snapshot snapshotAt(long nowMs) {
        if (observedAtMs == Long.MIN_VALUE || nowMs < observedAtMs ||
            nowMs - observedAtMs > FRESH_MS)
            return new Snapshot(false, false, "", 0f, new float[4]);
        boolean beat = lastBeatAtMs != Long.MIN_VALUE &&
            nowMs >= lastBeatAtMs && nowMs - lastBeatAtMs <= 150L;
        return new Snapshot(true, beat, source, energy, bands);
    }

    /** A stopped OLD source cannot clear a newer OEM audio source. */
    public static synchronized void observedOriginalMicStopped() {
        MIC_VALID_COUNT.remove();
        stopIfSource("MIC");
    }
    public static synchronized void observedOriginalVisualizerStopped() {
        stopIfSource("FFT");
    }
    private static void stopIfSource(String expected) {
        if (source.equals(expected)) {
            source = "";
            energy = 0f;
            observedAtMs = Long.MIN_VALUE;
            lastBeatAtMs = Long.MIN_VALUE;
            for (int i = 0; i < bands.length; i++) bands[i] = 0f;
        }
    }

    static synchronized void resetForTest() {
        MIC_VALID_COUNT.remove();
        source = "";
        movingAverage = .07f;
        stopIfSource("");
    }

    private static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f;
    }
    private static long clockMs() { return System.nanoTime() / 1_000_000L; }
}
