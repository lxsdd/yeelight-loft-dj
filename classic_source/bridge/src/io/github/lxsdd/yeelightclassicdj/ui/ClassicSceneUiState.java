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
        final boolean reverse, beatPulse;
        final int gainIndex, pulseTempoIndex, pulseShapeIndex;
        Selection(int scene, int speedIndex, boolean reverse,
                  int trailIndex, int rangeIndex, int gainIndex,
                  boolean beatPulse, int pulseTempoIndex, int pulseShapeIndex) {
            this.scene = scene;
            this.speedIndex = speedIndex;
            this.reverse = reverse;
            this.trailIndex = trailIndex;
            this.rangeIndex = rangeIndex;
            this.gainIndex = gainIndex;
            this.beatPulse = beatPulse;
            this.pulseTempoIndex = pulseTempoIndex;
            this.pulseShapeIndex = pulseShapeIndex;
        }
    }

    private static long revision;
    // Process-local, first original host reads a verified private DJ profile.
    // No implicit write occurs on attach/resume; later host recreation cannot
    // reset a user's in-process edits or resurrect an old scene selection.
    private static boolean initializedFromStore;

    static synchronized void initializeFromStore(Selection stored) {
        if (initializedFromStore) return;
        initializedFromStore = true;
        if (revision == 0 && stored != null) restoreSelection(stored);
    }

    static synchronized boolean restoreSelection(Selection chosen) {
        if (chosen == null || chosen.scene < 0 || chosen.scene >= SCENES.length ||
            chosen.speedIndex < 0 || chosen.speedIndex >= SPEEDS.length ||
            chosen.trailIndex < 0 || chosen.trailIndex >= TRAILS.length ||
            chosen.rangeIndex < 0 || chosen.rangeIndex >= RANGES.length ||
            chosen.gainIndex < 0 || chosen.gainIndex >= 4 ||
            chosen.pulseTempoIndex < 0 || chosen.pulseTempoIndex >= 5 ||
            chosen.pulseShapeIndex < 0 || chosen.pulseShapeIndex >= 3)
            return false;
        current = new Selection(chosen.scene, chosen.speedIndex, chosen.reverse,
            chosen.trailIndex, chosen.rangeIndex, chosen.gainIndex, chosen.beatPulse,
            chosen.pulseTempoIndex, chosen.pulseShapeIndex);
        revision++;
        return true;
    }
    private static Selection current =
        new Selection(0, 2, false, 2, 0, 1, true, 2, 2);
    private ClassicSceneUiState() {}

    /** Monotonic in-process UI-change marker, not a persisted OEM scene ID. */
    static synchronized long revision() { return revision; }

    static synchronized Selection snapshot() {
        Selection v = current;
        return new Selection(v.scene, v.speedIndex, v.reverse,
            v.trailIndex, v.rangeIndex, v.gainIndex, v.beatPulse,
            v.pulseTempoIndex, v.pulseShapeIndex);
    }

    static synchronized boolean update(int scene, int speedIndex,
                                       boolean reverse, int trailIndex, int rangeIndex) {
        if (scene < 0 || scene >= SCENES.length ||
            speedIndex < 0 || speedIndex >= SPEEDS.length ||
            trailIndex < 0 || trailIndex >= TRAILS.length ||
            rangeIndex < 0 || rangeIndex >= RANGES.length) return false;
        Selection old = current;
        current = new Selection(scene, speedIndex, reverse, trailIndex, rangeIndex,
            old.gainIndex, old.beatPulse, old.pulseTempoIndex, old.pulseShapeIndex);
        revision++;
        return true;
    }

    static synchronized boolean updateAudio(int gainIndex, boolean beatPulse,
                                            int pulseTempoIndex, int pulseShapeIndex) {
        if (gainIndex < 0 || gainIndex >= 4 || pulseTempoIndex < 0 ||
            pulseTempoIndex >= 5 || pulseShapeIndex < 0 || pulseShapeIndex >= 3)
            return false;
        Selection old = current;
        current = new Selection(old.scene, old.speedIndex, old.reverse,
            old.trailIndex, old.rangeIndex, gainIndex, beatPulse,
            pulseTempoIndex, pulseShapeIndex);
        revision++;
        return true;
    }
}
