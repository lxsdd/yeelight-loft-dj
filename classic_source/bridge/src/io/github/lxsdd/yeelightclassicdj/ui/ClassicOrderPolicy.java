package io.github.lxsdd.yeelightclassicdj.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local UI order, never device state, OEM database order, or cloud synchronization.
 * Reuses the verified standalone Loft-DJ LampOrder move semantics for all
 * stable-ID original Classic list categories. No side effects.
 */
public final class ClassicOrderPolicy {
    public enum Surface {
        DEVICE_LIST, ROOM_LIST, GROUP_LIST, GROUP_MEMBERS, SCENE_LIST,
        FAVORITES, AUTOMATIONS, LIGHT_PATH
    }
    public static final int MAX_ITEMS = 512;
    private ClassicOrderPolicy() {}

    public static boolean validStableId(String id) {
        if (id == null || id.isEmpty() || id.length() > 256) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isISOControl(ch) || Character.isSurrogate(ch)) return false;
        }
        return true;
    }

    private static void validate(List<String> ids) {
        if (ids == null || ids.size() > MAX_ITEMS) throw new IllegalArgumentException("Invalid list");
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!validStableId(id) || !seen.add(id))
                throw new IllegalArgumentException("Missing or duplicate stable identifier");
        }
    }

    /** Entries absent from today's OEM adapter are never destroyed. New entries
     * retain their OEM order until the user explicitly drags a row.
     */
    public static List<String> project(List<String> visible, List<String> saved) {
        validate(visible);
        validate(saved);
        Set<String> active = new HashSet<>(visible);
        LinkedHashSet<String> output = new LinkedHashSet<>();
        for (String id : saved) if (active.contains(id)) output.add(id);
        output.addAll(visible);
        return new ArrayList<>(output);
    }

    public static List<String> move(List<String> visible, String dragged, String beforeId) {
        validate(visible);
        if (dragged == null || beforeId == null ||
                !visible.contains(dragged) || !visible.contains(beforeId))
            return new ArrayList<>(visible);
        List<String> copy = new ArrayList<>(visible);
        if (dragged.equals(beforeId)) return copy;
        copy.remove(dragged);
        copy.add(copy.indexOf(beforeId), dragged);
        return copy;
    }

    /** Final-index drop supports moving items to the last row as well. */
    public static List<String> moveToIndex(List<String> visible, String dragged, int index) {
        validate(visible);
        List<String> copy = new ArrayList<>(visible);
        if (!copy.contains(dragged) || index < 0 || index >= copy.size()) return copy;
        copy.remove(dragged);
        copy.add(index, dragged);
        return copy;
    }

    /** Keep missing/offline members in their former slots when a visible row
     * moves; append newly discovered stable IDs after the original slots.
     */
    public static List<String> mergeHidden(List<String> oldSaved, List<String> movedVisible) {
        validate(oldSaved);
        validate(movedVisible);
        Set<String> visibleSet = new HashSet<>(movedVisible);
        List<String> result = new ArrayList<>();
        int consumed = 0;
        for (String previous : oldSaved) {
            if (visibleSet.contains(previous)) {
                result.add(movedVisible.get(consumed++));
            } else {
                result.add(previous);
            }
        }
        for (String now : movedVisible) if (!result.contains(now)) result.add(now);
        validate(result);
        return result;
    }

    /** Drag/drop must not silently alter membership, target permissions or
     * scene definitions. A stale rendered list is rejected, not rebased.
     */
    public static List<String> dropAtIndex(List<String> actualVisible, List<String> displayed,
                                            List<String> oldSaved, String dragged, int index) {
        validate(actualVisible); validate(displayed); validate(oldSaved);
        if (!project(actualVisible, oldSaved).equals(displayed) ||
                !displayed.contains(dragged) || index < 0 || index >= displayed.size())
            throw new IllegalArgumentException("Stale or unverified adapter snapshot");
        return mergeHidden(oldSaved, moveToIndex(displayed, dragged, index));
    }

    public static List<String> drop(List<String> actualVisible, List<String> displayed,
                                     List<String> oldSaved, String dragged, String beforeId) {
        validate(actualVisible); validate(displayed); validate(oldSaved);
        if (!project(actualVisible, oldSaved).equals(displayed) ||
                !displayed.contains(dragged) || !displayed.contains(beforeId))
            throw new IllegalArgumentException("Stale or unverified adapter snapshot");
        return mergeHidden(oldSaved, move(displayed, dragged, beforeId));
    }
}
