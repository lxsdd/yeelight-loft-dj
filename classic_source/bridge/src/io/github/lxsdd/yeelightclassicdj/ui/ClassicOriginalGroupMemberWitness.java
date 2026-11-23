package io.github.lxsdd.yeelightclassicdj.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Bounded, read-only witness of original GroupMusic J1() member identities.
 * A key is NEVER a selected device, authorized endpoint, ownership proof or
 * transport destination. No OEM models, network addresses or logs retained.
 */
public final class ClassicOriginalGroupMemberWitness {
    private static final String MEMBER_BASE = "com.yeelight.yeelib.device.base.e";
    private static final int MAX_MEMBERS = ClassicOrderPolicy.MAX_ITEMS;

    public static final class Snapshot {
        public final int observedCount;
        public final boolean identitiesVerified;
        private final List<String> keys;
        Snapshot(int count, boolean verified, List<String> keys) {
            observedCount = count;
            identitiesVerified = verified;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }
        /** Local UI order only. Never pass these values to a lamp transport. */
        List<String> keysForDisplay() { return keys; }
    }

    private ClassicOriginalGroupMemberWitness() {}

    public static Snapshot unknown() {
        return new Snapshot(-1, false, Collections.<String>emptyList());
    }

    public static Snapshot inspect(List<?> original) {
        if (original == null) return unknown();
        final int count;
        try { count = original.size(); }
        catch (RuntimeException unreadable) { return unknown(); }
        if (count < 0 || count > MAX_MEMBERS)
            return new Snapshot(count, false, Collections.<String>emptyList());
        ArrayList<String> ids = new ArrayList<>(count);
        HashSet<String> unique = new HashSet<>();
        try {
            for (int i = 0; i < count; i++) {
                Object item = original.get(i);
                if (item == null) return unverified(count);
                // Original GroupMusicAdapter.getView() casts these entries to
                // yeelib.device.base.e, which inherits T() from device.base.c.
                Class<?> base = Class.forName(MEMBER_BASE, false,
                    item.getClass().getClassLoader());
                if (!base.isInstance(item)) return unverified(count);
                Method getter = item.getClass().getMethod("T");
                if (getter.getParameterTypes().length != 0 ||
                    getter.getReturnType() != String.class)
                    return unverified(count);
                Object raw = getter.invoke(item);
                if (!(raw instanceof String) ||
                    !ClassicOrderPolicy.validStableId((String)raw) ||
                    ((String)raw).trim().isEmpty() ||
                    !unique.add((String)raw))
                    return unverified(count);
                ids.add((String)raw);
            }
            if (original.size() != count) return unverified(count);
            return new Snapshot(count, true, ids);
        } catch (ReflectiveOperationException | RuntimeException drift) {
            // Unknown OEM ABI, mutable list or inaccessible identity.
            return unverified(count);
        }
    }

    private static Snapshot unverified(int count) {
        return new Snapshot(count, false, Collections.<String>emptyList());
    }
}
