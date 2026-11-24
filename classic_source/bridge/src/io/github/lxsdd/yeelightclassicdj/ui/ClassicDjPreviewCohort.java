package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * UI-only provenance of the original Classic music participant count.
 * No OEM keys, IPs, accounts, device lookups, Music grants or commands.
 *
 * The original GroupMusic user's explicit DJ opt-in is NOT proof that any
 * reverse Music session was acquired. Even a full OWNED ACK confirms only
 * a transport cohort, never a physical light colour or bulb position.
 */
final class ClassicDjPreviewCohort {
    static final int PLACEHOLDER_CELLS = 8;
    enum Proof { PLACEHOLDER, SELECTED, OWNED }

    static final class Snapshot {
        final int cells;
        final Proof proof;
        Snapshot(int cells, Proof proof) {
            this.cells = cells;
            this.proof = proof;
        }
        String label() {
            switch (proof) {
                case SELECTED:
                    return cells + " vorgemerkte Lampen · reine Reihenfolge-Vorschau, "
                        + "keine bestätigte Lichtausgabe";
                case OWNED:
                    return cells + " bestätigte Music-Verbindungen · reine Effektvorschau, "
                        + "kein gemessener Lampenzustand";
                default:
                    return PLACEHOLDER_CELLS + " schematische Platzhalter · "
                        + "noch keine Lampen für DJ vorgemerkt";
            }
        }
    }

    private ClassicDjPreviewCohort() {}

    /** User intent, a pending start and confirmed OWNED callback remain
     * separate. Never call a mere proposed group a confirmed output.
     */
    static Snapshot from(int userIntentCount, int runningCount, boolean ownedAck) {
        if (runningCount > 0) return new Snapshot(runningCount,
            ownedAck ? Proof.OWNED : Proof.SELECTED);
        if (userIntentCount > 0) return new Snapshot(userIntentCount, Proof.SELECTED);
        return new Snapshot(PLACEHOLDER_CELLS, Proof.PLACEHOLDER);
    }
}
