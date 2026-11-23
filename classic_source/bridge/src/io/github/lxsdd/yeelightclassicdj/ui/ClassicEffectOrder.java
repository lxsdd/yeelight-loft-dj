package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * VerifiedList for the seven owned Loft-DJ effect cards, genuinely mounted
 * in original Classic SceneFragment and MusicActivity. Not an OEM scene ID
 * extractor or a second scene database. Existing clone-only user-drop
 * preferences, stale snapshot fences and one-step Undo remain authoritative.
 */
final class ClassicEffectOrder implements ClassicDragDropController.VerifiedList {
    private static final String SCOPE = "loft-dj-effect-cards";
    private static final List<String> ORIGINAL = Collections.unmodifiableList(
        Arrays.asList(ClassicSceneUiState.SCENES.clone()));
    private final ClassicLocalOrderStore store;
    private final ClassicDragDropController controller;
    private List<String> shown;

    ClassicEffectOrder(Context context) {
        store = new ClassicLocalOrderStore(context);
        controller = new ClassicDragDropController(store);
        shown = ClassicOrderPolicy.project(ORIGINAL,
            store.read(ClassicOrderPolicy.Surface.SCENE_LIST, SCOPE));
    }

    static int sceneIndex(String id) { return ORIGINAL.indexOf(id); }

    boolean refresh() {
        List<String> next = ClassicOrderPolicy.project(ORIGINAL,
            store.read(ClassicOrderPolicy.Surface.SCENE_LIST, SCOPE));
        if (next.equals(shown)) return false;
        shown = next;
        return true;
    }

    List<String> order() { return new ArrayList<>(shown); }

    /** Called only from SceneFragment's explicit drag or accessible buttons. */
    boolean move(String id, int finalIndex) {
        return controller.onUserMove(this, id, finalIndex);
    }
    boolean moveRelative(String id, int direction) {
        return controller.onUserMoveRelative(this, id, direction);
    }
    boolean undo() { return controller.onUndoLastMove(this); }

    @Override public ClassicOrderPolicy.Surface surface() {
        return ClassicOrderPolicy.Surface.SCENE_LIST;
    }
    @Override public String stableScope() { return SCOPE; }
    @Override public List<String> originalStableIds() { return new ArrayList<>(ORIGINAL); }
    @Override public List<String> displayedStableIds() { return order(); }
    @Override public boolean userEditing() { return true; }
    @Override public void render(List<String> ordered) {
        if (ordered == null || ordered.size() != ORIGINAL.size() ||
            !ClassicOrderPolicy.project(ORIGINAL, ordered).equals(ordered))
            throw new IllegalArgumentException("Unverified DJ effect order");
        shown = new ArrayList<>(ordered);
    }
}
