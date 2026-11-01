package io.github.lxsdd.yeelightloftdj

/**
 * Pure policy shared by lamp identification, physical reorder and recovery UI.
 * Never mistake a persisted recovery snapshot for an idle lighting session.
 */
object LampOperationGate {
    fun pendingIps(runningIps: Set<String>, recoveryIps: Set<String>): Set<String> =
        runningIps + recoveryIps

    fun needsRecovery(
        activeScene: String?,
        runningIps: Set<String>,
        recoveryIps: Set<String>,
        musicSession: Boolean,
    ): Boolean = activeScene != null || pendingIps(runningIps, recoveryIps).isNotEmpty() || musicSession

    fun canIdentify(
        busy: Boolean,
        activeScene: String?,
        runningIps: Set<String>,
        recoveryIps: Set<String>,
        musicSession: Boolean,
    ): Boolean = !busy && !needsRecovery(activeScene, runningIps, recoveryIps, musicSession)

    /** A failed stop on A never blocks identifying healthy B.
     * Never touch A until its outstanding stop/restore has been verified.
     */
    fun canIdentifyLamp(
        busy: Boolean,
        ip: String,
        runningIps: Set<String>,
        recoveryIps: Set<String>,
        musicSession: Boolean,
        musicStopping: Boolean = false,
    ): Boolean = !busy && !musicSession && !musicStopping &&
        ip !in pendingIps(runningIps, recoveryIps)

    /** Reordering groups modifies local settings, never the actual light output. */
    fun canEditConfiguration(
        busy: Boolean,
        musicSession: Boolean,
        musicStopping: Boolean,
    ): Boolean = !busy && !musicSession && !musicStopping

    /** New scene commands must not overwrite an unrecovered bulb. */
    fun usableForNewScene(
        requestedIps: List<String>,
        runningIps: Set<String>,
        recoveryIps: Set<String>,
    ): List<String> = requestedIps.filterNot {
        it in pendingIps(runningIps, recoveryIps)
    }
}
