package io.github.lxsdd.yeelightclassicdj.ui;

/** Process-local UI status for an exact user-owned analysis start. No device state. */
final class ClassicAnalysisStartResult {
    private String owner;
    private String failure;

    synchronized void begin(String acceptedOwner) {
        if (acceptedOwner == null || acceptedOwner.isEmpty()) return;
        owner = acceptedOwner;
        failure = null;
    }

    synchronized boolean fail(String candidateOwner, String reason) {
        if (!matches(candidateOwner) || reason == null || reason.isEmpty()) return false;
        failure = reason;
        return true;
    }

    synchronized String failureFor(String candidateOwner) {
        return matches(candidateOwner) ? failure : null;
    }

    synchronized boolean clear(String candidateOwner) {
        if (!matches(candidateOwner)) return false;
        owner = null;
        failure = null;
        return true;
    }

    private boolean matches(String candidateOwner) {
        return candidateOwner != null && owner != null && owner.equals(candidateOwner);
    }
}
