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
    private static ClassicOriginalGroupMemberWitness.Snapshot originalMemberKeys =
        ClassicOriginalGroupMemberWitness.unknown();
    // Session-only, explicitly chosen OEM identities. NEVER LAN authorization.
    private static final java.util.LinkedHashSet<String> djIntentKeys =
        new java.util.LinkedHashSet<>();
    // Ephemeral, exact LAN discovery proof for EXPLICITLY chosen OEM members.
    // Unlike G()/T(), these are actual IP endpoints; no persistence or ID
    // heuristics. A closed GroupMusic Activity keeps a short-lived snapshot
    // only for the returning original MusicActivity.
    private static final java.util.LinkedHashMap<String,String> lanTargets =
        new java.util.LinkedHashMap<>();
    private static String lanGroupOwner;
    private static long lanBoundAtMs = Long.MIN_VALUE;
    static final long LAN_FRESH_MS = 90_000L;
    // Source- and user-originated participant changes invalidate all output
    // authorizations; expiry of a one-shot LAN cache does not end an owned socket.
    private static long outputRevision = 1L;
    private static void revokeLan() {
        lanTargets.clear();
        lanGroupOwner = null;
        lanBoundAtMs = Long.MIN_VALUE;
    }

    private ClassicOriginalMusicSelection() {}

    /** Immutable, in-process participant grant. Never persisted or exported. */
    public static final class OutputLease {
        private final long revision;
        private final String owner;
        private final java.util.List<String> ips;
        private OutputLease(long revision, String owner, java.util.List<String> ips) {
            this.revision = revision;
            this.owner = owner;
            this.ips = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(ips));
        }
        public java.util.List<String> targets() {
            return new java.util.ArrayList<>(ips);
        }
        public int size() { return ips.size(); }
    }

    public static synchronized OutputLease leaseForOriginalOutput() {
        return leaseForOriginalOutputAt(monotonicMs());
    }
    static synchronized OutputLease leaseForOriginalOutputAt(long nowMs) {
        java.util.List<String> ips = resolvedOriginalLanIpsAt(nowMs);
        return ips.isEmpty() || selectedId == null
            ? null : new OutputLease(outputRevision, selectedId, ips);
    }
    /** The owner/participant revision, not a discovery TTL, fences live output. */
    public static synchronized boolean isOutputLeaseCurrent(OutputLease lease) {
        return lease != null && lease.revision == outputRevision &&
            selectedId != null && selectedId.equals(lease.owner);
    }

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
        // Only the live original group Activity may submit a new list.
        // After Back, retain the last read-only count for MusicActivity's
        // return screen, but reject late refreshes from a destroyed host.
        if (!groupEntryFreshAt(nowMs)) return;
        // Only a bounded display witness is retained, never an authorized target.
        java.util.Set<String> before = new java.util.HashSet<>(
            originalMemberKeys.keysForDisplay());
        originalMemberKeys = ClassicOriginalGroupMemberWitness.inspect(members);
        if (!originalMemberKeys.identitiesVerified ||
            !before.equals(new java.util.HashSet<>(originalMemberKeys.keysForDisplay()))) {
            ++outputRevision;
            revokeLan();
        }
        if (originalMemberKeys.identitiesVerified)
            djIntentKeys.retainAll(originalMemberKeys.keysForDisplay());
        else djIntentKeys.clear();
        observedGroupCount = originalMemberKeys.observedCount;
        if (observedGroupCount >= 0) groupObservedAtMs = nowMs;
    }

    /** A verified read-only identity snapshot while the original group screen
     * is active. It does NOT authorize any LAN/Yeelight device operations.
     * -1 means missing/ambiguous OEM identities, including a closed group.
     */
    public static synchronized int originalGroupVerifiedIdentityCount() {
        return verifiedIdentityCountAt(monotonicMs());
    }

    static synchronized int verifiedIdentityCountAt(long nowMs) {
        return groupEntryFreshAt(nowMs) && groupFreshAt(nowMs) &&
            originalMemberKeys.identitiesVerified
            ? originalMemberKeys.observedCount : -1;
    }

    /** In-process, package-private original display keys only. */
    static synchronized java.util.List<String> verifiedDisplayKeysAt(long nowMs) {
        if (verifiedIdentityCountAt(nowMs) < 0)
            return java.util.Collections.emptyList();
        return new java.util.ArrayList<>(originalMemberKeys.keysForDisplay());
    }

    /** In-clone visual ordering namespace, derived only from the original
     * device-click + matching GroupMusic Activity entry. NOT a lamp target. */
    static synchronized String originalGroupDisplayScope() {
        return groupEntryFreshAt(monotonicMs()) && selectedId != null
            ? "classic-original-group-members-" + selectedId : null;
    }

    /** Compare the live OEM ListAdapter identities against the last pinned
     * J1() membership witness in ONE synchronized session. Reordering may
     * change presentation, never membership; a new/unreported OEM row or an
     * absent former member must not be silently included in DJ intent.
     */
    static synchronized boolean matchesCurrentOriginalMemberSetAt(
            java.util.List<String> adapterIds, long nowMs) {
        if (adapterIds == null || verifiedIdentityCountAt(nowMs) < 0)
            return false;
        java.util.List<String> witnessed = originalMemberKeys.keysForDisplay();
        return adapterIds.size() == witnessed.size() &&
            new java.util.HashSet<>(adapterIds).size() == adapterIds.size() &&
            new java.util.HashSet<>(adapterIds).equals(
                new java.util.HashSet<>(witnessed));
    }

    /** Real GroupMusic row/menu call. Both source and J1 snapshot must agree
     * atomically, including group scope and freshness. No address resolution.
     */
    static synchronized boolean markDjIntentFromOriginalAdapter(
            String id, boolean include, java.util.List<String> adapterIds) {
        long nowMs = monotonicMs();
        return matchesCurrentOriginalMemberSetAt(adapterIds,nowMs) &&
            markDjIntentAt(id,include,nowMs);
    }

    static synchronized boolean markAllDjIntentFromOriginalAdapter(
            boolean include, java.util.List<String> adapterIds) {
        long nowMs = monotonicMs();
        return matchesCurrentOriginalMemberSetAt(adapterIds,nowMs) &&
            markAllDjIntentAt(include,nowMs);
    }

    /** Exact USER intent is never automatically a LAN transport authority. */
    /** Display order is user-controlled; the OEM adapter order is used ONLY
     * to verify membership. Both lists must describe the same unique members.
     * Spatial Scanner/Meteor/Wave order must not silently revert to J1() order.
     */
    private static boolean validDisplayOrder(java.util.List<String> originalIds,
                                             java.util.List<String> displayIds) {
        return originalIds != null && displayIds != null &&
            displayIds.size() == originalIds.size() &&
            new java.util.HashSet<>(displayIds).size() == displayIds.size() &&
            new java.util.HashSet<>(displayIds).equals(
                new java.util.HashSet<>(originalIds));
    }

    static synchronized java.util.List<String> selectedKeysForLanCheck(
            java.util.List<String> originalIds) {
        return selectedKeysForLanCheck(originalIds,originalIds);
    }

    static synchronized java.util.List<String> selectedKeysForLanCheck(
            java.util.List<String> originalIds, java.util.List<String> displayIds) {
        return selectedKeysForLanCheckAt(originalIds,displayIds,monotonicMs());
    }

    static synchronized java.util.List<String> selectedKeysForLanCheckAt(
            java.util.List<String> originalIds, java.util.List<String> displayIds,
            long nowMs) {
        if (!matchesCurrentOriginalMemberSetAt(originalIds,nowMs) ||
            !validDisplayOrder(originalIds,displayIds) || djIntentKeys.isEmpty())
            return java.util.Collections.emptyList();
        java.util.ArrayList<String> ordered=new java.util.ArrayList<>();
        for (String key:displayIds)
            if (djIntentKeys.contains(key)) ordered.add(key);
        return ordered;
    }

    /** Install only a positive, single-scan proof produced by the LAN parser
     * while the original GroupMusic adapter and selected identity set still
     * match. No direct caller-supplied IP map is accepted here.
     */
    static synchronized boolean acceptLanProof(
            java.util.List<String> originalAdapterIds,
            ClassicOriginalLanDiscovery.VerifiedTargets proof) {
        return acceptLanProof(originalAdapterIds,originalAdapterIds,proof);
    }

    /** Original membership and displayed spatial order are independently
     * verified before a user-approved LAN proof becomes a Music output lease.
     */
    static synchronized boolean acceptLanProof(
            java.util.List<String> originalAdapterIds,
            java.util.List<String> displayIds,
            ClassicOriginalLanDiscovery.VerifiedTargets proof) {
        return acceptLanProofAt(originalAdapterIds,displayIds,proof,monotonicMs());
    }

    static synchronized boolean acceptLanProofAt(
            java.util.List<String> originalAdapterIds,
            ClassicOriginalLanDiscovery.VerifiedTargets proof,long now) {
        return acceptLanProofAt(originalAdapterIds,originalAdapterIds,proof,now);
    }

    static synchronized boolean acceptLanProofAt(
            java.util.List<String> originalAdapterIds,
            java.util.List<String> displayIds,
            ClassicOriginalLanDiscovery.VerifiedTargets proof,long now) {
        if (!matchesCurrentOriginalMemberSetAt(originalAdapterIds,now) ||
            !validDisplayOrder(originalAdapterIds,displayIds) ||
            proof==null || !proof.freshAt(now) || djIntentKeys.isEmpty())
            return false;
        java.util.Map<String,String> exact=proof.copy();
        if (!exact.keySet().equals(djIntentKeys) ||
            new java.util.HashSet<>(exact.values()).size()!=exact.size())
            return false;
        // No Stream API: original Classic supports Android before API 24.
        for (String ip : exact.values())
            if (!ClassicOriginalLanDiscovery.privateIpv4(ip)) return false;
        ++outputRevision;
        revokeLan();
        // LinkedHashMap iteration order is the ACTUAL device choreography.
        // Original membership still controls authorization; only the already
        // displayed, explicit local DnD order controls spatial progression.
        for (String id : displayIds)
            if (exact.containsKey(id)) lanTargets.put(id,exact.get(id));
        lanGroupOwner=selectedId;
        lanBoundAtMs=now;
        return true;
    }

    /** Verified LAN endpoints in exact OEM display/selection order. Only the
     * foreground original Music UI may ask the user to confirm output. A
     * selection change, group switch, host destruction or timeout returns [].
     */
    static synchronized java.util.List<String> resolvedOriginalLanIps() {
        return resolvedOriginalLanIpsAt(monotonicMs());
    }

    static synchronized java.util.List<String> resolvedOriginalLanIpsAt(long now) {
        if (!freshAt(now) || lanGroupOwner==null ||
            !lanGroupOwner.equals(selectedId) || lanBoundAtMs==Long.MIN_VALUE ||
            now<lanBoundAtMs || now-lanBoundAtMs>LAN_FRESH_MS ||
            lanTargets.isEmpty() || !lanTargets.keySet().equals(djIntentKeys)) {
            revokeLan();
            return java.util.Collections.emptyList();
        }
        return new java.util.ArrayList<>(lanTargets.values());
    }

    public static synchronized int resolvedOriginalLanCount() {
        return resolvedOriginalLanIps().size();
    }

    /** Explicit user intention only, independent of original OEM group membership. */
    public static synchronized boolean markDjIntent(String id, boolean include) {
        return markDjIntentAt(id,include,monotonicMs());
    }
    static synchronized boolean markDjIntentAt(String id, boolean include, long nowMs) {
        if (verifiedIdentityCountAt(nowMs) <= 0 || id == null ||
            !originalMemberKeys.keysForDisplay().contains(id)) return false;
        ++outputRevision;
        revokeLan();
        if (include) djIntentKeys.add(id);
        else djIntentKeys.remove(id);
        return true;
    }
    public static synchronized boolean markAllDjIntent(boolean include) {
        return markAllDjIntentAt(include,monotonicMs());
    }
    static synchronized boolean markAllDjIntentAt(boolean include, long nowMs) {
        if (verifiedIdentityCountAt(nowMs) <= 0) return false;
        ++outputRevision;
        revokeLan();
        if (include) djIntentKeys.addAll(originalMemberKeys.keysForDisplay());
        else djIntentKeys.clear();
        return true;
    }
    /** Display count; not a selected/online/authorized transport cohort. */
    public static synchronized int djIntentCount() {
        return djIntentCountAt(monotonicMs());
    }
    static synchronized int djIntentCountAt(long nowMs) {
        return groupFreshAt(nowMs) ? djIntentKeys.size() : 0;
    }

    /** -1 = unknown, zero = actual observed empty OEM group. Not a DJ grant. */
    public static synchronized int originalGroupMemberCount() {
        return groupMemberCountAt(monotonicMs());
    }

    static synchronized int groupMemberCountAt(long nowMs) {
        return groupFreshAt(nowMs) ? observedGroupCount : -1;
    }

    private static boolean groupEntryFreshAt(long nowMs) {
        return groupEntryObserved && freshAt(nowMs) &&
            groupObservedAtMs != Long.MIN_VALUE && nowMs >= groupObservedAtMs &&
            nowMs - groupObservedAtMs <= FRESH_MS;
    }

    private static boolean groupFreshAt(long nowMs) {
        if (!freshAt(nowMs) || groupObservedAtMs == Long.MIN_VALUE ||
            nowMs < groupObservedAtMs || nowMs - groupObservedAtMs > FRESH_MS) {
            resetGroup();
            return false;
        }
        // A null/unreadable J1() list is UNKNOWN, not the end of an OEM
        // group Activity. Its next valid refresh must still be accepted.
        return observedGroupCount >= 0;
    }

    /** Closing OEM GroupMusic rejects further list callbacks, but leaves a
     * bounded READ-ONLY last-seen count for the resumed original MusicActivity.
     * No chosen membership, LAN address or ownership survives this transition.
     */
    public static synchronized void originalGroupClosed() {
        groupEntryObserved = false;
        // The count is safe to show briefly after Back; private OEM keys are not.
        originalMemberKeys = ClassicOriginalGroupMemberWitness.unknown();
    }

    private static void resetGroup() {
        ++outputRevision;
        revokeLan();
        originalMemberKeys = ClassicOriginalGroupMemberWitness.unknown();
        djIntentKeys.clear();
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
