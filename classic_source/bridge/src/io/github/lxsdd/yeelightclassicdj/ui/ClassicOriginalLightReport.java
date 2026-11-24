package io.github.lxsdd.yeelightclassicdj.ui;

/** Human-readable outcomes from the genuine original Classic DJ lifecycle.
 * Never accepts or exposes OEM IDs, LAN addresses, credentials or lamp data.
 * All counts come from real validated selected/owned runtime cohorts.
 */
public final class ClassicOriginalLightReport {
    public static final int NONE=0, GROUP_CHANGED=1, ORDER_CHANGED=2,
        SELECTION_CHANGED=3, DEVICE_ACTION=4, CONTEXT_CHANGED=5,
        LAN_RECHECKED=6, AUDIO_LOST=7, SCENE_CHANGED=8,
        TRANSPORT_LOST=9, HOST_PAUSED=10, USER_STOP=11;
    private ClassicOriginalLightReport() {}

    static String cause(int reason) {
        switch (reason) {
            case GROUP_CHANGED: return "Gruppenmitglieder geändert · Auswahl und LAN-Prüfung erneuern";
            case ORDER_CHANGED: return "Lampenreihenfolge geändert · LAN-Prüfung erneuern";
            case SELECTION_CHANGED: return "DJ-Teilnehmerauswahl geändert · LAN-Prüfung erneuern";
            case DEVICE_ACTION: return "Original-Geräteaktion · LAN-Prüfung erneuern";
            case CONTEXT_CHANGED: return "Originalgruppe gewechselt oder verlassen";
            case LAN_RECHECKED: return "LAN-Abgleich erneuert · Freigabe erneut bestätigen";
            case AUDIO_LOST: return "Original-Audioquelle beendet";
            case SCENE_CHANGED: return "Lichtszene für laufenden Modus nicht geeignet";
            case TRANSPORT_LOST: return "Music-Rückkanal unterbrochen";
            case HOST_PAUSED: return "Original-Musikansicht verlassen";
            case USER_STOP: return "Manuell beendet";
            default: return "DJ-Freigabe nicht mehr gültig";
        }
    }
    static String starting(int count) {
        return "DJ-Start · "+Math.max(0,count)+" ausgewählte Lampen werden geprüft";
    }
    /** A progress packet is counted only for the exact requested cohort.
     * No arbitrary OEM text, IP, id, firmware or partial output approval.
     */
    static int confirmedProgress(String state, int total) {
        if (state == null || total < 1) return -1;
        String prefix = "ORIGINAL_CONNECTING_";
        String suffix = "_OF_" + total + "_LAMPS";
        if (!state.startsWith(prefix) || !state.endsWith(suffix)) return -1;
        String count = state.substring(prefix.length(), state.length() - suffix.length());
        if (count.isEmpty() || count.length() > 9 ||
            (count.length() > 1 && count.charAt(0) == '0')) return -1;
        for (int i = 0; i < count.length(); i++)
            if (count.charAt(i) < '0' || count.charAt(i) > '9') return -1;
        try {
            int n = Integer.parseInt(count);
            return n >= 1 && n <= total ? n : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }
    static String connecting(int confirmed, int total) {
        if (total < 1 || confirmed < 1 || confirmed > total) return "";
        return "DJ-Start · " + confirmed + " von " + total +
            " Music-Verbindungen bestätigt · vollständige Gruppe wird geprüft";
    }
    /** Only after original Kotlin runtime confirms every OWNED Music participant. */
    static String active(int count, boolean timed) {
        return "DJ aktiv · "+Math.max(0,count)+" bestätigte Lampen · "+
            (timed?"Zeitsteuerung":"Original-Audio");
    }
    static String stopping(int reason) {
        return "DJ wird beendet · "+cause(reason);
    }
    /** Only after the output-gate owner has confirmed finished cleanup. */
    static String stopped(int reason) {
        return "DJ beendet · "+cause(reason);
    }
    static String uncertain() {
        return "DJ-Ausgabe gesperrt · Music-Sitzungsabschluss nicht bestätigt";
    }
    static String header(String status,int count,boolean confirmed) {
        return header(status,count,confirmed,0);
    }
    static String header(String status,int count,boolean confirmed,int partial) {
        if (confirmed) return "DJ aktiv · "+Math.max(0,count)+" Lampen  ﹀";
        if (status != null && status.startsWith("DJ-Start") &&
            count > 0 && partial > 0 && partial <= count)
            return "DJ-Start · " + partial + "/" + count + " Verbindungen  ﹀";
        if (status != null && !status.isEmpty()) {
            if (status.startsWith("DJ-Start")) return "DJ-Start · "+Math.max(0,count)+" Lampen  ﹀";
            if (status.startsWith("DJ wird beendet")) return "DJ wird beendet  ﹀";
            if (status.startsWith("DJ-Ausgabe gesperrt")) return "DJ gesperrt  ﹀";
            return "DJ-Status · Details  ﹀";
        }
        return "Licht-Effekte · Vorschau  ﹀";
    }
}
