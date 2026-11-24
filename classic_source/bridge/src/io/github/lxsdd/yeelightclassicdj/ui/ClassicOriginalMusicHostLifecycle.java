package io.github.lxsdd.yeelightclassicdj.ui;

/**
 * One real resumed original MusicActivity owns one attached DJ audio observer.
 * OEM post-resume layout rechecks are not new audio sessions; callbacks from
 * pause and destroy are not two detaches. This process-local gate owns no
 * OEM data, audio source, network connection, preferences or lamp.
 */
final class ClassicOriginalMusicHostLifecycle {
    private boolean resumed;

    synchronized boolean enterResumed() {
        if (resumed) return false;
        resumed = true;
        return true;
    }

    synchronized boolean leaveResumed() {
        if (!resumed) return false;
        resumed = false;
        return true;
    }
}
