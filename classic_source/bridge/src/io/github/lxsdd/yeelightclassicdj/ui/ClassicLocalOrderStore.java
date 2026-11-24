package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Separate, private, clone-only UI order. Reads never write. The only write is
 * called from a user-originated, stable-ID drag/drop event after snapshot CAS.
 * Does NOT touch original Classic data, account DBs, clouds or device ordering.
 */
public final class ClassicLocalOrderStore {
    private static final String CLONE = "io.lxsdd.yeelightdj";
    private final SharedPreferences prefs;
    // An immediate, session-local Undo receipt per surface+scope. Never synced
    // to Cloud or hidden in OEM DB; one confirmed user drop is reversible.
    private final Map<String, UndoReceipt> lastDrop = new HashMap<>();
    private static final class UndoReceipt {
        final List<String> before, after, originalVisible;
        UndoReceipt(List<String> before, List<String> after,
                    List<String> originalVisible) {
            this.before = new ArrayList<>(before);
            this.after = new ArrayList<>(after);
            this.originalVisible = new ArrayList<>(originalVisible);
        }
    }

    public ClassicLocalOrderStore(Context context) {
        if (context == null || !CLONE.equals(context.getPackageName()))
            throw new SecurityException("Clone-only local ordering");
        prefs = context.getSharedPreferences("classic-local-ui-order-v1", Context.MODE_PRIVATE);
    }

    private String key(ClassicOrderPolicy.Surface surface, String stableScope) {
        if (surface == null || !ClassicOrderPolicy.validStableId(stableScope))
            throw new IllegalArgumentException("Unverified stable scope");
        String raw = surface.name() + "\u0000" + stableScope;
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder("sort.");
        for (byte b : digest) {
            out.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return out.toString();
    }

    public List<String> read(ClassicOrderPolicy.Surface surface, String scope) {
        String data = prefs.getString(key(surface, scope), null);
        if (data == null || data.isEmpty()) return Collections.emptyList();
        // Encode length first so stable identifiers can include punctuation.
        try {
            List<String> items = new ArrayList<>();
            int offset = 0;
            while (offset < data.length()) {
                int colon = data.indexOf(':', offset);
                if (colon < 0 || colon - offset > 4) return Collections.emptyList();
                int len = Integer.parseInt(data.substring(offset, colon));
                int start = colon + 1;
                if (len <= 0 || len > 256 || start + len > data.length())
                    return Collections.emptyList();
                String id = data.substring(start, start + len);
                if (!ClassicOrderPolicy.validStableId(id) || items.contains(id))
                    return Collections.emptyList();
                items.add(id);
                if (items.size() > ClassicOrderPolicy.MAX_ITEMS) return Collections.emptyList();
                offset = start + len;
            }
            return items;
        } catch (RuntimeException corrupt) {
            // A corrupt preference never overwrites OEM data or starts an action.
            return Collections.emptyList();
        }
    }

    private static String encode(List<String> order) {
        StringBuilder out = new StringBuilder();
        for (String id : order) out.append(id.length()).append(':').append(id);
        return out.toString();
    }

    /** Caller must be a verified original-list drag handler and must pass the
     * exact previously rendered visible IDs. No automatic initial migration.
     */
    public synchronized boolean applyUserDrop(ClassicOrderPolicy.Surface surface, String scope,
                                 List<String> visibleFromOem, List<String> rendered,
                                 String draggedId, String beforeId) {
        String storageKey = key(surface, scope);
        List<String> saved = read(surface, scope);
        List<String> updated = ClassicOrderPolicy.drop(
            visibleFromOem, rendered, saved, draggedId, beforeId);
        if (updated.equals(saved)) return true;
        if (!prefs.edit().putString(storageKey, encode(updated)).commit()) return false;
        lastDrop.put(storageKey, new UndoReceipt(saved, updated, visibleFromOem));
        return true;
    }

    /** Undo only the exact last committed order for that same verified list.
     * A subsequent mutation or an OEM refresh with a changed adapter snapshot
     * cannot silently rewrite group membership or another scope.
     */
    public synchronized boolean undoLastUserDrop(ClassicOrderPolicy.Surface surface,
                                String scope, List<String> actualVisible,
                                List<String> rendered) {
        String storageKey = key(surface, scope);
        UndoReceipt receipt = lastDrop.get(storageKey);
        if (receipt == null) return false;
        if (!read(surface, scope).equals(receipt.after)) {
            lastDrop.remove(storageKey);
            return false;
        }
        if (!actualVisible.equals(receipt.originalVisible)) {
            lastDrop.remove(storageKey);
            return false;
        }
        if (!ClassicOrderPolicy.project(actualVisible, receipt.after).equals(rendered))
            return false;
        if (!prefs.edit().putString(storageKey, encode(receipt.before)).commit())
            return false;
        lastDrop.remove(storageKey);
        return true;
    }

    public synchronized boolean applyUserDropAtIndex(ClassicOrderPolicy.Surface surface,
                                 String scope, List<String> actualVisible,
                                 List<String> rendered, String draggedId, int index) {
        String storageKey = key(surface, scope);
        List<String> saved = read(surface, scope);
        List<String> updated = ClassicOrderPolicy.dropAtIndex(
            actualVisible, rendered, saved, draggedId, index);
        if (updated.equals(saved)) return true;
        if (!prefs.edit().putString(storageKey, encode(updated)).commit()) return false;
        lastDrop.put(storageKey, new UndoReceipt(saved, updated, actualVisible));
        return true;
    }
}
