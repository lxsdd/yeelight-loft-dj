package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Read-only observation of the exact original Classic device-selection click.
 *
 * The private clone hook passes the OEM device key at the already-existing
 * original Music -> GroupMusic navigation. This is NOT a lamp authorization,
 * an endpoint, an OEM device lookup, or a replacement for group membership.
 * Never log/export/store the key outside this process.
 */
public final class ClassicOriginalMusicSelection {
    static final long FRESH_MS = 5L * 60L * 1000L;
    private static String selectedId;
    private static long observedAtMs = Long.MIN_VALUE;

    private ClassicOriginalMusicSelection() {}

    /** Called only from the pinned private original click bridge. */
    public static synchronized void observedFromOriginalDevice(String originalId) {
        observeAt(originalId, monotonicMs());
    }

    static synchronized void observeAt(String originalId, long nowMs) {
        if (!valid(originalId)) {
            clear();
            return;
        }
        selectedId = originalId;
        observedAtMs = nowMs;
    }

    public static synchronized boolean fresh() {
        return freshAt(monotonicMs());
    }

    static synchronized boolean freshAt(long nowMs) {
        if (selectedId == null || observedAtMs == Long.MIN_VALUE) return false;
        long elapsed = nowMs - observedAtMs;
        if (elapsed < 0 || elapsed > FRESH_MS) {
            clear();
            return false;
        }
        return true;
    }

    /** Internal comparison only. Never turns a device ID into a LAN target. */
    static synchronized boolean matchesAt(String candidate, long nowMs) {
        return freshAt(nowMs) && valid(candidate) && selectedId.equals(candidate);
    }

    public static synchronized void clear() {
        selectedId = null;
        observedAtMs = Long.MIN_VALUE;
    }

    private static boolean valid(String value) {
        if (value == null || value.length() < 1 || value.length() > 192 ||
            value.trim().length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
