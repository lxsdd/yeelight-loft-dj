package io.github.lxsdd.yeelightclassicdj.ui;

/** Per-service generation fence. No Android, transport, or device side effects.
 * A token is issued only to one active session; canceled tokens never become live
 * again. All methods are thread-safe for main-thread and audio-worker callbacks.
 */
final class ClassicSessionFence {
    private long next;
    private long active;

    synchronized long begin() {
        if (active != 0L) return 0L;
        if (next == Long.MAX_VALUE) throw new IllegalStateException("Session generations exhausted");
        active = ++next;
        return active;
    }

    synchronized boolean owns(long token) {
        return token > 0L && active == token;
    }

    synchronized boolean cancel(long token) {
        if (!owns(token)) return false;
        active = 0L;
        return true;
    }

    synchronized long cancel() {
        long former = active;
        active = 0L;
        return former;
    }
}
