package io.github.lxsdd.yeelightloftdj

/** Pure preflight; no requests and no device discovery are ever performed here. */
object GoogleHomeCapturePlan {
    fun validate(
        requested: List<YeelightDevice>,
        known: List<YeelightDevice>,
        unavailableIps: Set<String>,
    ): List<YeelightDevice> {
        require(requested.size in 1..GoogleHomeSceneExport.MAX_LAMPS) {
            "1 bis ${GoogleHomeSceneExport.MAX_LAMPS} Lampen auswählen"
        }
        require(requested.map { it.id }.distinct().size == requested.size &&
            requested.map { it.ip }.distinct().size == requested.size) {
            "Keine doppelten Geräte oder Netzwerkziele"
        }
        val knownById = known.associateBy { it.id }
        require(requested.all { target ->
            val current = knownById[target.id]
            current != null && current.ip == target.ip && current.port == target.port
        }) { "Geräteauswahl ist nicht mehr aktuell" }
        require(requested.none { it.ip in unavailableIps }) {
            "Mindestens eine Lampe hat eine noch offene Wiederherstellung"
        }
        require(requested.all { YeelightLan.isLocalIp(it.ip) }) {
            "Nur bereits bekannte, lokale Yeelight-Geräte sind zulässig"
        }
        return requested.toList()
    }
}