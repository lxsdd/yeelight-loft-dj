package io.github.lxsdd.yeelightloftdj

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

/** Uses only already stored discovery methods. Never opens any connection. */
data class FirmwareInsight(
    val chroma: String,
    val firmware: String,
    val evidence: String,
    val flashAllowed: Boolean = false,
)

object FirmwareInsightReport {
    fun summarize(model: String, methods: Set<String>): FirmwareInsight {
        val p = FirmwareResearchCatalog.researchProfile(model)
        val chroma = when (ChromaUdpResearch.fromSupport(methods)) {
            ChromaUdpResearchEvidence.NO_SSDP_SUPPORT_EVIDENCE -> "Chroma unbekannt: kein SUPPORT-Nachweis"
            ChromaUdpResearchEvidence.NOT_ADVERTISED -> "Chroma zuletzt nicht gemeldet"
            ChromaUdpResearchEvidence.CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD ->
                "Chroma erwähnt, UDP-Sitzung nicht bestätigt"
            ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED ->
                "Chroma-UDP v1 gemeldet, nicht an der Lampe getestet"
            ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED ->
                "Chroma-UDP v2 gemeldet, nicht an der Lampe getestet"
        }
        val path = when (p.route) {
            FirmwareResearchRoute.NONE_VERIFIED -> "Keine bestätigte Alternativfirmware"
            FirmwareResearchRoute.CONTROLLER_REPLACEMENT_REFERENCE ->
                "Fremdprojekt: Controller-Ersatz, kein Direkt-Flash"
            FirmwareResearchRoute.ESPHOME_CONFIGURATION_REFERENCE ->
                "ESPHome-Fremdbeispiel, Hardware-Eignung ungeprüft"
            FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE ->
                "Modellspezifisches Fremd-OTA-Beispiel, nicht freigegeben"
        }
        val evidence = if (methods.isEmpty()) "Keine gespeicherte SUPPORT-Liste"
            else "Aus zuletzt gespeicherten SUPPORT-Daten, nicht live geprüft"
        return FirmwareInsight(chroma, path, evidence)
    }
}

enum class CeilingManualStatus {
    EMPTY, TOO_FEW, HOURLY_PATTERN, IRREGULAR, INVALID, DST_AMBIGUOUS, TOO_LARGE
}

data class CeilingManualResult(
    val status: CeilingManualStatus,
    val count: Int = 0,
    val intervalsMinutes: List<Long> = emptyList(),
    val confirmsFirmwareDefect: Boolean = false,
)

enum class CeilingComparison { UNKNOWN, SIMULTANEOUS, INDEPENDENT }

object CeilingManualTimeline {
    private val formatter = DateTimeFormatter
        .ofPattern("dd.MM.uuuu HH:mm:ss", Locale.GERMANY)
        .withResolverStyle(ResolverStyle.STRICT)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val shape = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""")
    private const val ONE_HOUR = 3_600_000L
    private const val FIVE_MINUTES = 300_000L

    /**
     * Accepts only anonymous disconnection timestamps (one per line).
     * Rejects pasted router events containing MACs, IPs and device names.
     * These times are user observations, NOT proof of a real AP disconnection.
     */
    private fun extract(raw: String): Pair<CeilingManualStatus?, List<Long>> {
        if (raw.length > 8192) return CeilingManualStatus.TOO_LARGE to emptyList()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size > 128) return CeilingManualStatus.TOO_LARGE to emptyList()
        if (lines.isEmpty()) return CeilingManualStatus.EMPTY to emptyList()
        val values = mutableListOf<Long>()
        for (line in lines) {
            if (!shape.matches(line)) return CeilingManualStatus.INVALID to emptyList()
            val date = try { LocalDateTime.parse(line, formatter) }
                catch (_: DateTimeParseException) {
                    return CeilingManualStatus.INVALID to emptyList()
                }
            val offsets = berlin.rules.getValidOffsets(date)
            if (offsets.size != 1) return CeilingManualStatus.DST_AMBIGUOUS to emptyList()
            values.add(date.toEpochSecond(offsets.single()) * 1000L)
        }
        if (values.distinct().size != values.size)
            return CeilingManualStatus.INVALID to emptyList()
        return null to values.sorted()
    }

    fun analyze(raw: String): CeilingManualResult {
        val (error, times) = extract(raw)
        if (error != null) return CeilingManualResult(error)
        if (times.size < 4) return CeilingManualResult(CeilingManualStatus.TOO_FEW, times.size)
        val gaps = times.zipWithNext { a, b -> b - a }
        return CeilingManualResult(
            if (gaps.all { abs(it - ONE_HOUR) <= FIVE_MINUTES })
                CeilingManualStatus.HOURLY_PATTERN else CeilingManualStatus.IRREGULAR,
            times.size,
            gaps.map { it / 60_000L },
        )
    }

    fun compare(first: String, second: String): CeilingComparison {
        val (ea, a) = extract(first)
        val (eb, b) = extract(second)
        if (ea != null || eb != null || a.size < 4 || b.size < 4)
            return CeilingComparison.UNKNOWN
        var i = 0
        var j = 0
        var matches = 0
        while (i < a.size && j < b.size) {
            val delta = a[i] - b[j]
            when {
                abs(delta) <= 60_000L -> { matches++; i++; j++ }
                delta < 0 -> i++
                else -> j++
            }
        }
        return if (matches >= 3) CeilingComparison.SIMULTANEOUS
            else CeilingComparison.INDEPENDENT
    }
}
