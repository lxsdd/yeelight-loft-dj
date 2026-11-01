package io.github.lxsdd.yeelightloftdj

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import java.nio.charset.StandardCharsets

private val night = Color(0xFF0D101A)
private val panel = Color(0xFF1D2232)
private val lavender = Color(0xFFC3ACFF)
private val muted = Color(0xFF9AA5BE)

class MainActivity : ComponentActivity() {
    private lateinit var controller: LoftController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = LoftController(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = lavender,
                secondary = Color(0xFF76D7D4),
                background = night,
                surface = panel,
                onBackground = Color.White,
            )) { LoftApp(controller) }
        }
    }
    override fun onStop() {
        controller.requestStopScanner()
        controller.stopMicrophone()
        super.onStop()
    }
    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@Composable
private fun LoftApp(controller: LoftController) {
    var page by remember { mutableIntStateOf(0) }
    var detailRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var parentRoute by rememberSaveable { mutableStateOf<String?>(null) }
    // Route-local editor controls must survive navigating to the target picker.
    val routeState = rememberSaveableStateHolder()
    val goBack: () -> Unit = {
        detailRoute = parentRoute
        parentRoute = null
    }
    BackHandler(enabled = detailRoute != null) { goBack() }
    var orderMode by remember { mutableStateOf(false) }
    var stagedOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    val scrollState = rememberLazyListState()
    LaunchedEffect(detailRoute, page) { scrollState.scrollToItem(0) }
    val feedback = LocalHapticFeedback.current
    val dragState = rememberReorderableLazyListState(scrollState) { from, to ->
        val source = (from.key as? String)?.takeIf { it.startsWith("sort:") }
            ?.removePrefix("sort:")
        val destination = (to.key as? String)?.takeIf { it.startsWith("sort:") }
            ?.removePrefix("sort:")
        if (controller.canOrganizeLamps && source != null && destination != null) {
            val target = stagedOrder.indexOf(destination)
            if (target >= 0) {
                stagedOrder = LampOrder.relocate(stagedOrder, source, target)
            }
        }
    }
    Scaffold(
        containerColor = night,
        bottomBar = {
            if (detailRoute == null) NavigationBar(containerColor = panel) {
                val pages = listOf("Home", "Szenen", "Geräte", "Musik")
                val icons = listOf(Icons.Rounded.Home, Icons.Rounded.AutoAwesome, Icons.Rounded.Devices, Icons.Rounded.GraphicEq)
                pages.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = page == index,
                        onClick = { page = index },
                        icon = { Icon(icons[index], contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }
            item { Status(controller) }
            if (controller.recoveryPending) {
                item(key = "recovery-action") {
                    if (controller.scannerRunning) ScannerRunningBanner(controller)
                    else RecoveryBanner(controller)
                }
            }
            if (detailRoute != null) {
                item(key = "detail-back") {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedButton(onClick = goBack) { Text("← Zurück") }
                        Text(
                            when (detailRoute) {
                                "chase" -> "Lichtlauf-Studio"
                                "scanner" -> "Knight-Rider-Scanner"
                                "transfer" -> "Szenenbibliothek"
                                "target" -> "Lampen auswählen"
                                "groups" -> "Gruppen verwalten"
                                "rooms" -> "Räume verwalten"
                                "network" -> "Geräte & Netzwerk prüfen"
                                else -> "Lampendetails"
                            },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        )
                    }
                }
                when (detailRoute) {
                    "chase", "scanner" -> {
                        item(key = "chase-target") {
                            CompactTargetCard(controller, onEdit = {
                                parentRoute = detailRoute
                                detailRoute = "target"
                            })
                        }
                        item(key = "chase-editor") {
                            routeState.SaveableStateProvider(
                                if (detailRoute == "scanner") "scanner-editor" else "chase-editor"
                            ) {
                                ChasePanel(controller,
                                    initialStyle = if (detailRoute == "scanner")
                                        ChaseStyle.KNIGHT_RIDER else ChaseStyle.RAINBOW_TAIL)
                            }
                        }
                        item(key = "chase-results") { SceneLampReport(controller) }
                    }
                    "target" -> item(key = "target-manager") {
                        routeState.SaveableStateProvider("target-manager") {
                            TargetSelector(controller,
                                onManageGroups = {
                                    parentRoute = detailRoute
                                    detailRoute = "groups"
                                })
                        }
                    }
                    "groups" -> item(key = "group-manager") {
                        routeState.SaveableStateProvider("group-manager") {
                            TargetSelector(controller, showGroupEditor = true)
                        }
                    }
                    "rooms" -> item(key = "room-manager") {
                        RoomAssignmentEditor(controller, initiallyExpanded = true)
                    }
                    "network" -> item(key = "network-diagnostics") {
                        DeviceTools(controller, initiallyExpanded = true)
                    }
                    "transfer" -> item(key = "scene-import-export") {
                        SceneExchangePanel(controller)
                    }
                    else -> {
                        val id = detailRoute?.removePrefix("device:")
                        val device = controller.devices.firstOrNull { it.id == id }
                        if (device != null) {
                            item(key = "device-details") {
                                routeState.SaveableStateProvider("device:" + device.id) {
                                    DeviceCard(device, controller, initiallyExpanded = true)
                                }
                            }
                        } else {
                            item { Note("Das Gerät ist nicht mehr vorhanden. Zurück zur Geräteliste wechseln.") }
                        }
                    }
                }
            } else when (page) {
                0 -> {
                    item { Hero(controller, onScene = { page = 1 }, onDevice = { page = 2 }) }
                    item { Heading("Deine Lichtwelten", "Ein Tipp – und dein Loft verwandelt sich.") }
                    item {
                        CompactTargetCard(controller, onEdit = { detailRoute = "target" })
                    }
                    item { TargetPowerControls(controller) }
                    items(SceneLibrary.scenes.take(2), key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item { TextButton(onClick = { page = 1 }) { Text("Alle Szenen entdecken →") } }
                }
                1 -> {
                    item { Heading("Lichtshows", "Farbverläufe direkt auf den Lampen") }
                    item {
                        CompactTargetCard(controller, onEdit = { detailRoute = "target" })
                    }
                    item { SceneRestoreOption(controller) }
                    items(controller.allScenes(), key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item(key = "scene-workshop") {
                        WorkshopNavigation(
                            onChase = { detailRoute = "chase" },
                            onScanner = { detailRoute = "scanner" },
                            onTransfer = { detailRoute = "transfer" },
                        )
                    }
                    item(key = "scene-lamp-report") { SceneLampReport(controller) }
                    if (!controller.recoveryPending) item { StopButton(controller) }
                    item { Note("Lichtläufe nutzen Yeelight-native Farbsequenzen. Tempo und Schweif wirken pro Lampe; der Start vieler Lampen ist nicht millisekundengenau synchron. Echte Musikreaktion findest du unter Musik.") }
                }
                2 -> {
                    item { Heading("Deine Geräte", "Direkte Steuerung über dein WLAN") }
                    item {
                        Surface(color = panel, shape = RoundedCornerShape(24.dp)) {
                            Column(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.DragHandle, contentDescription = null,
                                        tint = lavender)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Lichtweg gestalten", color = Color.White,
                                        fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                }
                                Text(
                                    if (orderMode)
                                        "Am Griff ziehen und die Lampen in die gewünschte räumliche Abfolge bringen. Zum Erkennen kurz auf das Lichtsymbol tippen."
                                    else "Deine Lampen auf einem Lichtweg – einfach ziehen statt Positionen eintippen.",
                                    color = muted, fontSize = 12.sp
                                )
                                Button(
                                    onClick = {
                                        if (orderMode) {
                                            orderMode = false
                                        } else {
                                            stagedOrder = controller.orderedDevices().map { it.id }
                                            orderMode = true
                                        }
                                    },
                                    enabled = controller.devices.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.DragHandle, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (orderMode) "Sortierung schließen" else "Lichtweg anordnen")
                                }
                                if (orderMode && controller.pendingRecoveryCount > 0) {
                                    Text("Nur Lampen mit offener Wiederherstellung können derzeit nicht " +
                                        "identifiziert werden. Lichtweg-Sortieren bleibt verfügbar.",
                                        color = muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (orderMode) {
                        val byId = controller.devices.associateBy { it.id }
                        items(
                            stagedOrder.mapNotNull { byId[it] },
                            key = { "sort:" + it.id }
                        ) { device ->
                            ReorderableItem(
                                dragState,
                                key = "sort:" + device.id,
                            ) { isDragging ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .animateItem()
                                        .semantics {
                                            customActions = listOf(
                                                CustomAccessibilityAction("Im Lichtweg nach oben") {
                                                    val at = stagedOrder.indexOf(device.id)
                                                    if (controller.canOrganizeLamps && at > 0) {
                                                        val changed = LampOrder.relocate(stagedOrder,
                                                            device.id, at - 1)
                                                        if (controller.saveDraggedOrder(changed)) {
                                                            stagedOrder = changed
                                                            true
                                                        } else false
                                                    } else false
                                                },
                                                CustomAccessibilityAction("Im Lichtweg nach unten") {
                                                    val at = stagedOrder.indexOf(device.id)
                                                    if (controller.canOrganizeLamps &&
                                                        at >= 0 && at < stagedOrder.lastIndex
                                                    ) {
                                                        val changed = LampOrder.relocate(stagedOrder,
                                                            device.id, at + 1)
                                                        if (controller.saveDraggedOrder(changed)) {
                                                            stagedOrder = changed
                                                            true
                                                        } else false
                                                    } else false
                                                }
                                            )
                                        },
                                    color = if (isDragging) Color(0xFF34314F) else panel,
                                    shape = RoundedCornerShape(22.dp),
                                    shadowElevation = if (isDragging) 12.dp else 0.dp,
                                    border = if (isDragging) BorderStroke(1.5.dp, lavender) else null,
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        IconButton(
                                            onClick = {},
                                            modifier = if (controller.canOrganizeLamps) {
                                                Modifier.draggableHandle(
                                                    onDragStarted = {
                                                        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragStopped = {
                                                        if (!controller.saveDraggedOrder(stagedOrder)) {
                                                            stagedOrder = controller.orderedDevices().map { it.id }
                                                        }
                                                    },
                                                )
                                            } else Modifier,
                                            enabled = controller.canOrganizeLamps,
                                        ) {
                                            Icon(Icons.Rounded.DragHandle,
                                                contentDescription = "Halten und zum Verschieben ziehen",
                                                tint = lavender)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(controller.displayName(device), color = Color.White,
                                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(device.ip + " · " +
                                                 (if (device.ip in controller.lastSeenIps)
                                                     "Erkannt" else "Nicht bestätigt"),
                                                 color = muted, fontSize = 10.sp)
                                        }
                                        TextButton(
                                             onClick = { controller.identifyLamp(device) },
                                             enabled = controller.canIdentifyLamp(device),
                                         ) { Text("Identifizieren", fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Surface(color = panel, shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Button(onClick = controller::discover, enabled = !controller.busy,
                                        modifier = Modifier.fillMaxWidth()) { Text("Lampen suchen") }
                                    OutlinedButton(onClick = { detailRoute = "rooms" },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Räume & Gruppen verwalten")
                                    }
                                    OutlinedButton(onClick = { detailRoute = "network" },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Netzwerkdiagnose & Geräte hinzufügen")
                                    }
                                }
                            }
                        }
                        items(controller.orderedDevices(), key = { it.id }) { device ->
                            DeviceSummaryCard(
                                device, controller,
                                onDetails = { detailRoute = "device:" + device.id }
                            )
                        }
                    }
                    if (controller.devices.isEmpty()) {
                        item { Note("Noch keine Geräte gefunden. Suche im WLAN oder füge eine lokale IP hinzu. LAN-Steuerung muss in Yeelight aktiviert sein.") }
                    }
                }
                else -> {
                    item {
                        CompactTargetCard(controller, onEdit = { detailRoute = "target" })
                    }
                    item { MusicScreen(controller) }
                }
            }
            item { Footer() }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8061E3), Color(0xFF2DC8BE)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("YEELIGHT", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text("LOFT DJ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.weight(1f))
        Text("v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE,
            color = lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScannerRunningBanner(controller: LoftController) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF242D39)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Knight-Rider-Scanner läuft", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Aktuell: Lampe " +
                (controller.scannerActiveIndex?.plus(1)?.toString() ?: "vorbereiten") +
                " · " + controller.scannerSteps + " Schritte",
                color = Color(0xFF80D9C6), fontSize = 13.sp)
            Text("Andere ausgewählte Lampen werden nur nach bestätigtem " +
                "Ausschalten der vorherigen Lampe eingeschaltet. Beim Stopp " +
                "werden alle Teilnehmer ausgeschaltet und geprüft.",
                color = muted, fontSize = 12.sp)
            Button(onClick = controller::requestStopScanner,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Scanner stoppen · Licht AUS")
            }
        }
    }
}

@Composable
private fun RecoveryBanner(controller: LoftController) {
    var releaseIp by rememberSaveable { mutableStateOf<String?>(null) }
    var acceptsRunningFlow by rememberSaveable { mutableStateOf(false) }
    var acceptsLoss by rememberSaveable { mutableStateOf(false) }
    var recoveryDetails by rememberSaveable { mutableStateOf(false) }
    val closeDialog = {
        releaseIp = null
        acceptsRunningFlow = false
        acceptsLoss = false
    }
    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF2C293B)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(controller.pendingRecoveryCount.toString() +
                " Lampe(n) zur Prüfung vorgemerkt",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Andere Lampen bleiben bedienbar.", color = muted, fontSize = 12.sp)
            controller.pendingRecoveryIps().forEach { ip ->
                val lamp = controller.devices.firstOrNull { it.ip == ip }
                val lampName = lamp?.let(controller::displayName)
                    ?: "Gespeicherte Lampe ." + ip.substringAfterLast('.')
                Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF383345)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lightbulb, null,
                                tint = Color(0xFFFFC1A6), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(lampName, color = Color.White,
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        }
                        controller.recoveryReadReports[ip]?.let {
                            Text(it, color = Color(0xFFFFC1A6), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { controller.checkRecoveryLamp(ip) },
                            enabled = controller.canRetryRecovery && !controller.scannerRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Search, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Zustand jetzt prüfen")
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = controller::stop,
                enabled = controller.scannerRunning || controller.canRetryRecovery,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (controller.scannerRunning)
                    "Scanner stoppen & Lampen ausschalten"
                else "Lichtshow stoppen & Licht wiederherstellen")
            }
            TextButton(onClick = { recoveryDetails = !recoveryDetails }) {
                Text(if (recoveryDetails) "Details ausblenden" else "Fehlerdetails & weitere Optionen")
            }
            if (recoveryDetails) {
                Text(controller.recoveryExplanation, color = muted, fontSize = 12.sp)
                controller.pendingLampDescriptions().forEach { desc ->
                    Text(desc, color = Color(0xFFFFC1A6), fontSize = 12.sp)
                }
                if (controller.quotaRecoveryHint) {
                    Text("Gerät meldet Yeelight-Clientlimit. " +
                        "Andere Steuerungen beenden und später erneut prüfen.",
                        color = Color(0xFFFFC1A6), fontSize = 12.sp)
                }
                HorizontalDivider(color = Color(0xFF49475F))
                Text("Nur als Ausnahme: alten App-Eintrag verwerfen.",
                    color = muted, fontSize = 12.sp)
                controller.pendingRecoveryIps().forEach { ip ->
                    val device = controller.devices.firstOrNull { it.ip == ip }
                    val bulbName = device?.let(controller::displayName)
                        ?: "Unbekannte gespeicherte Lampe"
                    OutlinedButton(
                        onClick = {
                            releaseIp = ip
                            acceptsRunningFlow = false
                            acceptsLoss = false
                        },
                        enabled = controller.canRetryRecovery,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(bulbName + " (." + ip.substringAfterLast('.') +
                            ") · alten App-Eintrag verwerfen")
                    }
                }
            }
        }
    }
    releaseIp?.let { ip ->
        val targetName = controller.devices.firstOrNull { it.ip == ip }
            ?.let(controller::displayName) ?: "Gespeicherte Lampe"
        AlertDialog(
            onDismissRequest = closeDialog,
            title = { Text("Alte Wiederherstellung wirklich verwerfen?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(targetName + " · " + ip +
                        ". Dies entfernt ausschließlich den lokalen " +
                        "Lichtshow-/Wiederherstellungseintrag dieser Lampe. " +
                        "Es sendet KEINEN Stoppbefehl. Ein eventuell laufender " +
                        "Lichtlauf wird dadurch nicht beendet. Der frühere Lichtzustand " +
                        "kann anschließend nicht mehr automatisch wiederhergestellt werden.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acceptsRunningFlow,
                            onCheckedChange = { acceptsRunningFlow = it })
                        Text("Ich weiß, dass diese Lampe eventuell noch einen " +
                            "Lichtlauf ausführt und das Verwerfen ihn NICHT stoppt.",
                            fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acceptsLoss,
                            onCheckedChange = { acceptsLoss = it })
                        Text("Ich akzeptiere, dass die frühere Farbe/Helligkeit " +
                            "nicht automatisch wiederhergestellt wird.",
                            fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.discardRecoveryForLamp(
                            ip, acceptsRunningFlow, acceptsLoss
                        )
                        closeDialog()
                    },
                    enabled = acceptsRunningFlow && acceptsLoss &&
                        controller.canRetryRecovery
                ) { Text("App-Eintrag für diese Lampe verwerfen") }
            },
            dismissButton = {
                TextButton(onClick = closeDialog) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun Status(controller: LoftController) {
    Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFF252A3A)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (controller.busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Lightbulb, null, tint = Color(0xFF7EDBC7), modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(controller.status, color = Color.White, fontSize = 13.sp, maxLines = 3)
        }
    }
}

@Composable
private fun Hero(controller: LoftController, onScene: () -> Unit, onDevice: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF312560), Color(0xFF174F69), Color(0xFF1B5649))))
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("DEIN LOFT. DEIN LICHT.", color = Color(0xFFC3E4F3), fontSize = 11.sp, letterSpacing = 1.6.sp)
            Text("Mach Licht zu\neinem Erlebnis.", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp, lineHeight = 34.sp)
            Text("Wohnzimmer · Esszimmer · Hobbyraum", color = Color(0xFFE1E7F2), fontSize = 13.sp)
            Text(controller.devices.size.toString() + " Geräte erkannt", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScene) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Szenen")
                }
                OutlinedButton(onClick = onDevice) { Text("Geräte") }
            }
        }
    }
}

@Composable
private fun Heading(name: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(name, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 13.sp, color = muted)
    }
}

@Composable
private fun SceneCard(scene: LoftScene, enabled: Boolean, active: Boolean, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF302D47) else panel),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(122.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(104.dp).fillMaxHeight()
                    .background(Brush.linearGradient(scene.colors.map { Color(it) })),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(35.dp)) }
            Column(Modifier.weight(1f).padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scene.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(scene.description, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (active) "AKTIV" else "STARTEN  ▶", color = lavender, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TargetSelector(
    controller: LoftController,
    onManageGroups: (() -> Unit)? = null,
    showGroupEditor: Boolean = false,
) {
    var targetMenu by remember { mutableStateOf(false) }
    var newGroupName by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lampen auswählen", color = Color.White, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { targetMenu = true },
                modifier = Modifier.fillMaxWidth()) {
                Text(controller.targetLabel(controller.target) +
                    " · " + controller.selectedCount() + " Lampen",
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("⌄")
            }
            DropdownMenu(expanded = targetMenu,
                onDismissRequest = { targetMenu = false }) {
                controller.targetOptions().forEach { value ->
                    DropdownMenuItem(
                        text = { Text(controller.targetLabel(value)) },
                        onClick = {
                            controller.chooseTarget(value)
                            targetMenu = false
                        },
                        leadingIcon = if (controller.target == value)
                            ({ Icon(Icons.Rounded.Lightbulb, null) }) else null
                    )
                }
            }
        }
        if (controller.target == "Auswahl") {
            Text("Eigene Mehrfachauswahl", color = muted, fontSize = 12.sp)
            controller.orderedDevices().forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = device.id in controller.customSelectedIds,
                        onCheckedChange = { controller.setCustomSelection(device.id, it) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(controller.displayName(device), color = Color.White, fontSize = 13.sp)
                        Text(device.ip, color = muted, fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = { controller.identifyLamp(device) },
                        enabled = controller.canIdentifyLamp(device)
                    ) { Text("Identifizieren", fontSize = 11.sp) }
                }
            }
        }
        if (controller.targetDescription(controller.target).isNotEmpty()) {
            Text(controller.targetDescription(controller.target),
                color = muted, fontSize = 12.sp)
        }
        HorizontalDivider(color = Color(0xFF374052))
        if (!showGroupEditor) {
            OutlinedButton(
                onClick = { onManageGroups?.invoke() },
                enabled = onManageGroups != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Devices, null)
                Spacer(Modifier.width(8.dp))
                Text("Gruppen verwalten")
            }
        }
        if (showGroupEditor) {
        Text("Eigene Gruppen", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = newGroupName,
                onValueChange = { newGroupName = it.take(40) },
                modifier = Modifier.weight(1f),
                label = { Text("Neuer Gruppenname") }, singleLine = true)
            Button(
                onClick = {
                    controller.addGroup(newGroupName)
                    newGroupName = ""
                },
                enabled = controller.canOrganizeLamps &&
                    NamedLampGroups.validName(newGroupName.trim())
            ) { Text("Anlegen") }
        }
        if (controller.target.startsWith("Gruppe:")) {
            val selected = controller.target.removePrefix("Gruppe:")
            var renamed by rememberSaveable(selected) { mutableStateOf(selected) }
            Text("Gruppenmitglieder · " + selected, color = Color.White,
                fontWeight = FontWeight.SemiBold)
            controller.orderedDevices().forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = device.id in controller.namedGroups[selected].orEmpty(),
                        onCheckedChange = { controller.setGroupMember(selected, device.id, it) },
                        enabled = controller.canOrganizeLamps
                    )
                    Column(Modifier.weight(1f)) {
                        Text(controller.displayName(device), color = Color.White, fontSize = 13.sp)
                        Text(controller.roomFor(device), color = muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { controller.identifyLamp(device) },
                        enabled = controller.canIdentifyLamp(device)) {
                        Text("Erkennen", fontSize = 11.sp)
                    }
                }
            }
            OutlinedTextField(value = renamed,
                onValueChange = { renamed = it.take(40) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Gruppe umbenennen") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { controller.renameGroup(selected, renamed) },
                    enabled = controller.canOrganizeLamps &&
                        NamedLampGroups.validName(renamed.trim()) &&
                        renamed.trim() != selected,
                    modifier = Modifier.weight(1f)
                ) { Text("Speichern") }
                TextButton(
                    onClick = { controller.deleteGroup(selected) },
                    enabled = controller.canOrganizeLamps,
                    modifier = Modifier.weight(1f)
                ) { Text("Gruppe löschen") }
            }
        }
        }
    }
}

@Composable
private fun StopButton(controller: LoftController) {
    OutlinedButton(
        enabled = !controller.busy && (controller.runningDeviceIps.isNotEmpty() || controller.selectedCount() > 0),
        onClick = controller::stop,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Rounded.Stop, null)
        Spacer(Modifier.width(7.dp))
        Text("Alle laufenden Lichtshows stoppen")
    }
}

@Composable
private fun SceneLampReport(controller: LoftController) {
    val results = controller.lastSceneReport
    if (results.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Lampenbericht · " + results.count { it.outcome == "Läuft" } +
                "/" + results.size + " gestartet", color = Color.White,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Bericht einklappen" else "Fehler und Lampen anzeigen")
            }
            if (expanded) results.forEach { item ->
                Column {
                    Text(item.name + " · " + item.outcome,
                        color = if (item.outcome == "Läuft") Color(0xFF80D9C6)
                            else Color(0xFFFFC1A6), fontSize = 13.sp)
                    Text(item.message, color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun WorkshopNavigation(
    onChase: () -> Unit, onScanner: () -> Unit, onTransfer: () -> Unit
) {
    Surface(color = panel, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Szenenwerkstatt", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Öffne ein übersichtliches Studio statt eines langen Editors mitten in der Liste.",
                color = muted, fontSize = 12.sp)
            Button(onClick = onScanner, modifier = Modifier.fillMaxWidth()) {
                Text("Knight-Rider-Scanner öffnen")
            }
            OutlinedButton(onClick = onChase, modifier = Modifier.fillMaxWidth()) {
                Text("Weitere Lichtläufe bearbeiten")
            }
            OutlinedButton(onClick = onTransfer, modifier = Modifier.fillMaxWidth()) {
                Text("Szenen importieren & exportieren")
            }
        }
    }
}

@Composable
private fun CompactTargetCard(controller: LoftController, onEdit: () -> Unit) {
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Aktuelle Lampenauswahl", color = muted, fontSize = 12.sp)
                Text(controller.targetLabel(controller.target) +
                    " · " + controller.selectedCount() + " Lampen",
                    color = Color.White, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onEdit) { Text("Ändern") }
        }
    }
}

@Composable
private fun DeviceSummaryCard(
    device: YeelightDevice,
    controller: LoftController,
    onDetails: () -> Unit
) {
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, tint = lavender)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(controller.displayName(device), color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                    Text(controller.roomFor(device) + " · " +
                        DeviceCapabilities.summary(device),
                        color = muted, fontSize = 11.sp)
                }
                TextButton(onClick = onDetails) { Text("Steuern →") }
            }
            TextButton(
                onClick = { controller.identifyLamp(device) },
                enabled = controller.canIdentifyLamp(device)
            ) { Text("Lampe identifizieren") }
        }
    }
}

@Composable
private fun DeviceTools(controller: LoftController, initiallyExpanded: Boolean = false) {
    var ip by remember { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = controller::discover, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Search, null)
            Spacer(Modifier.width(7.dp))
            Text("Alle Lampen suchen")
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Netzwerkdetails schließen" else "Erweiterte Suche und Netzwerkdiagnose")
        }
        if (expanded) {
        OutlinedButton(onClick = controller::deepDiscover, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Intensivsuche im WLAN")
        }
        Text("Die Hauptsuche kombiniert Geräteankündigungen und direkte LAN-Prüfung. Die Intensivsuche ist als gezielter Wiederholungstest verfügbar.", color = muted, fontSize = 12.sp)
        Surface(color = Color(0xFF252A3D), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Netzwerk-Diagnose", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(controller.searchDiagnostics, color = Color(0xFFB6C4E7), fontSize = 12.sp)
                Text("Yeelight nutzt SSDP-ähnliches UDP-Multicast (nicht mDNS). Antworten kommen einzeln per Unicast; Direkt-LAN prüft unabhängig davon TCP 55443. Je Suchrunde werden unterschiedliche Lampen gezählt.", color = muted, fontSize = 11.sp)
            }
        }
        OutlinedButton(
            onClick = controller::extendedMulticastDiagnostic,
            enabled = !controller.busy && controller.devices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("12-Runden-Multicast-Gegenprobe (nur lesend)") }
        Text("Multicast und direktes UDP sind verschiedene Suchwege. " +
            "Ein TCP-only-Treffer ist kein Hinweis auf weniger unterstützte Funktionen.",
            color = muted, fontSize = 12.sp)
        controller.orderedDevices().forEach { device ->
            val multicast = device.ip in controller.lastMulticastIps
            val directed = device.ip in controller.lastDirectedUdpIps
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(controller.displayName(device), color = Color.White,
                        fontWeight = FontWeight.Medium, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    Text(
                        when {
                            multicast -> "Multicast"
                            directed -> "Direkt-UDP"
                            else -> "Ohne UDP-Treffer"
                        },
                        color = if (multicast || directed) Color(0xFF80D9C6)
                            else Color(0xFFFFC1A6),
                        fontSize = 11.sp
                    )
                }
                Text(controller.discoveryDetail(device), color = muted, fontSize = 11.sp)
            }
        }
        OutlinedButton(
            onClick = controller::compareDirectedUdp,
            enabled = !controller.busy && controller.missingMulticastCount() > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("UDP-Gegenprobe für " + controller.missingMulticastCount() + " Nichtantworter") }
        if (controller.directedUdpDiagnostics.isNotBlank()) {
            Text(controller.directedUdpDiagnostics, color = Color(0xFFB6C4E7), fontSize = 12.sp)
        }
        Text("Die direkte UDP-Suche ist ein begrenzter, lesender Fallback " +
            "und kann Modell und unterstützte Befehle liefern. Ein Direkt-UDP-Treffer " +
            "wird nicht fälschlich als Multicast-Erfolg gezählt. " +
            "Ausbleibende Multicast-Antworten bleiben separat sichtbar.",
            color = muted, fontSize = 11.sp)
        Text("Gespeicherte Geräte bleiben bei erneuter Suche erhalten. Nicht bestätigte Geräte können inzwischen offline sein.", color = muted, fontSize = 12.sp)
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Lokale IP, falls Suche nicht funktioniert") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { controller.addManual(ip); ip = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("Gerät manuell hinzufügen")
        }
        OutlinedButton(
            enabled = !controller.busy && YeelightLan.isLocalIp(ip.trim()),
            onClick = { controller.diagnoseDeskLamp(ip.trim()) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Mi Desk Lamp 1S an dieser IP prüfen") }
        Text(
            "Diagnose nur lesend: Yeelight-TCP 55443 und Xiaomi-MiIO-UDP 54321. " +
                "Ein MiIO-Treffer bedeutet nicht automatisch Yeelight-LAN-Kompatibilität; " +
                "ein separates autorisiertes Protokoll kann erforderlich sein.",
            color = muted, fontSize = 11.sp
        )
        }
    }
}

@Composable
private fun DeviceCard(device: YeelightDevice, controller: LoftController,
    initiallyExpanded: Boolean = false) {
    var expanded by remember(device.id) { mutableStateOf(initiallyExpanded) }
    var localAlias by remember(device.id) { mutableStateOf(controller.aliasFor(device)) }
    var showRemoveConfirm by remember(device.id) { mutableStateOf(false) }
    val discovered = device.ip in controller.lastSeenIps
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Lightbulb,
                    contentDescription = null, tint = if (discovered) Color(0xFF80D9C6) else lavender)
                Column(Modifier.weight(1f)) {
                    Text(controller.displayName(device), color = Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(device.ip, color = muted, fontSize = 10.sp)
                    Text(controller.roomFor(device) + " · " +
                        (if (discovered) "Erkannt" else "Gespeichert"),
                        color = muted, fontSize = 11.sp)
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    enabled = !controller.busy
                ) { Text(if (expanded) "Weniger" else "Details", fontSize = 12.sp) }
            }
            OutlinedButton(
                onClick = { controller.identifyLamp(device) },
                enabled = controller.canIdentifyLamp(device),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Identifizieren · 2,5 s") }
            if (expanded) {
                HorizontalDivider(color = Color(0xFF384057))
                Text("Geräteverwaltung", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Modell: " + (if (YeelightLan.isSpecificModel(device.model))
                    device.model else "über LAN unbekannt") +
                    " · Port " + device.port, color = muted, fontSize = 11.sp)
                Text("Erkannt durch: " + when {
                    device.ip in controller.lastMulticastIps && device.ip in controller.lastDirectIps ->
                        "Multicast + TCP"
                    device.ip in controller.lastDirectIps -> "TCP-Direktsuche"
                    device.ip in controller.lastMulticastIps -> "Multicast"
                    else -> "Zuletzt nicht überprüft"
                }, color = muted, fontSize = 11.sp)
                OutlinedTextField(
                    value = localAlias,
                    onValueChange = { localAlias = it.take(50) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Individueller Name") }
                )
                OutlinedButton(
                    enabled = !controller.busy,
                    onClick = { controller.setAlias(device, localAlias) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Namen speichern") }
                Text("Raumzuordnung", color = Color.White, fontWeight = FontWeight.Medium)
                var roomMenu by remember(device.id) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { roomMenu = true },
                        enabled = controller.canOrganizeLamps,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(controller.roomFor(device), modifier = Modifier.weight(1f))
                        Text("⌄")
                    }
                    DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                        controller.roomNames.forEach { room ->
                            DropdownMenuItem(text = { Text(room) }, onClick = {
                                controller.assign(device, room)
                                roomMenu = false
                            })
                        }
                    }
                }
                DeviceCapabilityPanel(device)
                OutlinedButton(
                    onClick = { controller.inspectLamp(device) },
                    enabled = !controller.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Gerät prüfen · nur lesend") }
                LampManualPanel(device, controller, initiallyExpanded = initiallyExpanded)
                if (!device.canFlow) {
                    Note("Der erkannte SUPPORT-Eintrag enthält kein start_cf. " +
                        "Diese Lampe wird nicht in Farbfluss-Szenen aufgenommen.")
                } else if (device.methods.isEmpty()) {
                    Note("Farbfluss noch nicht verifiziert: Die reine TCP-Suche liefert " +
                        "keine vollständige Geräteliste mit unterstützten Methoden.")
                }
                TextButton(
                    onClick = { showRemoveConfirm = true },
                    enabled = controller.canOrganizeLamps,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Gerät aus dieser App entfernen") }
            }
        }
    }
    if (showRemoveConfirm) AlertDialog(
        onDismissRequest = { showRemoveConfirm = false },
        title = { Text("Gerät entfernen?") },
        text = { Text(controller.displayName(device) + " bleibt in Yeelight/Google Home erhalten.") },
        confirmButton = {
            TextButton(onClick = {
                controller.forget(device)
                showRemoveConfirm = false
            }) { Text("Entfernen") }
        },
        dismissButton = {
            TextButton(onClick = { showRemoveConfirm = false }) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun TargetPowerControls(controller: LoftController) {
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Schnellsteuerung", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Für ${controller.target} · ${controller.selectedCount()} Lampen. Farbverläufe werden durch manuelles Schalten beendet.", color = muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = { controller.powerTarget(true) },
                    enabled = !controller.busy && controller.selectedCount() > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Einschalten") }
                OutlinedButton(
                    onClick = { controller.powerTarget(false) },
                    enabled = !controller.busy && controller.selectedCount() > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Ausschalten") }
            }
            if (controller.lastPowerResults.isNotEmpty()) {
                HorizontalDivider(color = Color(0xFF394156))
                Text("Letzte Schaltmessung · " +
                    if (controller.lastPowerDesiredOn == true) "EIN" else "AUS",
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(controller.lastPowerSummary, color = muted, fontSize = 12.sp)
                controller.orderedDevices().filter {
                    it.ip in controller.lastPowerResults
                }.forEach { device ->
                    PowerTrace(
                        controller.displayName(device),
                        controller.lastPowerResults.getValue(device.ip),
                        discoverySource(controller, device)
                    )
                }
                Text("Zeiten ab Betätigung der einzelnen Lampe, nicht ab dem " +
                    "ersten Gruppenbefehl. Kein automatischer Retry bei unbekanntem Status.",
                    color = muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun discoverySource(controller: LoftController, device: YeelightDevice): String =
    when {
        device.ip in controller.lastMulticastIps && device.ip in controller.lastDirectIps ->
            "UDP + TCP erkannt"
        device.ip in controller.lastMulticastIps -> "UDP erkannt"
        device.ip in controller.lastDirectIps -> "nur TCP erkannt"
        else -> "letzte Erkennung nicht bestätigt"
    }

@Composable
private fun PowerTrace(name: String, probe: PowerProbe, discovery: String) {
    val outcome = when (probe.outcome) {
        PowerOutcome.VERIFIED -> "Schaltzustand bestätigt"
        PowerOutcome.ACK_ONLY -> "Befehl angenommen, Zustand unbekannt"
        PowerOutcome.MISMATCH -> "Zustand weicht vom Ziel ab"
        PowerOutcome.REJECTED -> "Befehl abgelehnt"
        PowerOutcome.NO_ACK -> "Geräteantwort fehlt"
        PowerOutcome.UNREACHABLE -> "TCP nicht erreichbar"
        PowerOutcome.PROTECTED -> "Durch Wiederherstellung geschützt"
        PowerOutcome.UNSUPPORTED -> "Gerät unterstützt Ein/Aus nicht"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name + " · " + discovery + " · " + outcome,
            color = if (probe.outcome == PowerOutcome.VERIFIED)
                Color(0xFF80D9C6) else Color(0xFFFFC1A6),
            fontSize = 12.sp)
        if (probe.totalMs > 0) {
            Text("TCP: " + (probe.connectMs?.let { "$it ms" } ?: "keine Verbindung") +
                " · OK: " + (probe.ackMs?.let { "$it ms" } ?: "keine Bestätigung") +
                " · Status: " + (probe.statusMs?.let { "$it ms" } ?: "nicht verifiziert") +
                " · Gesamt: " + probe.totalMs + " ms",
                color = muted, fontSize = 11.sp)
        }
        if (probe.message.isNotBlank()) {
            Text(probe.message.take(140), color = muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DeviceCapabilityPanel(device: YeelightDevice) {
    Surface(color = Color(0xFF252B3D), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Vom Gerät gemeldete Funktionen", color = Color.White,
                fontWeight = FontWeight.SemiBold)
            if (device.methods.isEmpty()) {
                Text("Nur TCP-Erkennung: Es liegt keine SUPPORT-Liste vor. " +
                    "RGB, Weißtemperatur und Lichtlauf sind damit nicht bestätigt. " +
                    "Eine kombinierte Netzwerksuche kann zusätzliche Daten liefern.",
                    color = muted, fontSize = 12.sp)
            } else {
                DeviceCapabilities.capabilities(device).forEach { capability ->
                    Text(
                        capability.label + ": " +
                            when (capability.evidence) {
                                CapabilityEvidence.CONFIRMED -> "unterstützt"
                                CapabilityEvidence.UNVERIFIED -> "nicht bestätigt"
                                CapabilityEvidence.UNSUPPORTED -> "nicht gemeldet"
                            },
                        color = if (capability.evidence == CapabilityEvidence.CONFIRMED)
                            Color(0xFF80D9C6) else muted,
                        fontSize = 12.sp
                    )
                }
                val notAvailable = YeelightCommandCatalog.reportedButNotImplemented(device.methods)
                if (notAvailable.isNotEmpty()) {
                    var showFuture by remember(device.id) { mutableStateOf(false) }
                    TextButton(onClick = { showFuture = !showFuture }) {
                        Text(notAvailable.size.toString() + " weitere Gerätebefehle anzeigen " +
                            if (showFuture) "−" else "+")
                    }
                    if (showFuture) {
                        notAvailable.forEach { method ->
                            Text(method.title + " · Gerät meldet " + method.id +
                                ", in App noch nicht implementiert",
                                color = muted, fontSize = 11.sp)
                        }
                    }
                }
                val future = YeelightCommandCatalog.unknownReported(device.methods)
                if (future.isNotEmpty()) {
                    Text("Firmware meldet zusätzliche, nicht klassifizierte Befehle: " +
                        future.joinToString(", ").take(150),
                        color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LampManualPanel(device: YeelightDevice, controller: LoftController,
    initiallyExpanded: Boolean = false) {
    var expanded by remember(device.ip) { mutableStateOf(initiallyExpanded) }
    val live = controller.snapshotFor(device)
    var allowUnverified by rememberSaveable(device.ip) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (expanded) "Lichtsteuerung schließen" else "Lichtsteuerung öffnen") }
    if (!expanded) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (device.methods.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allowUnverified,
                    onCheckedChange = { allowUnverified = it },
                    enabled = controller.canIdentifyLamp(device)
                )
                Text("Unbestätigte Funktionen für diese Lampe testen",
                    color = muted, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    live == null -> "Status noch nicht abgerufen"
                    live.isOn -> "Eingeschaltet · ${live.brightness} %"
                    else -> "Ausgeschaltet"
                },
                color = if (live?.isOn == true) Color(0xFF80D9C6) else muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { controller.refreshLamp(device) }, enabled = !controller.busy) {
                Text("Aktualisieren")
            }
        }
        if (!controller.canIdentifyLamp(device)) {
            Text("Nur diese Lampe ist wegen Lichtshow/Wiederherstellung derzeit geschützt.",
                color = muted, fontSize = 11.sp)
        }
        controller.lastPowerResults[device.ip]?.let { probe ->
            Text("Letzte Ein/Aus-Messung", color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            PowerTrace(controller.displayName(device), probe,
                discoverySource(controller, device))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { controller.controlLamp(device, LampCommands.power(true), "Einschalten") },
                enabled = controller.canIdentifyLamp(device) &&
                    DeviceCapabilities.evidence(device, "set_power") != CapabilityEvidence.UNSUPPORTED,
                modifier = Modifier.weight(1f)
            ) { Text("Ein") }
            OutlinedButton(
                onClick = { controller.controlLamp(device, LampCommands.power(false), "Ausschalten") },
                enabled = controller.canIdentifyLamp(device) &&
                    DeviceCapabilities.evidence(device, "set_power") != CapabilityEvidence.UNSUPPORTED,
                modifier = Modifier.weight(1f)
            ) { Text("Aus") }
        }
        var brightness by remember(device.ip, live?.brightness) {
            mutableFloatStateOf((live?.brightness ?: 60).toFloat())
        }
        Text("Helligkeit · ${brightness.roundToInt()} %", color = Color.White, fontSize = 12.sp)
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = {
                controller.controlLamp(device, LampCommands.brightness(brightness.roundToInt()), "Helligkeit")
            },
            valueRange = 1f..100f,
            enabled = controller.canIdentifyLamp(device) &&
                DeviceCapabilities.canOffer(device, "set_bright", allowUnverified),
        )
        val colors = listOf(
            Triple("Warm", 0xFFAE62, Color(0xFFFFAE62)),
            Triple("Türkis", 0x23D7D0, Color(0xFF23D7D0)),
            Triple("Violett", 0xA47BFF, Color(0xFFA47BFF)),
            Triple("Pink", 0xFF55B4, Color(0xFFFF55B4)),
        )
        if (DeviceCapabilities.evidence(device, "set_rgb") != CapabilityEvidence.UNSUPPORTED) {
            Text("Direktfarben", color = Color.White, fontSize = 12.sp)
            colors.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { (name, rgb, tint) ->
                        FilterChip(
                            selected = live?.colorMode == 1 && live.rgb == rgb,
                            onClick = { controller.controlLamp(device, LampCommands.rgb(rgb), name) },
                            enabled = controller.canIdentifyLamp(device) &&
                                DeviceCapabilities.canOffer(device, "set_rgb", allowUnverified),
                            modifier = Modifier.weight(1f),
                            label = { Text(name) },
                            leadingIcon = {
                                Box(Modifier.size(11.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                            },
                        )
                    }
                }
            }
        }
        if (DeviceCapabilities.evidence(device, "set_ct_abx") != CapabilityEvidence.UNSUPPORTED) {
            var kelvin by remember(device.ip, live?.temperature) {
                mutableFloatStateOf((live?.temperature ?: 3500).toFloat())
            }
            Text("Weißtemperatur · ${kelvin.roundToInt()} K", color = Color.White, fontSize = 12.sp)
            Slider(
                value = kelvin,
                onValueChange = { kelvin = it },
                onValueChangeFinished = {
                    controller.controlLamp(device, LampCommands.temperature(kelvin.roundToInt()), "Weißtemperatur")
                },
                valueRange = 1700f..6500f,
                enabled = controller.canIdentifyLamp(device) &&
                    DeviceCapabilities.canOffer(device, "set_ct_abx", allowUnverified),
            )
        }
        if (DeviceCapabilities.evidence(device, "set_hsv") != CapabilityEvidence.UNSUPPORTED) {
            var hue by remember(device.ip, live?.hue) {
                mutableFloatStateOf((live?.hue ?: 180).toFloat())
            }
            var saturation by remember(device.ip, live?.saturation) {
                mutableFloatStateOf((live?.saturation ?: 70).toFloat())
            }
            val hsvEnabled = controller.canIdentifyLamp(device) &&
                DeviceCapabilities.canOffer(device, "set_hsv", allowUnverified)
            Text("HSV-Farbsteuerung", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Farbton · ${hue.roundToInt()}°", color = muted, fontSize = 12.sp)
            Slider(
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = {
                    controller.controlLamp(device,
                        LampCommands.hsv(hue.roundToInt(), saturation.roundToInt()),
                        "HSV-Farbton")
                },
                valueRange = 0f..359f,
                enabled = hsvEnabled,
            )
            Text("Sättigung · ${saturation.roundToInt()} %", color = muted, fontSize = 12.sp)
            Slider(
                value = saturation,
                onValueChange = { saturation = it },
                onValueChangeFinished = {
                    controller.controlLamp(device,
                        LampCommands.hsv(hue.roundToInt(), saturation.roundToInt()),
                        "HSV-Sättigung")
                },
                valueRange = 0f..100f,
                enabled = hsvEnabled,
            )
        }
        OutlinedButton(
            onClick = { controller.identifyLamp(device) },
            enabled = controller.canIdentifyLamp(device),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Diese Lampe kurz identifizieren") }
        Text(
            "Identifikation nur auf dieser Lampe ohne laufende Lichtshow. Andere Lampen mit offener Wiederherstellung werden nicht verändert.",
            color = muted, fontSize = 11.sp
        )
    }
}


@Composable
private fun ChasePanel(controller: LoftController,
    initialStyle: ChaseStyle = ChaseStyle.RAINBOW_TAIL
) {
    var style by rememberSaveable { mutableStateOf(initialStyle) }
    var styleMenu by remember { mutableStateOf(false) }
    // The top-level target selector controls ALL scene types, including chases.
    val choice = controller.target
    val scannerEligible = controller.previewScannerEligibleCount(choice)
    var direction by rememberSaveable { mutableStateOf(ChaseDirection.FORWARD) }
    var bounce by rememberSaveable { mutableStateOf(initialStyle == ChaseStyle.KNIGHT_RIDER) }
    var palette by rememberSaveable { mutableStateOf(
        if (initialStyle == ChaseStyle.KNIGHT_RIDER) "#FF0000"
        else "#FF3ABF, #FFB12C, #5A4BFF, #25D9E6, #FFE474"
    ) }
    var ms by rememberSaveable { mutableFloatStateOf(450f) }
    var tail by rememberSaveable { mutableFloatStateOf(if (initialStyle == ChaseStyle.KNIGHT_RIDER) 1f else 4f) }
    var intensity by rememberSaveable { mutableFloatStateOf(75f) }
    var name by rememberSaveable { mutableStateOf(if (initialStyle == ChaseStyle.KNIGHT_RIDER)
        "Knight Rider Scanner" else "Meine Sternschnuppe") }
    var previewStep by rememberSaveable { mutableFloatStateOf(0f) }
    val parsedPalette = remember(palette) { runCatching { ChaseCompiler.decodePalette(palette) } }
    val config = parsedPalette.getOrNull()?.let { colors ->
        ChaseConfig(style, colors, ms.roundToInt(), tail.roundToInt(),
            intensity.roundToInt(), direction, bounce)
    }
    Surface(color = panel, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (style == ChaseStyle.KNIGHT_RIDER)
                "KNIGHT RIDER · EIN LAMPENLICHT"
            else "LIVE-STEUERUNG", color = lavender, fontWeight = FontWeight.Bold,
                fontSize = 12.sp)
            Text("Lichtweg: " + controller.targetLabel(choice) + " · " +
                controller.previewCount(choice) + " Lampen",
                color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (config != null) controller.playChase(config, choice) },
                    enabled = !controller.busy && !controller.scannerRunning &&
                        config != null &&
                            (if (style == ChaseStyle.KNIGHT_RIDER)
                                scannerEligible in 2..24
                            else controller.previewCount(choice) in 1..24),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Starten")
                }
                OutlinedButton(
                    onClick = controller::stop,
                    enabled = controller.scannerRunning ||
                        (controller.canRetryRecovery &&
                        (controller.recoveryPending || controller.selectedCount() > 0)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Stoppen")
                }
            }
            Text(controller.status, color = muted, fontSize = 12.sp)
            if (controller.scannerRunning) {
                Text("Aktive Lampe: " +
                    (controller.scannerActiveIndex?.plus(1)?.toString() ?: "Vorbereitung") +
                    " · Schritt " + controller.scannerSteps,
                    color = Color(0xFF80D9C6), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = Color(0xFF49475F))
            Text("Effekt einstellen", color = Color.White, fontWeight = FontWeight.SemiBold)
            val availableStyles = listOf(
                ChaseStyle.KNIGHT_RIDER to "Knight Rider",
                ChaseStyle.RAINBOW_TAIL to "Regenbogenschweif",
                ChaseStyle.METEOR to "Sternschnuppe",
                ChaseStyle.COLOR_WAVE to "Farbwelle"
            )
            Box {
                OutlinedButton(onClick = { styleMenu = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(availableStyles.first { it.first == style }.second,
                        modifier = Modifier.weight(1f))
                    Text("⌄")
                }
                DropdownMenu(expanded = styleMenu,
                    onDismissRequest = { styleMenu = false }) {
                    availableStyles.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            styleMenu = false
                            style = value
                            if (value == ChaseStyle.KNIGHT_RIDER) {
                                bounce = true
                                palette = "#FF0000"
                                tail = 1f
                                name = "Knight Rider Scanner"
                            }
                        })
                    }
                }
            }
            Text("Lichtweg-Ziel: " + controller.targetLabel(choice) +
                " · " + controller.previewCount(choice) + " Lampen",
                color = Color.White, fontWeight = FontWeight.SemiBold)
            if (style == ChaseStyle.KNIGHT_RIDER) {
                Text("$scannerEligible für den Scanner geeignet · " +
                    (controller.previewCount(choice) - scannerEligible) +
                    " unbestätigt, inkompatibel oder mit offener Wiederherstellung",
                    color = if (scannerEligible >= 2) Color(0xFF80D9C6)
                        else Color(0xFFFFC1A6), fontSize = 12.sp)
                if (scannerEligible < 2) {
                    Text("Mindestens 2 kompatible RGB-Lampen auswählen und gegebenenfalls " +
                        "über Geräte & Netzwerk neu erkennen.",
                        color = muted, fontSize = 12.sp)
                }
            }
            Text("Ziel und Gruppen im Auswahlbereich oben ändern. " +
                "Die Laufrichtung folgt der Sortierung unter Geräte.",
                color = muted, fontSize = 11.sp)
            if (style == ChaseStyle.KNIGHT_RIDER) {
                Text("Nur die gerade aktive Lampe leuchtet. Alle anderen " +
                    "Teilnehmer bleiben nach bestätigtem AUS dunkel. " +
                    "Nicht ausgewählte Lampen werden nicht verändert.",
                    color = muted, fontSize = 12.sp)
                Text("Schnellfarbe", color = Color.White, fontSize = 12.sp)
                listOf("#FF0000" to "Rot", "#00FF00" to "Grün",
                    "#0000FF" to "Blau", "#FFB000" to "Amber",
                    "#FFFFFF" to "Weiß", "#FF00FF" to "Magenta")
                    .chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { (hex, label) ->
                                FilterChip(selected = palette == hex,
                                    onClick = { palette = hex },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(label, maxLines = 1,
                                        fontSize = 11.sp) })
                            }
                        }
                    }
            }
            Text(if (style == ChaseStyle.KNIGHT_RIDER)
                "Farbe oder wechselnde Farben · #RRGGBB"
            else "Farbpalette · RGB-Hex, durch Komma getrennt",
                color = Color.White, fontSize = 12.sp)
            OutlinedTextField(
                value = palette,
                onValueChange = { palette = it.take(180) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Farben (#RRGGBB)") },
            )
            if (parsedPalette.isFailure) {
                Text(parsedPalette.exceptionOrNull()?.message ?: "Ungültige Farben",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    parsedPalette.getOrNull().orEmpty().forEach { rgb ->
                        Box(Modifier.weight(1f).height(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF000000L or rgb.toLong())))
                    }
                }
            }
            if (config != null && (if (style == ChaseStyle.KNIGHT_RIDER)
                    scannerEligible > 0 else controller.previewCount(choice) > 0)) {
                val lampCount = (if (style == ChaseStyle.KNIGHT_RIDER)
                    scannerEligible else controller.previewCount(choice)).coerceAtMost(24)
                val count = ChaseCompiler.frames(config, 0, lampCount).size
                val step = previewStep.roundToInt().coerceIn(0, count - 1)
                Text("Effektvorschau · Schritt ${step + 1} von $count",
                    color = Color.White, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(lampCount) { index ->
                        val frame = ChaseCompiler.frames(config, index, lampCount)[step]
                        val dark = style == ChaseStyle.KNIGHT_RIDER &&
                            frame.rgb == 0 && frame.brightness == 1
                        val tint = if (dark) Color(0xFF0B0E16)
                            else Color(0xFF000000L or frame.rgb.toLong())
                                .copy(alpha = 0.2f + 0.8f * frame.brightness / 100f)
                        Box(Modifier.weight(1f).height(24.dp)
                            .clip(RoundedCornerShape(6.dp)).background(tint))
                    }
                }
                Slider(
                    value = previewStep.coerceIn(0f, (count - 1).toFloat()),
                    onValueChange = { previewStep = it },
                    valueRange = 0f..(count - 1).toFloat()
                )
                Text("Simulation der Reihenfolge – keine Statusanzeige der echten Lampen.",
                    color = muted, fontSize = 11.sp)
            }
            val selectedCount = if (style == ChaseStyle.KNIGHT_RIDER)
                scannerEligible else controller.previewCount(choice)
            val effectiveMs = if (style == ChaseStyle.KNIGHT_RIDER &&
                selectedCount in 2..24)
                    KnightRiderPlan.effectiveStepMs(ms.roundToInt(), selectedCount, bounce)
                else ms.roundToInt()
            Text("Tempo · " + (if (style == ChaseStyle.KNIGHT_RIDER)
                "$effectiveMs ms je Schritt (sichere Befehlsrate)"
                else "${ms.roundToInt()} ms je Lampe"),
                color = Color.White, fontSize = 12.sp)
            Slider(value = ms, onValueChange = { ms = it }, valueRange = 180f..2500f)
            if (style == ChaseStyle.KNIGHT_RIDER && effectiveMs > ms.roundToInt()) {
                Text("Zum Schutz vor Yeelight-Limits auf $effectiveMs ms begrenzt. " +
                    "Bei mehr beteiligten Lampen sind schnellere Scans möglich.",
                    color = Color(0xFFFFC1A6), fontSize = 11.sp)
            }
            if (style != ChaseStyle.KNIGHT_RIDER) {
                Text("Schweiflänge · ${tail.roundToInt()} Lampen", color = Color.White, fontSize = 12.sp)
                Slider(value = tail, onValueChange = { tail = it }, valueRange = 1f..16f)
            }
            Text("Maximale Helligkeit · ${intensity.roundToInt()} %", color = Color.White, fontSize = 12.sp)
            Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 5f..100f)
            Text("Laufrichtung", color = Color.White, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = direction == ChaseDirection.FORWARD,
                    onClick = { direction = ChaseDirection.FORWARD }, label = { Text("Vorwärts") })
                FilterChip(selected = direction == ChaseDirection.REVERSE,
                    onClick = { direction = ChaseDirection.REVERSE }, label = { Text("Rückwärts") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bounce, onCheckedChange = { bounce = it })
                Text("Pingpong · an den Enden umkehren", color = Color.White, fontSize = 12.sp)
            }
            Text(if (style == ChaseStyle.KNIGHT_RIDER)
                "Nur Geräte mit bestätigten RGB-/Szenen-/Ein-Aus-Befehlen nehmen teil. " +
                    "Zu Beginn und nach Stopp werden sie ausdrücklich ausgeschaltet. " +
                    "Wenn AUS nicht bestätigt wird, bricht der Scanner ab."
            else "Beim Start siehst du die Ergebnisse für jede Lampe im Lampenbericht. " +
                "Ungeeignete oder offline befindliche Lampen verhindern nicht mehr den Start anderer Lampen.",
                color = muted, fontSize = 11.sp)
            Text("Hinweis: Schnell wechselnde Farben können für lichtempfindliche Personen ungeeignet sein.",
                color = muted, fontSize = 11.sp)
            HorizontalDivider(color = Color(0xFF49475F))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(64) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Name der eigenen Szene") }
            )
            OutlinedButton(
                onClick = {
                    if (config != null) runCatching { controller.saveChase(name, config) }
                        .onFailure { controller.report("Speichern fehlgeschlagen: " + it.message) }
                },
                enabled = config != null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aktuelle Einstellungen als Szene speichern") }
            if (controller.savedChases.isNotEmpty()) {
                Text("Gespeicherte Lichtläufe", color = Color.White, fontWeight = FontWeight.SemiBold)
                controller.savedChases.forEach { saved ->
                    Surface(color = Color(0xFF2A2F43), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(saved.name, color = Color.White, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TextButton(onClick = {
                                    val c = saved.config
                                    style = c.style
                                    palette = ChaseCompiler.encodePalette(c.palette)
                                    ms = c.stepMs.toFloat()
                                    tail = c.tailLength.toFloat()
                                    intensity = c.brightness.toFloat()
                                    direction = c.direction
                                    bounce = c.bounce
                                    name = saved.name
                                }) { Text("Laden") }
                                TextButton(onClick = {
                                    controller.playChase(saved.config, choice, saved.name)
                                }, enabled = !controller.busy && controller.devices.isNotEmpty()) {
                                    Text("Starten")
                                }
                                TextButton(onClick = { controller.deleteChase(saved) },
                                    enabled = !controller.busy) { Text("Löschen") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneRestoreOption(controller: LoftController) {
    Surface(color = panel, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nach Stopp vorheriges Licht wiederherstellen",
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Ein/Aus, Farbe und Helligkeit jeder Lampe werden vor der Szene erfasst.",
                        color = muted, fontSize = 11.sp)
                }
                Switch(
                    checked = controller.restoreAfterStop,
                    onCheckedChange = controller::configureRestoreAfterStop,
                    enabled = controller.canOrganizeLamps
                )
            }
            Text(
                "Nur Lampen mit sicher lesbarem Ausgangszustand werden gestartet. " +
                    "Offline- und nicht wiederherstellbare Geräte werden übersprungen. " +
                    "Die Wiederherstellung erfolgt nach dem Stoppen.",
                color = muted, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SceneExchangePanel(controller: LoftController) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<SceneArchiveData?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SceneArchive.mimeType)
    ) { uri ->
        val payload = pendingExport
        if (uri != null && payload != null) runCatching {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: error("Zieldatei nicht beschreibbar")
        }.onSuccess {
            controller.report("Szenen erfolgreich als JSON exportiert")
        }.onFailure {
            controller.report("Export fehlgeschlagen: " + it.message)
        }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(SceneArchive.maxBytes + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                require(total <= SceneArchive.maxBytes) { "Importdatei ist zu groß" }
                String(buffer, 0, total, StandardCharsets.UTF_8)
            } ?: error("Datei nicht lesbar")
            SceneArchive.decode(bytes)
        }.onSuccess { archive ->
            pendingImport = archive
            selected = archive.flows.map { "flow:" + it.id }.toSet() +
                archive.chases.map { "chase:" + it.id }.toSet()
        }.onFailure { controller.report("Import fehlgeschlagen: " + it.message) }
    }

    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Szenen sichern und übertragen", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Gesamte Sammlung oder einzelne Szenen – lokale JSON-Dateien ohne IP-Adressen, Geräte-IDs oder Grundrisse.",
                color = muted, fontSize = 12.sp)
            Button(onClick = {
                pendingExport = controller.exportSceneArchive()
                exportLauncher.launch(SceneArchive.fileName)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Alle Szenen exportieren")
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf(SceneArchive.mimeType, "text/plain"))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Szenen importieren · einzeln auswählen")
            }
            Text("Einzeln exportieren", color = Color.White, fontWeight = FontWeight.SemiBold)
            controller.allScenes().forEach { scene ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scene.name, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        pendingExport = controller.exportSingleFlow(scene)
                        exportLauncher.launch("yeelight-flow-" + scene.id + ".json")
                    }) { Text("Export") }
                }
            }
            controller.savedChases.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        pendingExport = controller.exportSingleChase(preset)
                        exportLauncher.launch("yeelight-chase-" + preset.id + ".json")
                    }) { Text("Export") }
                }
            }
            Text("Identische Szenen werden beim Import übersprungen; deine vorhandenen bleiben erhalten.",
                color = muted, fontSize = 11.sp)
        }
    }
    pendingImport?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Szenen zum Import auswählen") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text("Nur markierte Szenen werden übernommen.",
                        fontSize = 12.sp)
                    archive.flows.forEach { scene ->
                        val key = "flow:" + scene.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + key else selected - key
                                })
                            Text(scene.name + " · Farbverlauf", fontSize = 12.sp)
                        }
                    }
                    archive.chases.forEach { preset ->
                        val key = "chase:" + preset.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + key else selected - key
                                })
                            Text(preset.name + " · Lichtlauf", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        runCatching {
                            val subset = SceneArchiveData(
                                archive.flows.filter { "flow:" + it.id in selected },
                                archive.chases.filter { "chase:" + it.id in selected }
                            )
                            controller.importSceneArchive(SceneArchive.encode(subset))
                        }.onSuccess { controller.report(it); pendingImport = null }
                            .onFailure { controller.report("Import fehlgeschlagen: " + it.message) }
                    }
                ) { Text("Auswahl importieren") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun Note(message: String) {
    Surface(color = Color(0xFF22283B), shape = RoundedCornerShape(16.dp)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(15.dp), color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun Footer() {
    Text("PRIVAT IM WLAN · KEINE CLOUD · v" + BuildConfig.VERSION_NAME +
        " (Build " + BuildConfig.VERSION_CODE + ")",
        color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
}
