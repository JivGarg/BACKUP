package app.backupcircle.guardian.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.net.EventTypes
import app.backupcircle.core.net.EventView
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.ShieldIllustration
import app.backupcircle.core.ui.ShieldMark
import app.backupcircle.core.ui.StatusPill
import app.backupcircle.core.ui.hiddenLongPress
import app.backupcircle.core.util.Format
import app.backupcircle.guardian.data.GuardianRepository
import app.backupcircle.guardian.data.MemberState
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.EventRow
import app.backupcircle.guardian.ui.TextInputDialog
import app.backupcircle.guardian.ui.alert.AlertActivity
import app.backupcircle.guardian.ui.Chip
import app.backupcircle.guardian.ui.dial
import app.backupcircle.guardian.ui.maskedText
import app.backupcircle.guardian.ui.onboarding.alertPermissionGap
import app.backupcircle.guardian.ui.rememberNow
import app.backupcircle.guardian.ui.statusChip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HomeUi(
    val members: List<MemberState> = emptyList(),
    val selected: MemberState? = null,
    /** Selected member's events, newest first. */
    val events: List<EventView> = emptyList(),
    val connection: ConnState = ConnState.Disconnected,
    val synced: Boolean = false,
)

class HomeViewModel(private val repo: GuardianRepository) : ViewModel() {
    private val selectedId = MutableStateFlow<String?>(null)

    val ui: StateFlow<HomeUi> = combine(repo.members, repo.events, repo.connection, selectedId, repo.synced) { members, events, conn, sel, synced ->
        val selected = members.firstOrNull { it.id == sel } ?: members.firstOrNull()
        HomeUi(
            members = members,
            selected = selected,
            events = events.filter { it.memberId == selected?.id }.sortedByDescending { it.id },
            connection = conn,
            synced = synced,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi())

    fun select(id: String) {
        selectedId.value = id
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repo.sync() } }
    }
}

internal enum class HomeSheet { Add, Settings, People, Server, Help, About, MyName, CallNumber }

private fun isToday(localMs: Long): Boolean =
    Instant.ofEpochMilli(localMs).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

/** G4 (empty state) and G7 (dashboard). */
@Composable
fun HomeScreen(onPair: () -> Unit, onEvents: () -> Unit, onFixPermissions: () -> Unit, onDemoPanel: () -> Unit) {
    val context = LocalContext.current
    val graph = context.guardianGraph
    val repo = graph.repository
    val vm: HomeViewModel = viewModel { HomeViewModel(repo) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var permissionGap by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        permissionGap = alertPermissionGap(context)
        vm.refresh()
        onPauseOrDispose { }
    }
    val now = rememberNow()
    val clock = { ms: Long -> Format.clock(context, ms) }
    val listState = rememberLazyListState()
    var showNewPill by remember { mutableStateOf(false) }
    val selected = ui.selected

    // "New events" pill: a new event arrived while the recent-events list is out of view.
    LaunchedEffect(Unit) {
        repo.incoming.collect {
            val visible = listState.layoutInfo.visibleItemsInfo.any { info -> info.key == "recent_header" }
            if (!visible && listState.firstVisibleItemIndex > 0) showNewPill = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "recent_header" } }
            .collect { if (it) showNewPill = false }
    }

    val openAlert = ui.events.firstOrNull { it.isAlert && !it.resolved }
    val openAlertActivity = { event: EventView -> context.startActivity(AlertActivity.intent(context, event.id)) }
    val showConnBanner = ui.connection != ConnState.Connected && !ui.synced
    // Item order: header, [connection banner], hero, sheet, [permission banner], recent header.
    val recentHeaderIndex = 3 + (if (showConnBanner) 1 else 0) + (if (permissionGap) 1 else 0)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackupColors.GradientTop, BackupColors.Background))),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Header(ui, onDemoPanel = onDemoPanel, onSelect = vm::select)
            }
            if (showConnBanner) {
                item(key = "conn") { ConnectionBanner(ui.connection, onRetry = { scope.launch { graph.locator.rediscover(); graph.ws.reconnectNow() } }) }
            }
            if (selected == null) {
                item(key = "empty") { EmptyState(onAdd = { sheet = HomeSheet.Add }) }
                return@LazyColumn
            }
            item(key = "hero") {
                HeroCard(
                    member = selected,
                    chip = statusChip(selected, openAlert, now, clock),
                    subtitle = when {
                        openAlert != null -> "Tap to see the alert"
                        selected.activeCall != null -> "Answered a call from ${maskedText(selected.activeCall.maskedNumber)}"
                        else -> null
                    },
                    onClick = openAlert?.let { { openAlertActivity(it) } },
                )
            }
            item(key = "sheet") {
                DashboardSheet(
                    events = ui.events,
                    clock = clock,
                    onCall = {
                        if (selected.phone.isNullOrBlank()) sheet = HomeSheet.CallNumber else dial(context, selected.phone)
                    },
                    onEvents = onEvents,
                    onAdd = { sheet = HomeSheet.Add },
                    onSettings = { sheet = HomeSheet.Settings },
                )
            }
            if (permissionGap) {
                item(key = "gap") {
                    BackupCard(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        color = BackupColors.WarningSoft,
                        borderColor = BackupColors.WarningSoft,
                        onClick = onFixPermissions,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = BackupColors.WarningText)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Alerts may not ring on this phone. Tap to fix alert permissions.",
                                color = BackupColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item(key = "recent_header") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent events", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (ui.events.isNotEmpty()) TextButton(onClick = onEvents) { Text("See all") }
                }
            }
            if (ui.events.isEmpty()) {
                item(key = "no_events") {
                    Text(
                        "Nothing yet. You'll see events here when ${selected.name} answers a call from an unknown number.",
                        color = BackupColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(ui.events.take(20), key = { it.id }) { event ->
                EventRow(
                    event,
                    selected.name,
                    clock(repo.toLocal(event.occurredAt)),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onClick = if (event.isAlert) ({ openAlertActivity(event) }) else null,
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }

        if (showNewPill) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .shadow(8.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(BackupColors.Primary)
                    .clickable {
                        showNewPill = false
                        scope.launch { listState.animateScrollToItem(recentHeaderIndex) }
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("New events", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }

    when (sheet) {
        HomeSheet.Add -> AddPersonSheet(onDismiss = { sheet = null }, onConnect = { sheet = null; onPair() })
        HomeSheet.Settings -> SettingsSheet(
            onDismiss = { sheet = null },
            onOpen = { sheet = it },
        )
        HomeSheet.People -> PeopleSheet(ui.members, onDismiss = { sheet = null })
        HomeSheet.Server -> ServerDialog(onDismiss = { sheet = null })
        HomeSheet.Help -> HelpDialog(onDismiss = { sheet = null })
        HomeSheet.About -> AboutDialog(onDismiss = { sheet = null })
        HomeSheet.MyName -> TextInputDialog(
            title = "My name",
            initial = repo.prefs.name.orEmpty(),
            label = "Your name",
            message = "The people you protect see this name.",
            onDismiss = { sheet = null },
            onConfirm = { repo.rename(it); sheet = null },
        )
        HomeSheet.CallNumber -> if (selected != null) TextInputDialog(
            title = "${selected.name}'s phone number",
            initial = "",
            label = "Phone number",
            confirm = "Save and call",
            phone = true,
            message = "Save it once and the Call button will use it.",
            onDismiss = { sheet = null },
            onConfirm = { number ->
                repo.updateMember(selected.id, phone = number)
                sheet = null
                dial(context, number)
            },
        )
        null -> Unit
    }
}

@Composable
private fun Header(ui: HomeUi, onDemoPanel: () -> Unit, onSelect: (String) -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Backup Circle",
                style = MaterialTheme.typography.headlineSmall,
                color = BackupColors.TextPrimary,
                modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel),
            )
            Spacer(Modifier.width(10.dp))
            val dot = when (ui.connection) {
                ConnState.Connected -> BackupColors.Success
                ConnState.Connecting -> BackupColors.Warning
                ConnState.Disconnected -> BackupColors.TextSecondary
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        }
        if (ui.members.size > 1) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.members, key = { it.id }) { m ->
                    val selected = m.id == ui.selected?.id
                    Text(
                        m.name,
                        color = if (selected) Color.White else BackupColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) BackupColors.Primary else Color.White)
                            .border(1.dp, if (selected) BackupColors.Primary else BackupColors.Border, RoundedCornerShape(50))
                            .clickable { onSelect(m.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(state: ConnState, onRetry: () -> Unit) {
    BackupCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = BackupColors.WarningSoft, borderColor = BackupColors.WarningSoft) {
        Text(
            if (state == ConnState.Connecting) "Connecting to the Backup server…"
            else "Can't reach the Backup server. Make sure this phone is on the same Wi-Fi as the laptop.",
            color = BackupColors.TextPrimary,
        )
        if (state == ConnState.Disconnected) {
            TextButton(onClick = onRetry) { Text("Retry", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    BackupCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldIllustration(size = 150.dp, mark = ShieldMark.People)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add the person you want to protect",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = BackupColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Install Backup on their phone. It shows a 6-digit code that connects it to you.",
                textAlign = TextAlign.Center,
                color = BackupColors.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = BackupColors.Primary),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Add a person to protect", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun HeroCard(member: MemberState, chip: Chip, subtitle: String?, onClick: (() -> Unit)?) {
    BackupCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = 22.dp,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(BackupColors.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    member.name.take(1).uppercase(),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = BackupColors.Primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(member.name, style = MaterialTheme.typography.headlineMedium, color = BackupColors.TextPrimary)
        }
        Spacer(Modifier.height(16.dp))
        StatusPill(chip.text, chip.dot, chip.background)
        if (subtitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(subtitle, color = BackupColors.TextSecondary)
        }
    }
}

@Composable
private fun DashboardSheet(
    events: List<EventView>,
    clock: (Long) -> String,
    onCall: () -> Unit,
    onEvents: () -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val repo = LocalContext.current.guardianGraph.repository
    val today = events.filter { isToday(repo.toLocal(it.occurredAt)) }
    val unknownCalls = today.count { it.type == EventTypes.CALL_STARTED }
    val paymentOpens = today.count { it.type == EventTypes.RISK_ALERT }
    val lastAlert = events.firstOrNull { it.isAlert }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(BackupColors.Border))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("$unknownCalls", "Unknown calls today", Modifier.weight(1f))
            Tile("$paymentOpens", "Payment apps opened during calls", Modifier.weight(1f))
            Tile(lastAlert?.let { clock(repo.toLocal(it.occurredAt)) } ?: "None", "Last alert", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ActionButton(Icons.Rounded.Call, "Call", onCall)
            ActionButton(Icons.Rounded.History, "Events", onEvents)
            ActionButton(Icons.Rounded.PersonAdd, "Add person", onAdd)
            ActionButton(Icons.Rounded.Settings, "Settings", onSettings)
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .height(118.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(BackupColors.Background)
            .padding(12.dp),
    ) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = BackupColors.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = BackupColors.TextSecondary)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, BackupColors.Border, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BackupColors.TextPrimary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = BackupColors.TextPrimary)
    }
}

/** Add sheet (layout from the "connect a device" reference): curved header, one option, Close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPersonSheet(onDismiss: () -> Unit, onConnect: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val curve = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height * 0.78f)
                        quadraticTo(size.width / 2f, size.height * 1.02f, 0f, size.height * 0.78f)
                        close()
                    }
                    drawPath(curve, Brush.verticalGradient(listOf(Color.White, BackupColors.PrimarySoft)))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    PhoneSketch(Modifier.padding(bottom = 30.dp))
                    ShieldIllustration(size = 150.dp, mark = ShieldMark.People)
                    PhoneSketch(Modifier.padding(bottom = 30.dp))
                }
            }
            Text(
                "Who do you want to protect?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
            BackupCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                onClick = onConnect,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = BackupColors.Primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Connect their phone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = null, tint = BackupColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BackupColors.PrimarySoft, contentColor = BackupColors.Primary),
                contentPadding = PaddingValues(vertical = 18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) { Text("Close", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun PhoneSketch(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 34.dp, height = 58.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White)
            .border(3.dp, BackupColors.Primary.copy(alpha = 0.6f), RoundedCornerShape(9.dp)),
    )
}
