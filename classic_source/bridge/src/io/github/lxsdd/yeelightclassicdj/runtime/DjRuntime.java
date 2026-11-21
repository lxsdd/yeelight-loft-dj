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
    /** Idempotent, per-bulb safe stop/close; must not send blind recovery retries. */
    void stop();
    /** Update DSP sensitivity and visual scene without reconnecting owned Music sockets. */
    boolean configure(double sensitivity, String scene);
    /** Beat or clock driven pulse, on the same generation-owned Music session. */
    boolean configurePulse(int rgb, boolean beatSync, int bpm, int minimum, int maximum,
                           int attackMs, int decayMs, double gamma);
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
