package app.backupcircle.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last 20 connection lines, shown in the hidden demo panels. */
class ConnectionLog(private val capacity: Int = 20) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(text: String) {
        val line = "${format.format(Date())}  $text"
        _lines.update { (it + line).takeLast(capacity) }
    }
}
