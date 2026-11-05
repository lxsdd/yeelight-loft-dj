package io.github.lxsdd.yeelightloftdj

import android.content.Context

/** Shared owner survives screen-lock/Activity recreation only during Live-DJ. */
internal object LoftControllerStore {
    private var value: LoftController? = null
    private var activities = 0

    @Synchronized fun attach(context: Context): LoftController {
        val owner = value ?: LoftController(context.applicationContext).also { value = it }
        activities++
        return owner
    }

    @Synchronized fun current(): LoftController? = value

    @Synchronized fun detach(owner: LoftController) {
        if (value !== owner) return
        activities = (activities - 1).coerceAtLeast(0)
        cleanup()
    }

    @Synchronized fun serviceFinished() = cleanup()

    private fun cleanup() {
        val owner = value ?: return
        if (activities == 0 && !LiveDjSessionService.isHolding() && !owner.minimalProbeRunning) {
            owner.close()
            value = null
        }
    }
}
