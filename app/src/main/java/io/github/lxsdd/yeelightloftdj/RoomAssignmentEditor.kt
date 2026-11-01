package io.github.lxsdd.yeelightloftdj

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App-only logical rooms, not a claim of a completed interactive floorplan. */
@Composable
fun RoomAssignmentEditor(controller: LoftController, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var roomName by remember { mutableStateOf("") }
    var activeRoom by remember { mutableStateOf("Nicht zugeordnet") }
    var rename by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val editable = controller.canOrganizeLamps
    val devices = controller.orderedDevices()

    Surface(color = Color(0xFF1D2232), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = Color(0xFFC3ACFF))
                Spacer(Modifier.width(9.dp))
                Text("Räume & Lampenzuordnung", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Schließen" else "Bearbeiten")
                }
            }
            Text("Freie Raumgruppen für Beleuchtung und Szenen. Kein Grundriss – dieser folgt separat.",
                color = Color(0xFF9BAAC4), fontSize = 11.sp)
            if (expanded) {
                Text("Raum auswählen", fontWeight = FontWeight.Medium, color = Color.White)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    controller.roomNames.forEach { room ->
                        val count = devices.count { controller.roomFor(it) == room }
                        FilterChip(
                            selected = activeRoom == room,
                            onClick = {
                                activeRoom = room
                                rename = room
                                picked = emptySet()
                            },
                            label = { Text("$room · $count") }
                        )
                    }
                }
                OutlinedTextField(
                    value = roomName, onValueChange = { roomName = it.take(40) },
                    singleLine = true, label = { Text("Neuen Raum hinzufügen") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = editable && roomName.isNotBlank(),
                    onClick = {
                        if (controller.addRoom(roomName)) {
                            activeRoom = roomName.trim()
                            rename = activeRoom
                            roomName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Raum hinzufügen") }
                if (activeRoom in controller.roomNames && activeRoom != "Nicht zugeordnet") {
                    OutlinedTextField(
                        value = rename.ifEmpty { activeRoom },
                        onValueChange = { rename = it.take(40) },
                        label = { Text("Raum umbenennen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (controller.renameRoom(activeRoom, rename)) activeRoom = rename.trim()
                            },
                            enabled = editable && rename.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Umbenennen") }
                        TextButton(
                            onClick = { confirmDelete = true },
                            enabled = editable,
                            modifier = Modifier.weight(1f)
                        ) { Text("Entfernen") }
                    }
                }
                HorizontalDivider(color = Color(0xFF3D4358))
                Text("Lampen wählen und gesammelt zuordnen",
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { picked = devices.map { it.id }.toSet() }) {
                        Text("Alle auswählen")
                    }
                    TextButton(onClick = {
                        picked = devices.filter { controller.roomFor(it) == "Nicht zugeordnet" }
                            .map { it.id }.toSet()
                    }) { Text("Unzugeordnete") }
                    TextButton(onClick = { picked = emptySet() }) { Text("Keine") }
                }
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = device.id in picked,
                            onCheckedChange = { checked ->
                                picked = if (checked) picked + device.id else picked - device.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(controller.displayName(device), color = Color.White, fontSize = 13.sp)
                            Text(device.ip + "  ·  " + controller.roomFor(device),
                                color = Color(0xFF9BAAC4), fontSize = 10.sp)
                        }
                        IconButton(
                            enabled = controller.canIdentifyLamp(device),
                            onClick = { controller.identifyLamp(device) }
                        ) {
                            Icon(Icons.Rounded.Lightbulb,
                                contentDescription = "Lampe durch Lichtimpuls identifizieren",
                                tint = Color(0xFF80D9C6))
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable && picked.isNotEmpty() && activeRoom in controller.roomNames,
                    onClick = {
                        controller.assignMany(picked, activeRoom)
                        picked = emptySet()
                    }
                ) {
                    Text("${picked.size} Lampen → ${activeRoom}")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Raum entfernen?") },
            text = {
                Text("Die Lampen bleiben gespeichert und werden auf „Nicht zugeordnet“ gesetzt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.deleteRoom(activeRoom)) activeRoom = "Nicht zugeordnet"
                    confirmDelete = false
                }) { Text("Raum entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") }
            }
        )
    }
}
