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
    // Clone-local DJ scene colours; OEM device colours/scenes are never edited.
    // Index zero preserves every existing v1 profile's original pink.
    static final int[] COLORS = {
        0xFF2E79, 0xF44336, 0xFF8C00, 0xFFD83D,
        0x34C759, 0x00C8B8, 0x20BFE5, 0x3877FF,
        0x8054D9, 0xD34ABD, 0xFFB06D, 0xFFFFFF
    };
    static final String[] COLOR_NAMES = {
        "Pink", "Rot", "Orange", "Gelb", "Grün", "Türkis",
        "Cyan", "Blau", "Violett", "Magenta", "Apricot", "Weiß"
    };

    static final String[] PALETTE_NAMES = {"Einfarbig", "Regenbogen"};

    static final class Selection {
        final int scene, speedIndex, trailIndex, rangeIndex;
        final boolean reverse, beatPulse;
        final int gainIndex, pulseTempoIndex, pulseShapeIndex, colorIndex, paletteIndex;
        Selection(int scene, int speedIndex, boolean reverse,
                  int trailIndex, int rangeIndex, int gainIndex,
                  boolean beatPulse, int pulseTempoIndex, int pulseShapeIndex) {
            this(scene, speedIndex, reverse, trailIndex, rangeIndex, gainIndex,
                beatPulse, pulseTempoIndex, pulseShapeIndex, 0);
        }
        Selection(int scene, int speedIndex, boolean reverse,
                  int trailIndex, int rangeIndex, int gainIndex,
                  boolean beatPulse, int pulseTempoIndex, int pulseShapeIndex,
                  int colorIndex) {
            this(scene, speedIndex, reverse, trailIndex, rangeIndex, gainIndex,
                beatPulse, pulseTempoIndex, pulseShapeIndex, colorIndex, 0);
        }
        Selection(int scene, int speedIndex, boolean reverse,
                  int trailIndex, int rangeIndex, int gainIndex,
                  boolean beatPulse, int pulseTempoIndex, int pulseShapeIndex,
                  int colorIndex, int paletteIndex) {
            this.paletteIndex = paletteIndex;
            this.colorIndex = colorIndex;
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
            chosen.pulseShapeIndex < 0 || chosen.pulseShapeIndex >= 3 ||
            chosen.colorIndex < 0 || chosen.colorIndex >= COLORS.length ||
            chosen.paletteIndex < 0 || chosen.paletteIndex >= PALETTE_NAMES.length)
            return false;
        current = new Selection(chosen.scene, chosen.speedIndex, chosen.reverse,
            chosen.trailIndex, chosen.rangeIndex, chosen.gainIndex, chosen.beatPulse,
            chosen.pulseTempoIndex, chosen.pulseShapeIndex, chosen.colorIndex,
            chosen.paletteIndex);
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
            v.pulseTempoIndex, v.pulseShapeIndex, v.colorIndex, v.paletteIndex);
    }

    static synchronized boolean update(int scene, int speedIndex,
                                       boolean reverse, int trailIndex, int rangeIndex) {
        if (scene < 0 || scene >= SCENES.length ||
            speedIndex < 0 || speedIndex >= SPEEDS.length ||
            trailIndex < 0 || trailIndex >= TRAILS.length ||
            rangeIndex < 0 || rangeIndex >= RANGES.length) return false;
        Selection old = current;
        current = new Selection(scene, speedIndex, reverse, trailIndex, rangeIndex,
            old.gainIndex, old.beatPulse, old.pulseTempoIndex, old.pulseShapeIndex,
            old.colorIndex, old.paletteIndex);
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
            pulseTempoIndex, pulseShapeIndex, old.colorIndex, old.paletteIndex);
        revision++;
        return true;
    }

    /** Explicit user choice. A colour edit is a pure profile update: no LAN write. */
    static synchronized boolean updateColor(int colorIndex) {
        if (colorIndex < 0 || colorIndex >= COLORS.length) return false;
        Selection old = current;
        current = new Selection(old.scene, old.speedIndex, old.reverse,
            old.trailIndex, old.rangeIndex, old.gainIndex, old.beatPulse,
            old.pulseTempoIndex, old.pulseShapeIndex, colorIndex, old.paletteIndex);
        revision++;
        return true;
    }

    /** Clone-local palette choice. Never modifies OEM scenes or target devices. */
    static synchronized boolean updatePalette(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= PALETTE_NAMES.length) return false;
        Selection old = current;
        current = new Selection(old.scene, old.speedIndex, old.reverse,
            old.trailIndex, old.rangeIndex, old.gainIndex, old.beatPulse,
            old.pulseTempoIndex, old.pulseShapeIndex, old.colorIndex, paletteIndex);
        revision++;
        return true;
    }
}
