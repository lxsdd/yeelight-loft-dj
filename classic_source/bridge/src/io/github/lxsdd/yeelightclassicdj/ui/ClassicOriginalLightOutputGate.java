package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Process-wide exclusive owner of the original Classic DJ reverse-output lane.
 *
 * An Activity can disappear while its owned Music sockets are being closed on
 * a worker thread. The next Activity must NOT start a second Music group until
 * that close has finished. This class owns no sockets, OEM objects or lamp IDs;
 * it is an in-process admission fence, not a hardware ownership assertion.
 */
public final class ClassicOriginalLightOutputGate {
    private static long serial;
    private static long holder;
    private static boolean closing;
    private static boolean quarantined;

    private ClassicOriginalLightOutputGate() {}

    /** Zero means busy or unsafe; no caller may take over a prior owner. */
    public static synchronized long tryAcquire() {
        if (holder != 0L || quarantined) return 0L;
        if (serial == Long.MAX_VALUE) {
            quarantined = true; // Never recycle a previously issued generation.
            return 0L;
        }
        holder = ++serial;
        closing = false;
        return holder;
    }

    public static synchronized boolean isBusy() {
        return holder != 0L || quarantined;
    }

    public static synchronized boolean isQuarantined() {
        return quarantined;
    }

    /** Closing fences starts immediately, before the asynchronous STOP worker. */
    public static synchronized boolean beginClose(long token) {
        if (token == 0L || holder != token || closing) return false;
        closing = true;
        return true;
    }

    /**
     * Only the exact closing generation may release admission. If STOP failed,
     * the remote state is unknown; quarantine until the process ends rather
     * than risk blind re-handshake or stopping someone else's Music session.
     */
    public static synchronized boolean finishClose(long token, boolean confirmed) {
        if (token == 0L || token != holder || !closing) return false;
        if (!confirmed) {
            quarantined = true;
            return false;
        }
        if (quarantined) return false;
        holder = 0L;
        closing = false;
        return true;
    }
}
