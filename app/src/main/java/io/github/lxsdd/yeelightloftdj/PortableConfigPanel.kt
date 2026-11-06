package io.github.lxsdd.yeelightloftdj

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets

/**
 * Actual Android UI entry point: local-only full configuration export/import.
 * No network access, device control, background service or OAuth login.
 */
@Composable
fun PortableConfigPanel(controller: LoftController) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var stagedText by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PortableBackupPreview?>(null) }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PortableConfigArchive.mimeType)
    ) { uri ->
        val content = pendingExport
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(content.toByteArray(StandardCharsets.UTF_8))
                    stream.flush()
                } ?: error("Datei konnte nicht erstellt werden")
            }.onSuccess { controller.report("Konfiguration lokal exportiert") }
                .onFailure { controller.report("Export fehlgeschlagen: " + it.message) }
        }
        pendingExport = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val limit = PortableConfigArchive.maxBytes
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(limit + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val n = stream.read(buffer, total, buffer.size - total)
                        if (n < 0) break
                        total += n
                    }
                    require(total <= limit) { "Backup größer als 256 KiB" }
                    String(buffer, 0, total, StandardCharsets.UTF_8)
                } ?: error("Datei ist nicht lesbar")
                val plan = controller.previewPortableBackup(bytes)
                bytes to plan
            }.onSuccess { result ->
                stagedText = result.first
                preview = result.second
            }.onFailure { controller.report("Importprüfung fehlgeschlagen: " + it.message) }
        }
    }
    Surface(
        color = Color(0xFF1D2232), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Gesamte Konfiguration sichern", color = Color.White,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Räume, Gruppen, Lampenzuordnung, Reihenfolge, eigene Szenen und Einstellungen. " +
                    "Keine IP-Adressen, Kontozugänge oder laufenden Wiederherstellungsdaten. " +
                    "Diese unverschlüsselte Datei kann private Raum- und Lampennamen enthalten.",
                color = Color(0xFFB4BFCE)
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching { controller.exportPortableBackup() }
                        .onSuccess {
                            pendingExport = it
                            export.launch(PortableConfigArchive.fileName)
                        }.onFailure {
                            controller.report("Backup nicht möglich: " + it.message)
                        }
                }
            ) { Text("Vollständige lokale Sicherung exportieren") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = controller.canOrganizeLamps,
                onClick = {
                    import.launch(arrayOf(PortableConfigArchive.mimeType, "text/plain"))
                }
            ) { Text("Konfiguration importieren · zunächst Vorschau") }
            Text(
                "Der Import ändert ausschließlich gespeicherte App-Einstellungen. " +
                    "Nicht erkannte Lampen werden nicht per IP zugeordnet und nicht geschaltet. " +
                    "Die Yeelight-Cloud wird nicht angesprochen.",
                color = Color(0xFFB4BFCE)
            )
        }
    }
    val plan = preview
    val contents = stagedText
    if (plan != null && contents != null) {
        AlertDialog(
            onDismissRequest = { preview = null; stagedText = null },
            title = { Text("Konfiguration importieren?") },
            text = {
                Text(plan.description() +
                    "\nNur Ergänzungen. Bestehende Gruppen, Aliase und Zuordnungen bleiben erhalten. " +
                    "Lampen ohne bestätigte ID müssen später neu zugeordnet werden. " +
                    "Keine Lampen- oder Cloud-Befehle.")
            },
            confirmButton = {
                TextButton(
                    enabled = controller.canOrganizeLamps,
                    onClick = {
                        runCatching { controller.importPortableBackup(contents) }
                            .onSuccess { controller.report(it); preview = null; stagedText = null }
                            .onFailure { controller.report("Import fehlgeschlagen: " + it.message) }
                    }
                ) { Text("Lokal importieren") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null; stagedText = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}
