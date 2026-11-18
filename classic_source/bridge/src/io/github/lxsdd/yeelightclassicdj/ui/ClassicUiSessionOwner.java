package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * In-process identity of the UI which started a Classic microphone analysis.
 * This is not OEM participant authorization and grants NO lamp capabilities.
 * Generation changes on each explicit start; stale panels cannot reconfigure
 * or stop the next owner's session.
 */
final class ClassicUiSessionOwner {
    private String active;

    static boolean valid(String candidate) {
        if (candidate == null || candidate.length() != 36) return false;
        try {
            return java.util.UUID.fromString(candidate).toString().equals(candidate);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    synchronized boolean begin(String candidate) {
        if (active != null || !valid(candidate)) return false;
        active = candidate;
        return true;
    }

    synchronized boolean owns(String candidate) {
        return active != null && active.equals(candidate);
    }

    synchronized String current() { return active; }

    synchronized void clear() { active = null; }
}
