package io.github.lxsdd.yeelightclassicdj.runtime;

import android.content.Context;

/** Implemented by the previously tested private Kotlin Classic DJ checkpoint, NOT by Classic internals. */
public interface DjRuntime {
    /** Must fail closed if no participants/mic permission or any session remains ambiguous. */
    void start(Context context, String source, Listener listener) throws Exception;
    /** Idempotent, per-bulb safe stop/close; must not send blind recovery retries. */
    void stop();
    interface Listener {
        void onState(String state);
        void onBeat(double bpm, float level);
        void onError(String error);
    }
}
