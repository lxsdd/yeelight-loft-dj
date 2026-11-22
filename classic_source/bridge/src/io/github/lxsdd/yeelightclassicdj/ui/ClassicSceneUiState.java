package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * One process-local scene selection for the original Classic SceneFragment
 * and MusicActivity. No new scene DB, cloud record, OEM device ID or writer.
 * Deliberately ephemeral: restart returns to a safe zero-target default.
 */
final class ClassicSceneUiState {
    static final String[] SCENES = {
        "SPECTRUM", "AMBIENT", "BEAT", "PULSE", "SCANNER", "METEOR", "WAVE"
    };
    static final String[] LABELS = {
        "Spektrum", "Ruhig", "Beat", "Puls", "Scanner", "Meteor", "Welle"
    };
    static final int[] SPEEDS = {50, 75, 100, 150, 200};
    static final int[] TRAILS = {1, 2, 4, 6, 8};
    static final int[][] RANGES = {{1, 100}, {5, 60}, {20, 85}};

    static final class Selection {
        final int scene, speedIndex, trailIndex, rangeIndex;
        final boolean reverse;
        Selection(int scene, int speedIndex, boolean reverse,
                  int trailIndex, int rangeIndex) {
            this.scene = scene;
            this.speedIndex = speedIndex;
            this.reverse = reverse;
            this.trailIndex = trailIndex;
            this.rangeIndex = rangeIndex;
        }
    }

    private static Selection current = new Selection(0, 2, false, 2, 0);
    private ClassicSceneUiState() {}

    static synchronized Selection snapshot() {
        Selection v = current;
        return new Selection(v.scene, v.speedIndex, v.reverse,
            v.trailIndex, v.rangeIndex);
    }

    static synchronized boolean update(int scene, int speedIndex,
                                       boolean reverse, int trailIndex, int rangeIndex) {
        if (scene < 0 || scene >= SCENES.length ||
            speedIndex < 0 || speedIndex >= SPEEDS.length ||
            trailIndex < 0 || trailIndex >= TRAILS.length ||
            rangeIndex < 0 || rangeIndex >= RANGES.length) return false;
        current = new Selection(scene, speedIndex, reverse, trailIndex, rangeIndex);
        return true;
    }
}
