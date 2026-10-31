package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class SceneArchiveData(val flows: List<LoftScene>, val chases: List<ChasePreset>)

/**
 * Portable, versioned and strictly bounded offline scene archive.
 * Never includes device IDs, private IP addresses, rooms, passwords or signers.
 */
object SceneArchive {
    const val mimeType = "application/json"
    const val fileName = "yeelight-loft-dj-szenen.json"
    const val maxBytes = 96 * 1024
    private const val format = "yeelight-loft-dj-scenes"
    private const val version = 1

    fun encode(data: SceneArchiveData): String {
        require(data.flows.size + data.chases.size <= 64)
        val flows = JSONArray()
        data.flows.forEach { scene ->
            val frames = JSONArray()
            scene.frames.forEach { frame ->
                frames.put(JSONObject().put("ms", frame.durationMs)
                    .put("rgb", frame.rgb).put("brightness", frame.brightness))
            }
            flows.put(JSONObject().put("id", scene.id).put("name", scene.name)
                .put("description", scene.description).put("frames", frames))
        }
        val chases = JSONArray()
        data.chases.forEach { preset ->
            val config = preset.config
            chases.put(JSONObject().put("id", preset.id).put("name", preset.name)
                .put("style", config.style.name).put("palette", JSONArray(config.palette))
                .put("stepMs", config.stepMs).put("tail", config.tailLength)
                .put("brightness", config.brightness)
                .put("direction", config.direction.name).put("bounce", config.bounce))
        }
        val text = JSONObject().put("format", format).put("version", version)
            .put("flows", flows).put("chases", chases).toString(2)
        require(text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes)
        return text
    }

    fun decode(input: String): SceneArchiveData {
        require(input.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "Datei zu groß (maximal 96 KB)" }
        val root = JSONObject(input)
        require(root.optString("format") == format && root.optInt("version") == version) {
            "Unbekanntes Szenenformat oder Version"
        }
        val flows = root.optJSONArray("flows") ?: error("Szenenliste fehlt")
        val chases = root.optJSONArray("chases") ?: error("Lichtlaufliste fehlt")
        require(flows.length() + chases.length() <= 64) { "Maximal 64 Szenen pro Datei" }
        val decodedFlows = (0 until flows.length()).map { i ->
            val obj = flows.getJSONObject(i)
            val framesArray = obj.getJSONArray("frames")
            require(framesArray.length() in 2..48) { "Farbverlauf benötigt 2–48 Abschnitte" }
            val frames = (0 until framesArray.length()).map { index ->
                val frame = framesArray.getJSONObject(index)
                FlowFrame(
                    frame.getInt("ms"),
                    frame.getInt("rgb"),
                    frame.getInt("brightness")
                )
            }
            val name = checkedName(obj.getString("name"))
            val desc = obj.optString("description").trim().take(140)
            LoftScene(
                id = checkedId(obj.getString("id")),
                name = name,
                description = desc,
                colors = frames.take(4).map { 0xFF000000L or it.rgb.toLong() },
                frames = frames
            )
        }
        val decodedChases = (0 until chases.length()).map { i ->
            val obj = chases.getJSONObject(i)
            val colors = obj.getJSONArray("palette")
            require(colors.length() in 1..16) { "Farbpalette muss 1–16 Farben enthalten" }
            val palette = (0 until colors.length()).map { colors.getInt(it) }
            ChasePreset(
                id = checkedId(obj.getString("id")),
                name = checkedName(obj.getString("name")),
                config = ChaseConfig(
                    style = ChaseStyle.valueOf(obj.getString("style")),
                    palette = palette,
                    stepMs = obj.getInt("stepMs"),
                    tailLength = obj.getInt("tail"),
                    brightness = obj.getInt("brightness"),
                    direction = ChaseDirection.valueOf(obj.getString("direction")),
                    bounce = obj.getBoolean("bounce"),
                )
            )
        }
        return SceneArchiveData(decodedFlows, decodedChases)
    }

    private fun checkedId(raw: String): String =
        raw.trim().takeIf { it.length in 1..80 && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
            ?: error("Ungültige Szenen-ID")

    private fun checkedName(raw: String): String =
        raw.trim().takeIf { it.length in 1..64 && it.none { c -> c.isISOControl() } }
            ?: error("Szenenname fehlt oder ist zu lang")
}
