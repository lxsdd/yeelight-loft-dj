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
}
