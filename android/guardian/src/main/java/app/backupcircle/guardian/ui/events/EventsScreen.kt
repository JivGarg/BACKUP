package app.backupcircle.guardian.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.util.Format
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.EventRow
import app.backupcircle.guardian.ui.alert.AlertActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dayFormat)
    }
}

/** G9: a timeline grouped by day. */
@Composable
fun EventsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.guardianGraph.repository
    val events by repo.events.collectAsStateWithLifecycle()
    val members by repo.members.collectAsStateWithLifecycle()
    val byDay = events
        .sortedByDescending { it.id }
        .groupBy { Instant.ofEpochMilli(repo.toLocal(it.occurredAt)).atZone(ZoneId.systemDefault()).toLocalDate() }

    Column(Modifier.fillMaxSize().background(BackupColors.Background)) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
            Text("Events", style = MaterialTheme.typography.headlineSmall)
        }
        if (events.isEmpty()) {
            Text(
                "No events yet. Calls from unknown numbers and alerts will show up here.",
                color = BackupColors.TextSecondary,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            byDay.forEach { (day, list) ->
                item(key = "day-$day") {
                    Text(
                        dayLabel(day),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BackupColors.TextSecondary,
                        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { event ->
                    val name = members.firstOrNull { it.id == event.memberId }?.name ?: "Your person"
                    EventRow(
                        event,
                        name,
                        Format.clock(context, repo.toLocal(event.occurredAt)),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        onClick = if (event.isAlert) ({ context.startActivity(AlertActivity.intent(context, event.id)) }) else null,
                    )
                }
            }
        }
    }
}
