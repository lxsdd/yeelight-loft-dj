package io.github.lxsdd.yeelightclassicdj.ui;

import android.media.AudioRecord;

/**
 * Read-only, generation-fenced observer of ORIGINAL Classic AudioRecord/Visualizer.
 * Does not create a source, modify the OEM player, retain PCM/FFT, or address lamps.
 * The resumed original MusicActivity installs the already-tested Kotlin DSP owner.
 */
public final class ClassicOriginalMusicAudioSignal {
    static final long FRESH_MS = 1250L;
    private static final int MAX_PCM = 131072;
    private static final ThreadLocal<int[]> MIC_COUNT =
        ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> MIC_RATE =
        ThreadLocal.withInitial(() -> new int[1]);

    public interface Analyzer {
        Frame onPcm(short[] pcm, int valid, int rateHz, long timestampMs);
        Frame onFft(byte[] fft, int rateMilliHz, long timestampMs);
        /** Pure DSP reset only. An OEM stop cannot close another capture owner. */
        default void onCaptureClosed() {}
    }

    /** A nonblocking observer only. Its callbacks MUST NOT do LAN I/O. */
    public interface FrameObserver {
        void onFrame(Frame frame, long timestampMs);
        void onCaptureClosed();
    }

    public static final class Frame {
        public final float level, bpm, confidence;
        public final boolean beat;
        public final float[] bands;
        public Frame(float level, float[] bands, boolean beat, float bpm, float confidence) {
            this.level = level;
            this.bands = bands == null ? null : bands.clone();
            this.beat = beat;
            this.bpm = bpm;
            this.confidence = confidence;
        }
    }

    public static final class Snapshot {
        public final boolean fresh, beat;
        public final String source;
        public final float level, bpm, confidence;
        public final float[] bands;
        Snapshot(boolean fresh, boolean beat, String source, float level,
                 float bpm, float confidence, float[] bands) {
            this.fresh = fresh;
            this.beat = beat;
            this.source = source;
            this.level = level;
            this.bpm = bpm;
            this.confidence = confidence;
            this.bands = bands.clone();
        }
    }

    private static Analyzer owner;
    private static String source = "";
    private static float energy, bpm, confidence;
    private static final float[] bands = new float[4];
    private static long observedAtMs = Long.MIN_VALUE;
    private static long lastBeatAtMs = Long.MIN_VALUE;

    private ClassicOriginalMusicAudioSignal() {}

    public static synchronized void installAnalyzer(Analyzer next) {
        if (next == null) throw new IllegalArgumentException("Missing original DSP owner");
        // A repeated layout attach to this SAME OEM Music host must not
        // erase still-fresh measured PCM, FFT bands, BPM or beat timing.
        if (owner == next) return;
        owner = next;
        clearSignal();
    }

    /** A stale Activity cannot detach the newer resumed Activity. */
    public static void clearAnalyzer(Analyzer expected) {
        boolean detached = false;
        synchronized (ClassicOriginalMusicAudioSignal.class) {
            if (expected != null && owner == expected) {
                owner = null;
                clearSignal();
                detached = true;
            }
        }
        if (detached) closeDspSafely(expected);
    }

    /** No exception from the optional read-only observer may escape into OEM. */
    private static void closeDspSafely(Analyzer expected) {
        if (expected == null) return;
        try {
            expected.onCaptureClosed();
        } catch (RuntimeException | LinkageError ignored) {
            // The original Classic capture and player lifecycle is authoritative.
        }
    }

    private static void clearSignal() {
        source = "";
        energy = bpm = confidence = 0f;
        observedAtMs = lastBeatAtMs = Long.MIN_VALUE;
        java.util.Arrays.fill(bands, 0f);
    }

    public static void observedOriginalMicResult(int readCount) {
        MIC_COUNT.get()[0] = readCount > 0 && readCount <= MAX_PCM ? readCount : 0;
        MIC_RATE.get()[0] = 0;
    }

    /** Read the SAME original AudioRecord's format. No new recorder. */
    public static void observedOriginalMicRate(AudioRecord original) {
        int rate = 0;
        try {
            if (original != null) rate = original.getSampleRate();
        } catch (RuntimeException | LinkageError ignored) { }
        MIC_RATE.get()[0] = rate >= 8000 && rate <= 192000 ? rate : 0;
    }

    public static void observedOriginalMicSamples(short[] originalBuffer) {
        int valid = MIC_COUNT.get()[0], rate = MIC_RATE.get()[0];
        MIC_COUNT.get()[0] = 0;
        MIC_RATE.get()[0] = 0;
        observedPcmAt(originalBuffer, valid, rate, clockMs());
    }

    static void observedPcmAt(short[] pcm, int valid, int rate, long nowMs) {
        if (pcm == null || valid < 1 || valid > MAX_PCM || valid > pcm.length ||
            rate < 8000 || rate > 192000 || nowMs < 0) return;
        Analyzer current;
        synchronized (ClassicOriginalMusicAudioSignal.class) { current = owner; }
        if (current == null) return;
        try {
            acceptFrame(current, "MIC", current.onPcm(pcm, valid, rate, nowMs), nowMs);
        } catch (RuntimeException | LinkageError ignored) {
            // Observer failures must not interrupt the original OEM recorder.
        }
    }

    public static void observedOriginalVisualizerFft(byte[] originalFft, int rateMilliHz) {
        observedFftAt(originalFft, rateMilliHz, clockMs());
    }

    static void observedFftAt(byte[] fft, int rateMilliHz, long nowMs) {
        if (fft == null || fft.length < 16 || fft.length > 32768 ||
            (fft.length & 1) != 0 || rateMilliHz < 8000000 ||
            rateMilliHz > 192000000 || nowMs < 0) return;
        Analyzer current;
        synchronized (ClassicOriginalMusicAudioSignal.class) { current = owner; }
        if (current == null) return;
        try {
            acceptFrame(current, "FFT", current.onFft(fft, rateMilliHz, nowMs), nowMs);
        } catch (RuntimeException | LinkageError ignored) {
            // Observer failures must not interrupt the original OEM callback.
        }
    }

    private static synchronized void acceptFrame(Analyzer expected, String kind,
                                                  Frame frame, long nowMs) {
        if (owner != expected || frame == null || !validFrame(frame) ||
            (observedAtMs != Long.MIN_VALUE && nowMs < observedAtMs &&
             source.equals(kind))) return;
        if (!source.equals(kind)) clearSignal();
        source = kind;
        observedAtMs = nowMs;
        energy = frame.level;
        bpm = frame.bpm;
        confidence = frame.confidence;
        System.arraycopy(frame.bands, 0, bands, 0, 4);
        if (frame.beat) lastBeatAtMs = nowMs;
    }

    private static boolean validFrame(Frame f) {
        if (!Float.isFinite(f.level) || f.level < 0f || f.level > 1f ||
            !Float.isFinite(f.bpm) || f.bpm < 0f || f.bpm > 240f ||
            !Float.isFinite(f.confidence) || f.confidence < 0f ||
            f.confidence > 1f || f.bands == null || f.bands.length != 4) return false;
        for (float b : f.bands)
            if (!Float.isFinite(b) || b < 0f || b > 1f) return false;
        return true;
    }

    public static synchronized Snapshot snapshot() { return snapshotAt(clockMs()); }

    static synchronized Snapshot snapshotAt(long nowMs) {
        if (owner == null || observedAtMs == Long.MIN_VALUE || nowMs < observedAtMs ||
            nowMs - observedAtMs > FRESH_MS)
            return new Snapshot(false, false, "", 0f, 0f, 0f, new float[4]);
        boolean beat = lastBeatAtMs != Long.MIN_VALUE && nowMs >= lastBeatAtMs &&
            nowMs - lastBeatAtMs <= 150L;
        return new Snapshot(true, beat, source, energy, bpm, confidence, bands);
    }

    public static void observedOriginalMicStopped() {
        MIC_COUNT.remove();
        MIC_RATE.remove();
        Analyzer closing = null;
        synchronized (ClassicOriginalMusicAudioSignal.class) {
            if ("MIC".equals(source)) {
                closing = owner;
                clearSignal();
            }
        }
        // Never take the DSP monitor while holding this class monitor:
        // an in-flight PCM worker acquires them in the opposite order.
        closeDspSafely(closing);
    }

    public static void observedOriginalVisualizerStopped() {
        Analyzer closing = null;
        synchronized (ClassicOriginalMusicAudioSignal.class) {
            if ("FFT".equals(source)) {
                closing = owner;
                clearSignal();
            }
        }
        closeDspSafely(closing);
    }

    static synchronized void resetForTest() {
        MIC_COUNT.remove();
        MIC_RATE.remove();
        owner = null;
        clearSignal();
    }

    private static long clockMs() { return System.nanoTime() / 1_000_000L; }
}
