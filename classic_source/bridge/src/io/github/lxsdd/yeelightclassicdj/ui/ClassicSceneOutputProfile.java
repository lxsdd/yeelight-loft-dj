package io.github.lxsdd.yeelightclassicdj.ui;

import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;

/**
 * ONE immutable effect/beat/geometry translation for the original Classic
 * SceneFragment and the original MusicActivity. Unlike their old divergent
 * arrays, saved pulse SHAPE, gain and timer settings now reach the real owned
 * engine with identical values. No device, network, OEM or preference writes.
 */
public final class ClassicSceneOutputProfile {
    private static final double[] GAINS = {0.5, 1.0, 2.0, 4.0};
    private static final int[] PULSE_BPM = {60, 90, 120, 150, 180};
    private static final int[][] PULSE_SHAPES = {
        {12, 65, 160, 900}, // smooth
        {8, 100, 70, 330},  // strong
        {12, 90, 90, 600}   // normal
    };
    private static final double[] PULSE_GAMMA = {2.2, 1.2, 2.0};
    private static final int PINK_RGB = 0xFF2E79;

    private ClassicSceneOutputProfile() {}

    public static DjRuntime.SceneSettings fromSelection(ClassicSceneUiState.Selection s) {
        if (s == null || s.scene < 0 || s.scene >= ClassicSceneUiState.SCENES.length ||
            s.gainIndex < 0 || s.gainIndex >= GAINS.length ||
            s.pulseTempoIndex < 0 || s.pulseTempoIndex >= PULSE_BPM.length ||
            s.pulseShapeIndex < 0 || s.pulseShapeIndex >= PULSE_SHAPES.length ||
            s.speedIndex < 0 || s.speedIndex >= ClassicSceneUiState.SPEEDS.length ||
            s.trailIndex < 0 || s.trailIndex >= ClassicSceneUiState.TRAILS.length ||
            s.rangeIndex < 0 || s.rangeIndex >= ClassicSceneUiState.RANGES.length)
            throw new IllegalArgumentException("Invalid original scene selection");
        int[] shape = PULSE_SHAPES[s.pulseShapeIndex];
        int[] spatialRange = ClassicSceneUiState.RANGES[s.rangeIndex];
        return new DjRuntime.SceneSettings(
            GAINS[s.gainIndex], ClassicSceneUiState.SCENES[s.scene],
            PINK_RGB, s.beatPulse, PULSE_BPM[s.pulseTempoIndex],
            shape[0], shape[1], shape[2], shape[3],
            PULSE_GAMMA[s.pulseShapeIndex],
            PINK_RGB, ClassicSceneUiState.SPEEDS[s.speedIndex],
            s.reverse, ClassicSceneUiState.TRAILS[s.trailIndex],
            spatialRange[0], spatialRange[1]);
    }
}
