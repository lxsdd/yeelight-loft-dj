package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject

/**
 * Private app-only recovery journal. This must NEVER enter scene exports:
 * private LAN addresses and actual household light state belong on the device.
 */
object SceneRecovery {
    fun supported(snapshot: LampSnapshot): Boolean {
        if (!snapshot.isOn) return true
        return when (snapshot.colorMode) {
            1 -> snapshot.rgb != null
            2 -> snapshot.temperature != null
            3 -> snapshot.hue != null && snapshot.saturation != null
            else -> false
        }
    }

    fun commands(snapshot: LampSnapshot): List<LampCommand> {
        require(supported(snapshot)) { "Ursprünglicher Lichtzustand ist nicht vollständig wiederherstellbar" }
        return if (snapshot.isOn) listOf(LampCommands.power(true)) + snapshot.restoreCommands()
        else listOf(LampCommands.power(false))
    }

    fun encode(snapshots: Map<String, LampSnapshot>): String {
        val entries = JSONArray()
        snapshots.forEach { (ip, s) ->
            require(YeelightLan.isLocalIp(ip))
            require(supported(s))
            entries.put(JSONObject()
                .put("ip", ip).put("on", s.isOn)
                .put("bright", s.brightness).put("mode", s.colorMode)
                .put("rgb", s.rgb ?: JSONObject.NULL)
                .put("ct", s.temperature ?: JSONObject.NULL)
                .put("hue", s.hue ?: JSONObject.NULL)
                .put("sat", s.saturation ?: JSONObject.NULL))
        }
        return JSONObject().put("version", 1).put("snapshots", entries).toString()
    }

    fun decode(text: String?): Map<String, LampSnapshot> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val doc = JSONObject(text)
            if (doc.optInt("version") != 1) return emptyMap()
            val entries = doc.optJSONArray("snapshots") ?: return emptyMap()
            if (entries.length() > 64) return emptyMap()
            val result = mutableMapOf<String, LampSnapshot>()
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val ip = item.getString("ip")
                if (!YeelightLan.isLocalIp(ip)) continue
                fun read(name: String): Int? = if (item.isNull(name)) null else item.optInt(name)
                val snap = LampSnapshot(item.getBoolean("on"), item.getInt("bright"),
                    item.getInt("mode"), read("rgb"), read("ct"), read("hue"), read("sat"))
                if (supported(snap) && snap.brightness in 1..100) result[ip] = snap
            }
            result
        } catch (_: Exception) { emptyMap() }
    }
}
