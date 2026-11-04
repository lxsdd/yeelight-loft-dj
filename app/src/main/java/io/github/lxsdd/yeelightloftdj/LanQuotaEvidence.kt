package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject

/** Passive historical receipt. Never authorizes LAN access or clears quarantine. */
internal data class LanQuotaEvidence(
    val ip: String,
    val method: String,
    val appAdmittedLastMinute: Int,
    val observedAtEpochMs: Long,
)

internal object LanQuotaEvidenceCodec {
    private const val LIMIT = 12
    private val safeMethod = Regex("[a-z_]{1,32}")

    fun decode(raw: String?): List<LanQuotaEvidence> {
        if (raw.isNullOrBlank() || raw.length > 8192) return emptyList()
        return try {
            val doc = JSONObject(raw)
            if (doc.optInt("version") != 1) return emptyList()
            val items = doc.optJSONArray("events") ?: return emptyList()
            if (items.length() > LIMIT) return emptyList()
            (0 until items.length()).mapNotNull { n ->
                val item = items.optJSONObject(n) ?: return@mapNotNull null
                val ip = item.optString("ip")
                val method = item.optString("method")
                val count = item.optInt("count", -1)
                val time = item.optLong("time", -1)
                if (!YeelightLan.isLocalIp(ip) || !safeMethod.matches(method) ||
                    count !in 0..24 || time <= 0) null
                else LanQuotaEvidence(ip, method, count, time)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun append(
        previous: String?, ip: String, method: String,
        appAdmittedLastMinute: Int, observedAtEpochMs: Long,
    ): String {
        require(YeelightLan.isLocalIp(ip))
        require(safeMethod.matches(method))
        require(appAdmittedLastMinute in 0..24 && observedAtEpochMs > 0)
        val entry = LanQuotaEvidence(ip, method, appAdmittedLastMinute, observedAtEpochMs)
        val events = (decode(previous).filterNot { it.ip == ip } + entry).takeLast(LIMIT)
        val array = JSONArray()
        events.forEach {
            array.put(JSONObject().put("ip", it.ip).put("method", it.method)
                .put("count", it.appAdmittedLastMinute)
                .put("time", it.observedAtEpochMs))
        }
        return JSONObject().put("version", 1).put("events", array).toString()
    }
}
