package io.github.lxsdd.yeelightclassicdj.runtime;

import android.content.Context;
import java.util.List;

/** Implemented by the previously tested private Kotlin Classic DJ checkpoint, NOT by Classic internals. */
public interface DjRuntime {
    /** Must fail closed if no participants/mic permission or any session remains ambiguous. */
    void start(Context context, String source, List<String> selectedLampIps, Listener listener) throws Exception;
    /** Idempotent, per-bulb safe stop/close; must not send blind recovery retries. */
    void stop();
    /** Update DSP sensitivity and visual scene without reconnecting owned Music sockets. */
    boolean configure(double sensitivity, String scene);
    /** Beat or clock driven pulse, on the same generation-owned Music session. */
    boolean configurePulse(int rgb, boolean beatSync, int bpm, int minimum, int maximum,
                           int attackMs, int decayMs, double gamma);
    interface Listener {
        void onState(String state);
        void onBeat(double bpm, float level);
        void onError(String error);
    }
}
