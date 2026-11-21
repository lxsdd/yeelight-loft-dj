package io.github.lxsdd.yeelightclassicdj.runtime;

import android.content.Context;
import android.media.projection.MediaProjection;
import java.util.List;

/** Implemented by the previously tested private Kotlin Classic DJ checkpoint, NOT by Classic internals. */
public interface DjRuntime {
    /** Must fail closed if no participants/mic permission or any session remains ambiguous. */
    void start(Context context, String source, List<String> selectedLampIps, Listener listener) throws Exception;
    /** Explicitly consented Android playback capture, using the same PCM/DSP pipeline. */
    void startInternal(Context context, MediaProjection projection,
                       List<String> selectedLampIps, Listener listener) throws Exception;
    /** A verified original Classic audio-session Visualizer, not MediaProjection or a raw OEM source dump.
     * The call site must supply the actual, independently qualified session ID;
     * no implicit output-mix fallback is permitted.
     */
    void startVisualizer(Context context, int audioSessionId,
                         List<String> selectedLampIps, Listener listener) throws Exception;
    /** A time-driven scene has no microphone, Visualizer or projection.
     * Only a verified scene owner may supply explicit owned participants.
     */
    void startTimedScene(Context context, List<String> selectedLampIps,
                         Listener listener) throws Exception;
    /** Idempotent, per-bulb safe stop/close; must not send blind recovery retries. */
    void stop();
    /** Update DSP sensitivity and visual scene without reconnecting owned Music sockets. */
    boolean configure(double sensitivity, String scene);
    /** Beat or clock driven pulse, on the same generation-owned Music session. */
    boolean configurePulse(int rgb, boolean beatSync, int bpm, int minimum, int maximum,
                           int attackMs, int decayMs, double gamma);
    /** Change current spatial scene colour, speed, direction, tail and bounded
     * brightness on the EXISTING owned output. Does not start or select lamps.
     * RGB -1 means keep the original effect colour.
     */
    boolean configureSpatial(int rgb, int speedPercent, boolean reverse,
                             int trailLength, int minBrightness, int maxBrightness);
    /**
     * Immutable scene-editor snapshot shared by the original Classic Music and
     * future verified SceneFragment routes. No device identifiers or OEM data.
     * Call applySceneSettings ONCE; never partially apply its 3 sub-settings.
     */
    final class SceneSettings {
        public final double sensitivity;
        public final String scene;
        public final int pulseRgb, pulseBpm, pulseMin, pulseMax, attackMs, decayMs;
        public final boolean beatSync;
        public final double gamma;
        public final int spatialRgb, spatialSpeed, trailLength, spatialMin, spatialMax;
        public final boolean reverse;
        public SceneSettings(double sensitivity, String scene,
                int pulseRgb, boolean beatSync, int pulseBpm, int pulseMin,
                int pulseMax, int attackMs, int decayMs, double gamma,
                int spatialRgb, int spatialSpeed, boolean reverse,
                int trailLength, int spatialMin, int spatialMax) {
            this.sensitivity = sensitivity;
            this.scene = scene;
            this.pulseRgb = pulseRgb;
            this.beatSync = beatSync;
            this.pulseBpm = pulseBpm;
            this.pulseMin = pulseMin;
            this.pulseMax = pulseMax;
            this.attackMs = attackMs;
            this.decayMs = decayMs;
            this.gamma = gamma;
            this.spatialRgb = spatialRgb;
            this.spatialSpeed = spatialSpeed;
            this.reverse = reverse;
            this.trailLength = trailLength;
            this.spatialMin = spatialMin;
            this.spatialMax = spatialMax;
        }
    }
    /** Validate scene+gain+pulse+geometry together, then commit one owned-session edit. */
    boolean applySceneSettings(SceneSettings settings);
    /** Stable allowlisted capture-open result. Never expose raw OEM/Android exceptions in UI. */
    enum CaptureFailureKind { PERMISSION, FORMAT, STATE, ABI, UNKNOWN }
    final class CaptureStartFailure extends Exception {
        public final CaptureFailureKind kind;
        public CaptureStartFailure(CaptureFailureKind kind, Throwable cause) {
            super(kind == null ? "UNKNOWN" : kind.name(), cause);
            this.kind = kind == null ? CaptureFailureKind.UNKNOWN : kind;
        }
    }
    interface Listener {
        void onState(String state);
        void onBeat(double bpm, float level);
        /** Source-local FFT telemetry; optional for older listeners. Zero device I/O.
         * Only the exact owner of the capture session may display it.
         */
        default void onSpectrum(float bass, float mid, float presence,
                                float treble, boolean beat, float confidence) {}
        void onError(String error);
    }
}
