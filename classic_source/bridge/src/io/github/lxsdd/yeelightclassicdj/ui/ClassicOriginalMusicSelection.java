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
    // This is a read-only OEM group-list witness, NEVER a selected/online cohort.
    private static boolean groupEntryObserved;
    private static int observedGroupCount = -1;
    private static long groupObservedAtMs = Long.MIN_VALUE;

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
        resetGroup();
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

    /** Called at the real original group-manager entry, after its Intent key read. */
    public static synchronized void observedOriginalGroupEntry(String originalId) {
        observeGroupEntryAt(originalId, monotonicMs());
    }

    static synchronized void observeGroupEntryAt(String originalId, long nowMs) {
        resetGroup();
        // An untrusted or unrelated Activity cannot claim the earlier OEM click.
        groupEntryObserved = matchesAt(originalId, nowMs);
        if (groupEntryObserved) groupObservedAtMs = nowMs;
    }

    /** Counts a real OEM J1() list. Does not enumerate/retain OEM device objects. */
    public static synchronized void observedOriginalGroupMembers(java.util.List<?> members) {
        observeGroupMembersAt(members, monotonicMs());
    }

    static synchronized void observeGroupMembersAt(java.util.List<?> members, long nowMs) {
        if (!groupFreshAt(nowMs) || members == null) {
            observedGroupCount = -1;
            return;
        }
        try {
            // No fixed fleet maximum, no access to IDs/IPs, no device commands.
            observedGroupCount = members.size();
            groupObservedAtMs = nowMs;
        } catch (RuntimeException unreadable) {
            observedGroupCount = -1;
        }
    }

    /** -1 = unknown, zero = actual observed empty OEM group. Not a DJ grant. */
    public static synchronized int originalGroupMemberCount() {
        return groupMemberCountAt(monotonicMs());
    }

    static synchronized int groupMemberCountAt(long nowMs) {
        return groupFreshAt(nowMs) ? observedGroupCount : -1;
    }

    private static boolean groupFreshAt(long nowMs) {
        if (!groupEntryObserved || !freshAt(nowMs) ||
            groupObservedAtMs == Long.MIN_VALUE || nowMs < groupObservedAtMs ||
            nowMs - groupObservedAtMs > FRESH_MS) {
            resetGroup();
            return false;
        }
        return true;
    }

    /** Original GroupMusic Activity teardown, never a release of lamp access. */
    public static synchronized void originalGroupClosed() { resetGroup(); }

    private static void resetGroup() {
        groupEntryObserved = false;
        observedGroupCount = -1;
        groupObservedAtMs = Long.MIN_VALUE;
    }

    public static synchronized void clear() {
        selectedId = null;
        observedAtMs = Long.MIN_VALUE;
        resetGroup();
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
