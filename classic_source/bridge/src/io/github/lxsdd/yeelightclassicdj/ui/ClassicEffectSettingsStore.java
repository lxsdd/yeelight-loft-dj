package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistence of ONLY new Loft-DJ effect parameters as clone-private UI
 * preferences, not Yeelight's original scenes, groups, accounts, favourites
 * or any proprietary schema. Read/resume never writes. Only the user's actual
 * SceneFragment control action or original MusicActivity effect choice saves.
 */
final class ClassicEffectSettingsStore {
    private static final String CLONE = "io.lxsdd.yeelightdj";
    private static final String LAST = "last-user-chosen-dj-effect";
    private static final String PREFIX = "dj-effect.";
    private final SharedPreferences prefs;

    ClassicEffectSettingsStore(Context context) {
        if (context == null || !CLONE.equals(context.getPackageName()))
            throw new SecurityException("Clone-only DJ effect configuration");
        prefs = context.getSharedPreferences("classic-loft-dj-effect-settings-v1",
            Context.MODE_PRIVATE);
        if (prefs == null) throw new IllegalStateException("No private preference store");
    }

    /** Called once from an actual original UI host; never initiates any work. */
    void restoreOnFirstHost() {
        ClassicSceneUiState.Selection previous = null;
        try {
            previous = readEncoded(prefs.getString(LAST, null), -1);
        } catch (RuntimeException denied) {
            // A corrupt/unreadable private preference must not break original
            // MusicActivity or SceneFragment initialization.
        }
        ClassicSceneUiState.initializeFromStore(previous);
    }

    /** A scene is selected in the one original host; stored parameters for
     * that symbolic DJ effect ID apply without inheriting another effect's
     * incompatible pulse/spatial controls. This is not a saved OEM scene.
     */
    boolean selectScene(int index) {
        if (index < 0 || index >= ClassicSceneUiState.SCENES.length) return false;
        ClassicSceneUiState.Selection profile = readProfile(index);
        if (profile == null) {
            profile = new ClassicSceneUiState.Selection(index, 2, false, 2, 0,
                1, true, 2, 2);
        }
        return ClassicSceneUiState.restoreSelection(profile);
    }

    ClassicSceneUiState.Selection readProfile(int scene) {
        if (scene < 0 || scene >= ClassicSceneUiState.SCENES.length) return null;
        try {
            return readEncoded(prefs.getString(PREFIX +
                ClassicSceneUiState.SCENES[scene], null), scene);
        } catch (RuntimeException denied) { return null; }
    }

    /** Exactly one atomic editor.commit from a user's explicit interaction.
     * No automatic initialization, migration, reset or remote synchronization.
     */
    boolean saveExplicit(ClassicSceneUiState.Selection selection) {
        if (!valid(selection)) return false;
        String data = encode(selection);
        try {
            return prefs.edit()
                .putString(PREFIX + ClassicSceneUiState.SCENES[selection.scene], data)
                .putString(LAST, data)
                .commit();
        } catch (RuntimeException denied) { return false; }
    }

    private static boolean valid(ClassicSceneUiState.Selection s) {
        return s != null &&
            s.scene >= 0 && s.scene < ClassicSceneUiState.SCENES.length &&
            s.speedIndex >= 0 && s.speedIndex < ClassicSceneUiState.SPEEDS.length &&
            s.trailIndex >= 0 && s.trailIndex < ClassicSceneUiState.TRAILS.length &&
            s.rangeIndex >= 0 && s.rangeIndex < ClassicSceneUiState.RANGES.length &&
            s.gainIndex >= 0 && s.gainIndex < 4 &&
            s.pulseTempoIndex >= 0 && s.pulseTempoIndex < 5 &&
            s.pulseShapeIndex >= 0 && s.pulseShapeIndex < 3 &&
            s.colorIndex >= 0 && s.colorIndex < ClassicSceneUiState.COLORS.length;
    }

    private static String encode(ClassicSceneUiState.Selection s) {
        return "v2," + s.scene + "," + s.speedIndex + "," + (s.reverse ? 1 : 0) +
            "," + s.trailIndex + "," + s.rangeIndex + "," + s.gainIndex +
            "," + (s.beatPulse ? 1 : 0) + "," + s.pulseTempoIndex +
            "," + s.pulseShapeIndex + "," + s.colorIndex;
    }

    /** Opaque bounded data only. Malformed, stale-version or foreign-scene
     * preferences never become app state or rewrite the original OEM store.
     */
    private static ClassicSceneUiState.Selection readEncoded(String data, int expectedScene) {
        if (data == null || data.length() < 18 || data.length() > 96) return null;
        String[] parts = data.split(",", -1);
        // v1 is an existing owner-authored profile without colour. Read it
        // unchanged using pink index zero; upgrade only after an explicit edit.
        boolean legacy = parts.length == 10 && "v1".equals(parts[0]);
        if (!legacy && !(parts.length == 11 && "v2".equals(parts[0]))) return null;
        try {
            int[] v = new int[legacy ? 9 : 10];
            for (int i = 0; i < v.length; i++) {
                if (!parts[i + 1].matches("[0-9]{1,3}")) return null;
                v[i] = Integer.parseInt(parts[i + 1]);
            }
            if (v[2] > 1 || v[6] > 1) return null;
            ClassicSceneUiState.Selection candidate =
                new ClassicSceneUiState.Selection(v[0], v[1], v[2] != 0,
                    v[3], v[4], v[5], v[6] != 0, v[7], v[8],
                    legacy ? 0 : v[9]);
            if (!valid(candidate) ||
                expectedScene >= 0 && candidate.scene != expectedScene) return null;
            return candidate;
        } catch (RuntimeException malformed) { return null; }
    }
}
