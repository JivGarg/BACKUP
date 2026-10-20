package app.backupcircle.guardian.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.backupcircle.core.net.EventTypes
import app.backupcircle.core.net.EventView
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.IconBadge

private data class EventLook(val icon: ImageVector, val color: Color, val background: Color)

private fun look(e: EventView): EventLook = when (e.type) {
    EventTypes.RISK_ALERT -> if (e.isHigh) {
        EventLook(Icons.Rounded.Warning, BackupColors.Danger, BackupColors.DangerSoft)
    } else {
        EventLook(Icons.Rounded.Warning, BackupColors.Warning, BackupColors.WarningSoft)
    }
    EventTypes.LONG_UNKNOWN_CALL -> EventLook(Icons.Rounded.Timer, BackupColors.Warning, BackupColors.WarningSoft)
    EventTypes.CALL_ENDED -> EventLook(Icons.Rounded.CallEnd, BackupColors.TextSecondary, Color.White)
    else -> EventLook(Icons.Rounded.PhoneInTalk, BackupColors.TextSecondary, Color.White)
}

/** One timeline row: icon, sentence, time, and how an alert was handled. */
@Composable
fun EventRow(event: EventView, name: String, time: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val l = look(event)
    var base = modifier.fillMaxWidth()
    if (onClick != null) base = base.clickable(onClick = onClick)
    Row(base.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        IconBadge(l.color, l.background, size = 40.dp) {
            Icon(l.icon, contentDescription = null, tint = l.color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                eventSentence(event, name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (event.isAlert) FontWeight.SemiBold else FontWeight.Normal,
                color = if (event.isHigh) BackupColors.Danger else BackupColors.TextPrimary,
            )
            resolutionText(event)?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = BackupColors.Success, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = BackupColors.Success, fontWeight = FontWeight.SemiBold)
                }
            }
            if (event.isAlert && !event.resolved) {
                Text("Not handled yet", style = MaterialTheme.typography.bodySmall, color = BackupColors.Danger, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(time, style = MaterialTheme.typography.bodySmall, color = BackupColors.TextSecondary)
    }
}