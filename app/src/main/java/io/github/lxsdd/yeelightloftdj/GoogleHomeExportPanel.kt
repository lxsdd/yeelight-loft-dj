package io.github.lxsdd.yeelightloftdj

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Export-only page; no background discovery or control commands. */
@Composable
fun GoogleHomeExportPanel(controller: LoftController) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("google-home-export", Context.MODE_PRIVATE) }
    val selection = remember { mutableStateMapOf<String, Boolean>() }
    val refs = remember { mutableStateMapOf<String, String>() }
    var mode by remember { mutableIntStateOf(0) }
    // Store only the author's chosen script wording locally. No Google account access.
    var title by remember {
        mutableStateOf(preferences.getString("scene.title", "Loft Abendlicht").orEmpty())
    }
    var phrase by remember {
        mutableStateOf(preferences.getString("scene.phrase", "Loft Abendlicht starten").orEmpty())
    }
    // One-shot, scene-specific RGB edits; never apply as a global bulb calibration.
    val rgbEdits = remember { mutableStateMapOf<String, String>() }
    var consent by remember { mutableStateOf(false) }
    var sceneId by remember { mutableStateOf(SceneLibrary.scenes.firstOrNull()?.id.orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HomeExport?>(null) }
    var notice by remember { mutableStateOf("") }
    var yamlToSave by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        val content = yamlToSave
        yamlToSave = null
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("Exportdatei kann nicht geöffnet werden")
            }.onSuccess { notice = "YAML-Datei gespeichert – in Google Home noch manuell prüfen und einfügen" }
                .onFailure { notice = "Dateiexport fehlgeschlagen: " + it.message.orEmpty() }
        }
    }

    val devices = controller.orderedDevices()
    val selected = devices.filter { selection[it.id] == true }
    val ids = selected.map { it.id }.toSet()
    val availableScenes = controller.allScenes()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Google Home · Szenen übertragen", style = MaterialTheme.typography.titleLarge)
        Text("Die Szene wird als eigenständiges Google-Home-Skript erstellt, nicht als Yeelight-Szenenaufruf. " +
            "Die Gerätekopplung zu Google Home bleibt erforderlich.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0; result = null },
                label = { Text("Momentaufnahme") })
            FilterChip(selected = mode == 1, onClick = { mode = 1; result = null },
                label = { Text("Verlaufsvorschau") })
        }
        OutlinedTextField(
            value = title, onValueChange = {
                title = it
                preferences.edit().putString("scene.title", it).apply()
                result = null
            },
            label = { Text("Szenenname") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phrase, onValueChange = {
                phrase = it
                preferences.edit().putString("scene.phrase", it).apply()
                result = null
            },
            label = { Text("Sprachbefehl zum Starten") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Nur die gesprochene Formulierung eintragen, ohne „Hey Google“. " +
                "Kurz und eindeutig wählen; der Skript-Validator testet keine Spracherkennung. " +
                "Schon anderweitig belegte Sprachbefehle können kollidieren.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Zielgeräte in Google Home zuordnen", style = MaterialTheme.typography.titleMedium)
        Text("Bitte exakt den Gerätenamen und Raum aus Google Home eintragen (Gerätename - Raum). " +
            "Lokale Yeelight-Namen sind nicht zwingend identisch.", style = MaterialTheme.typography.bodySmall)
        val selectable = devices.take(GoogleHomeSceneExport.MAX_LAMPS)
        val allSelected = selectable.isNotEmpty() && selectable.all { selection[it.id] == true }
        TextButton(onClick = {
            if (allSelected) devices.forEach { selection[it.id] = false }
            else {
                devices.forEach { selection[it.id] = false }
                selectable.forEach { selection[it.id] = true }
            }
            result = null
        }) { Text(if (allSelected) "Alle abwählen" else "Bis zu 16 Lampen auswählen") }
        devices.forEach { device ->
            val googleName = refs[device.id] ?: preferences.getString("ref." + device.id, "").orEmpty()
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = selection[device.id] == true,
                    enabled = selection[device.id] == true || selected.size < GoogleHomeSceneExport.MAX_LAMPS,
                    onCheckedChange = { selection[device.id] = it; result = null },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(controller.displayName(device), style = MaterialTheme.typography.labelLarge)
                    if (selection[device.id] == true) {
                        OutlinedTextField(
                            value = googleName,
                            onValueChange = { next ->
                                refs[device.id] = next
                                preferences.edit().putString("ref." + device.id, next).apply()
                                result = null
                            },
                            label = { Text("Google Home: Gerät - Raum") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // The bulb may render a requested Google Home RGB visually differently.
                        // Only explicitly chosen one-scene overrides are offered, never guessed.
                        val captured = controller.googleHomeCaptured[device.ip]
                        if (mode == 0 && controller.googleHomeCapturedIds == ids &&
                            captured?.isOn == true && captured.colorMode in setOf(1, 3)
                        ) {
                            val originalHex = captured.rgb?.let {
                                it.toString(16).uppercase(java.util.Locale.ROOT).padStart(6, '0')
                            }
                            Text(
                                if (originalHex != null) "Gelesener RGB-Zielwert: #$originalHex"
                                else "Gelesene HSV-Farbe – RGB-Export wird näherungsweise umgerechnet",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = rgbEdits[device.id].orEmpty(),
                                onValueChange = { next ->
                                    rgbEdits[device.id] = next
                                    result = null
                                },
                                label = { Text("RGB nur für diese Szene korrigieren (optional)") },
                                placeholder = { Text("#FF7700") },
                                supportingText = { Text("Sechs Hex-Zeichen; leer lässt die erfasste Farbe unverändert. " +
                                    "Der echte Farbton muss optisch an der Lampe geprüft werden.") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (mode == 0) {
            Text("Zuerst die Lichtstimmung in Yeelight aktivieren. Dann auf 'Jetzt erfassen' tippen. " +
                "Es erfolgt genau eine serielle Statusabfrage je ausgewählter Lampe – kein automatischer Scan.",
                style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = selected.isNotEmpty() && !controller.busy && !controller.recoveryPending,
                onClick = {
                    result = null
                    rgbEdits.clear() // never silently reuse a correction for a new capture
                    notice = "Erfassung angefordert; bei einer Fehlantwort wird die gesamte Aufnahme verworfen."
                    controller.captureGoogleHomeSnapshot(selected)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Jetzt ${selected.size} Lampen erfassen (nur lesen)") }
            Text(
                if (ids.isNotEmpty() && controller.googleHomeCapturedIds == ids)
                    "${ids.size} Lampen vollständig erfasst. Export kann erstellt werden."
                else "Für diese Auswahl liegt noch keine vollständige gemeinsame Aufnahme vor.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Nur endliche, langsame Vorschau bestehender Loft-DJ-Farbverläufe; " +
                "keine Yeelight-Classic-Animationen und kein Musik-/Lauflicht-Import.",
                style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(availableScenes.firstOrNull { it.id == sceneId }?.name ?: "Farbverlauf wählen")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    availableScenes.forEach { scene ->
                        DropdownMenuItem(text = { Text(scene.name) }, onClick = {
                            sceneId = scene.id
                            result = null
                            consent = false
                            menuOpen = false
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it; result = null })
                Text("Ich akzeptiere die vereinfachte, langsame Einmal-Vorschau.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selected.isNotEmpty() && !controller.busy &&
                (mode == 1 && consent || mode == 0 && controller.googleHomeCapturedIds == ids),
            onClick = {
                val mapped = selected.associate { device ->
                    device.ip to (refs[device.id]
                        ?: preferences.getString("ref." + device.id, "").orEmpty()).trim()
                }
                runCatching {
                    if (mode == 0) {
                        val captured = controller.googleHomeCaptured
                        val exact = selected.map { it.ip }.toSet()
                        require(controller.googleHomeCapturedIds == ids && captured.keys == exact) {
                            "Bitte die gewählten Lampen neu erfassen"
                        }
                        val lamps = GoogleHomeSnapshotAdapter.fromFreshSnapshots(mapped, captured)
                        val manualOverrides = selected.mapNotNull { device ->
                            rgbEdits[device.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { hex ->
                                mapped.getValue(device.ip) to hex
                            }
                        }.toMap()
                        val adjusted = GoogleHomeColorTuning.applyOverrides(lamps, manualOverrides)
                        val exported = GoogleHomeSceneExport.staticScene(title.trim(), phrase.trim(), adjusted)
                        if (manualOverrides.isEmpty()) exported else exported.copy(
                            warnings = exported.warnings + "Manuelle RGB-Zielwerte sind " +
                                "ausschließlich für diese Szene gesetzt. Die Farbwiedergabe der " +
                                "Yeelight-Hardware wurde damit nicht automatisch kalibriert."
                        )
                    } else {
                        val scene = availableScenes.firstOrNull { it.id == sceneId }
                            ?: error("Farbverlauf auswählen")
                        GoogleHomeFlowPreview.approximate(
                            scene, phrase.trim(), mapped.values.toList(), consent,
                            sceneName = title.trim(),
                        )
                    }
                }.onSuccess {
                    result = it
                    notice = "Skript vorbereitet. Bitte in Google Home prüfen, speichern und dort testen."
                }.onFailure {
                    result = null
                    notice = "Export nicht möglich: " + it.message.orEmpty()
                }
            },
        ) { Text("Google-Home-Skript erzeugen") }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        result?.let { export ->
            HorizontalDivider()
            Text("${export.actionCount} Aktionen · Vorschau", style = MaterialTheme.typography.titleMedium)
            export.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            SelectionContainer {
                Text(export.yaml.take(2400), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Google Home Automation", export.yaml))
                    notice = "Skript kopiert; bitte Google-Home-Gerätebefehle prüfen"
                }) { Text("Code kopieren") }
                OutlinedButton(onClick = {
                    yamlToSave = export.yaml
                    saver.launch("google-home-szene.yaml")
                }) { Text("YAML speichern") }
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://home.google.com/automations")))
                }.onFailure { notice = "Google Home konnte nicht geöffnet werden" }
            }) { Text("Google Home Script-Editor öffnen") }
        }
    }
}