package io.github.lxsdd.yeelightclassicdj.ui;

import java.util.List;

/**
 * Original-Classic adapter integration boundary. OEM RecyclerView details are
 * attached only after a private class/row-ID audit proves their exact shape.
 * UI may issue drag handles or accessible move buttons through the same route.
 */
public final class ClassicDragDropController {
    public interface VerifiedList {
        ClassicOrderPolicy.Surface surface();
        String stableScope();
        List<String> originalStableIds();
        List<String> displayedStableIds();
        void render(List<String> orderedStableIds);
        boolean userEditing();
    }

    private final ClassicLocalOrderStore store;
    public ClassicDragDropController(ClassicLocalOrderStore store) {
        if (store == null) throw new IllegalArgumentException("Missing local store");
        this.store = store;
    }

    /** Route touch drag and accessible move actions through same final-index path. */
    public boolean onUserMove(VerifiedList adapter, String dragged, int index) {
        if (adapter == null || !adapter.userEditing()) return false;
        try {
            List<String> actual = adapter.originalStableIds();
            List<String> shown = adapter.displayedStableIds();
            // Check the exact OEM adapter snapshot even for an apparent no-op.
            // A stale drag event must never silently succeed or overwrite state.
            if (!ClassicOrderPolicy.project(actual,
                    store.read(adapter.surface(), adapter.stableScope())).equals(shown))
                return false;
            if (!ClassicOrderPolicy.validStableId(dragged) ||
                    !shown.contains(dragged) || index < 0 || index >= shown.size())
                return false;
            List<String> revised = ClassicOrderPolicy.moveToIndex(shown, dragged, index);
            if (shown.equals(revised)) return true;
            if (!store.applyUserDropAtIndex(adapter.surface(), adapter.stableScope(),
                    actual, shown, dragged, index)) return false;
            adapter.render(ClassicOrderPolicy.project(actual,
                store.read(adapter.surface(), adapter.stableScope())));
            return true;
        } catch (IllegalArgumentException | SecurityException invalid) {
            return false;
        }
    }

    /** Both keyboard/screen-reader Move Up/Down and drag use one validated route.
     * At list boundaries nothing moves and no order preference is modified.
     */
    public boolean onUserMoveRelative(VerifiedList adapter, String id, int offset) {
        if (adapter == null || !adapter.userEditing() || (offset != -1 && offset != 1))
            return false;
        try {
            List<String> shown = adapter.displayedStableIds();
            int index = shown.indexOf(id);
            if (index < 0 || index + offset < 0 || index + offset >= shown.size())
                return false;
            return onUserMove(adapter, id, index + offset);
        } catch (IllegalArgumentException | SecurityException invalid) {
            return false;
        }
    }

    public boolean onUndoLastMove(VerifiedList adapter) {
        if (adapter == null || !adapter.userEditing()) return false;
        try {
            List<String> actual = adapter.originalStableIds();
            List<String> shown = adapter.displayedStableIds();
            if (!store.undoLastUserDrop(adapter.surface(), adapter.stableScope(), actual, shown))
                return false;
            adapter.render(ClassicOrderPolicy.project(actual,
                store.read(adapter.surface(), adapter.stableScope())));
            return true;
        } catch (IllegalArgumentException | SecurityException invalid) {
            return false;
        }
    }

    /** Never mutate OEM list membership, scenes, cloud or device state. */
    public boolean onUserDrop(VerifiedList adapter, String dragged, String before) {
        if (adapter == null || !adapter.userEditing()) return false;
        try {
            List<String> actual = adapter.originalStableIds();
            List<String> shown = adapter.displayedStableIds();
            if (!ClassicOrderPolicy.project(actual,
                    store.read(adapter.surface(), adapter.stableScope())).equals(shown))
                return false;
            if (!ClassicOrderPolicy.validStableId(dragged) ||
                    !ClassicOrderPolicy.validStableId(before) ||
                    !shown.contains(dragged) || !shown.contains(before))
                return false;
            List<String> revised = ClassicOrderPolicy.move(shown, dragged, before);
            if (shown.equals(revised)) return true;
            if (!store.applyUserDrop(adapter.surface(), adapter.stableScope(),
                    actual, shown, dragged, before)) return false;
            adapter.render(ClassicOrderPolicy.project(actual,
                store.read(adapter.surface(), adapter.stableScope())));
            return true;
        } catch (IllegalArgumentException | SecurityException invalid) {
            return false;
        }
    }
}
