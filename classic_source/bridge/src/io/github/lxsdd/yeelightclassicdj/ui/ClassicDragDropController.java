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

    /** Never mutate OEM list membership, scenes, cloud or device state. */
    public boolean onUserDrop(VerifiedList adapter, String dragged, String before) {
        if (adapter == null || !adapter.userEditing()) return false;
        try {
            List<String> actual = adapter.originalStableIds();
            List<String> shown = adapter.displayedStableIds();
            if (!ClassicOrderPolicy.project(actual,
                    store.read(adapter.surface(), adapter.stableScope())).equals(shown))
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
