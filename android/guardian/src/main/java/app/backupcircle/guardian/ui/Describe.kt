package app.backupcircle.guardian.ui

import androidx.compose.ui.graphics.Color
import app.backupcircle.core.net.EventTypes
import app.backupcircle.core.net.EventView
import app.backupcircle.core.net.HIDDEN_NUMBER
import app.backupcircle.core.net.HealthKeys
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.util.Format
import app.backupcircle.guardian.data.MemberState

/** Plain-language text shared by the dashboard, events list, alert screen and notifications. */

fun maskedText(masked: String): String = if (masked.isBlank() || masked == HIDDEN_NUMBER) "a hidden number" else masked

fun eventSentence(e: EventView, name: String): String = when (e.type) {
    EventTypes.CALL_STARTED -> "$name answered a call from an unknown number (${maskedText(e.maskedNumber)})"
    EventTypes.LONG_UNKNOWN_CALL -> "$name has been on a call with an unknown number for ${Format.duration(e.elapsedSeconds)}"
    EventTypes.CALL_ENDED -> "$name's call with an unknown number ended after ${Format.duration(e.durationSeconds)}"
    EventTypes.RISK_ALERT -> if (e.isHigh) {
        "$name opened ${e.appLabel} after ${Format.duration(e.callDurationSeconds)} on a call with an unknown number"
    } else {
        "$name opened ${e.appLabel} during a call with an unknown number"
    }
    else -> e.type
}

fun resolutionText(e: EventView): String? {
    if (!e.resolved) return null
    val who = e.resolvedByName ?: "Someone"
    return if (e.resolution == "called") "$who called" else "Marked safe by $who"
}

fun alertTitle(e: EventView, name: String): String =
    if (e.isHigh) "$name may be talking to a scammer." else "$name opened a payment app during a call with an unknown number."

fun alertBody(e: EventView, name: String): String {
    val duration = Format.duration(e.callDurationSeconds)
    return if (e.callActive) {
        "$name has been on a call with an unknown number for $duration and just opened ${e.appLabel}."
    } else {
        "$name was on a call with an unknown number for $duration and just opened ${e.appLabel}."
    }
}

private val healthLabels = mapOf(
    HealthKeys.NOTIFICATIONS to "notifications are off",
    HealthKeys.PHONE to "phone permission is off",
    HealthKeys.CALL_SCREENING to "Caller ID & spam is off",
    HealthKeys.USAGE_ACCESS to "usage access is off",
)

fun healthProblem(health: Map<String, Boolean>): String? =
    HealthKeys.REQUIRED.firstOrNull { health[it] == false }?.let { "Needs attention: ${healthLabels[it]}" }

data class Chip(val text: String, val dot: Color, val background: Color)

/** One status chip for the hero card, most urgent first. */
fun statusChip(member: MemberState, openAlert: EventView?, nowLocal: Long, clock: (Long) -> String): Chip {
    val call = member.activeCall
    return when {
        openAlert != null && openAlert.isHigh -> Chip("Alert · tap to view", BackupColors.Danger, BackupColors.DangerSoft)
        openAlert != null -> Chip("Alert · tap to view", BackupColors.Warning, BackupColors.WarningSoft)
        call != null -> Chip(
            "On a call with an unknown number · ${Format.timer((nowLocal - call.startedAtLocal) / 1000)}",
            BackupColors.Warning,
            BackupColors.WarningSoft,
        )
        !member.online -> Chip(
            "Offline · last seen " + (member.lastSeenLocal?.let(clock) ?: "never"),
            BackupColors.TextSecondary,
            BackupColors.Border,
        )
        healthProblem(member.health) != null -> Chip(healthProblem(member.health)!!, BackupColors.Warning, BackupColors.WarningSoft)
        else -> Chip("Protected · Online", BackupColors.Success, BackupColors.SuccessSoft)
    }
}
