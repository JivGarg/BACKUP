package app.backupcircle.core.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

object Format {
    /** "45 s", "5 min 12 s", "1 h 5 min" (English, for the Guardian app). */
    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 && seconds > 0 -> "$minutes min $seconds s"
            minutes > 0 -> "$minutes min"
            else -> "$seconds s"
        }
    }

    /** Live call timer: "03:12" or "1:03:12". */
    fun timer(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    /** Local time of day in the phone's 12/24-hour style, e.g. "12:40". */
    fun clock(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    /** "••••3210" (only the last 4 digits ever leave the phone) or "hidden". */
    fun maskNumber(number: String?): String {
        val digits = number.orEmpty().filter { it.isDigit() }
        return when {
            digits.isEmpty() -> "hidden"
            digits.length <= 4 -> "••••$digits"
            else -> "••••" + digits.takeLast(4)
        }
    }
}
