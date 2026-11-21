package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * Zero-device light-look visualization of the currently selected effect.
 * Read-only: explicitly a preview of existing DSP input, never a bulb-state
 * assertion and never a replacement for the OEM colour picker.
 */
public final class ClassicDjEffectPreview {
    private ClassicDjEffectPreview() {}

    public static String explanation(String scene, boolean beatSync) {
        if ("SPECTRUM".equals(scene))
            return "Spektrum · vier tatsächlich gemessene Frequenzbänder";
        if ("AMBIENT".equals(scene))
            return "Ruhig · gemittelter Klangpegel, weiche Reaktion";
        if ("BEAT".equals(scene))
            return "Beat · kurzer Impuls nur bei erkanntem Schlag";
        if ("PULSE".equals(scene))
            return beatSync ? "Puls · Beat-gesteuerte Helligkeitshüllkurve" :
                              "Puls · frei eingestellter Tempo-Takt";
        if ("SCANNER".equals(scene))
            return "Scanner · gerichteter Knight-Rider-Lauf auf freigegebenen Lampen";
        if ("METEOR".equals(scene))
            return "Meteor · Sternschnuppe mit abklingendem Schweif";
        if ("WAVE".equals(scene))
            return "Welle · räumliche Sinuswelle entlang der Lichtreihenfolge";
        return "Effekt nicht verifiziert";
    }

    public static int intensity(String scene, ClassicDjLiveFrame frame,
                                boolean beatSync, int tempoBpm, long clockMs) {
        if (frame == null || !frame.fresh()) return 0;
        double amplitude = frame.level;
        final double value;
        if ("SPECTRUM".equals(scene)) {
            value = Math.max(Math.max(frame.bass, frame.mid),
                             Math.max(frame.presence, frame.treble));
        } else if ("AMBIENT".equals(scene)) {
            value = (0.40 * frame.bass + 0.30 * frame.mid +
                     0.20 * frame.presence + 0.10 * frame.treble) * 0.55;
        } else if ("BEAT".equals(scene)) {
            value = frame.lastBeatAgeMs < 180L ? 1.0 : 0.0;
        } else if ("PULSE".equals(scene)) {
            if (beatSync) {
                final long age = frame.lastBeatAgeMs;
                value = age > 1800L ? 0.0 :
                    (age < 90L ? (double) age / 90.0 :
                                      Math.exp(-(age - 90.0) / 550.0));
            } else {
                if (tempoBpm < 40 || tempoBpm > 240 || clockMs < 0) return 0;
                long period = 60000L / tempoBpm;
                long elapsed = clockMs % period;
                value = elapsed < 100L ? elapsed / 100.0 :
                                      Math.exp(-(elapsed - 100.0) / 350.0);
            }
        } else if ("SCANNER".equals(scene)) {
            value = (clockMs / 210L) % 2 == 0 ? 0.85 : 0.18;
        } else if ("METEOR".equals(scene)) {
            value = Math.exp(-((clockMs / 165L) % 5) * 0.65);
        } else if ("WAVE".equals(scene)) {
            value = 0.5 + 0.5 * Math.sin(clockMs / 500.0);
        } else {
            return 0;
        }
        if (!Double.isFinite(value)) return 0;
        return (int) Math.round(Math.min(1.0, Math.max(0.0, value)) * 100.0);
    }
}
