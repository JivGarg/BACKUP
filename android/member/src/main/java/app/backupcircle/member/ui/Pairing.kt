package app.backupcircle.member.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.WaveBackground
import app.backupcircle.core.ui.hiddenLongPress
import app.backupcircle.member.R
import app.backupcircle.member.data.MemberRepository
import app.backupcircle.member.memberGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CodeState {
    data object Loading : CodeState
    data class Ready(val code: String, val expiresAtElapsed: Long) : CodeState
    data class Failed(val error: ApiException) : CodeState
}

class CodeViewModel(private val repo: MemberRepository) : ViewModel() {
    private val _state = MutableStateFlow<CodeState>(CodeState.Loading)
    val state: StateFlow<CodeState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        // The "paired" push normally arrives at once; polling covers a missed push.
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                runCatching { repo.refreshMe() }
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = CodeState.Loading
            _state.value = try {
                val code = repo.requestCode()
                // Count down on this phone's own clock, so phone and laptop clocks never need to match.
                CodeState.Ready(code.code, SystemClock.elapsedRealtime() + code.expiresInSeconds * 1000L)
            } catch (e: ApiException) {
                CodeState.Failed(e)
            }
        }
    }
}

/** M4: the reverse of the reference code screen; this phone shows the code. */
@Composable
fun CodeScreen(showBack: Boolean, onBack: () -> Unit, onConnected: (String) -> Unit, onDemoPanel: () -> Unit) {
    val graph = LocalContext.current.memberGraph
    val vm: CodeViewModel = viewModel { CodeViewModel(graph.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    val knownAtStart = remember { prefs.guardians.map { it.guardianId }.toSet() }

    // A new guardian appeared (pushed "paired" or found by polling): go to M5.
    LaunchedEffect(prefs.guardians) {
        prefs.guardians.firstOrNull { it.guardianId !in knownAtStart }?.let { onConnected(it.name) }
    }

    WaveBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            if (showBack) {
                IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(30.dp))
                }
            } else {
                Spacer(Modifier.height(40.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.code_title),
                style = MaterialTheme.typography.headlineLarge,
                color = BackupColors.TextPrimary,
                modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel),
            )
            Spacer(Modifier.height(24.dp))
            when (val s = state) {
                is CodeState.Loading -> CodeCard {
                    CircularProgressIndicator(Modifier.size(48.dp), color = BackupColors.Primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.code_loading), style = MaterialTheme.typography.bodyLarge)
                }
                is CodeState.Failed -> {
                    CodeCard {
                        Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = BackupColors.Warning, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.code_error_title),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(errorText(s.error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = BackupColors.TextSecondary)
                    }
                    Spacer(Modifier.height(24.dp))
                    BigButton(stringResource(R.string.retry), onClick = vm::load)
                }
                is CodeState.Ready -> ReadyCode(s, onNewCode = vm::load)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CodeCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ReadyCode(state: CodeState.Ready, onNewCode: () -> Unit) {
    var remaining by remember(state) { mutableLongStateOf((state.expiresAtElapsed - SystemClock.elapsedRealtime()) / 1000) }
    LaunchedEffect(state) {
        while (true) {
            remaining = (state.expiresAtElapsed - SystemClock.elapsedRealtime()) / 1000
            if (remaining <= 0) {
                onNewCode() // expired: fetch a fresh one automatically
                break
            }
            delay(1_000)
        }
    }
    CodeCard {
        Text(
            "${state.code.take(3)} ${state.code.drop(3)}",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = BackupColors.TextPrimary,
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.code_hint), style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextPrimary)
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = BackupColors.Primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.code_waiting), style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextSecondary)
    }
    Spacer(Modifier.height(12.dp))
    val minutes = (remaining.coerceAtLeast(0)) / 60
    val seconds = (remaining.coerceAtLeast(0)) % 60
    Text(
        stringResource(R.string.code_expires, "%d:%02d".format(minutes, seconds)),
        style = MaterialTheme.typography.bodyMedium,
        color = BackupColors.TextSecondary,
    )
    TextButton(onClick = onNewCode, modifier = Modifier.heightIn(min = 64.dp)) {
        Text(stringResource(R.string.code_new), style = MaterialTheme.typography.labelLarge, color = BackupColors.Primary)
    }
}

/** M5: large green tick, then home after 3 seconds. */
@Composable
fun ConnectedScreen(name: String, onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3_000)
        onDone()
    }
    Screen {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(180.dp).clip(CircleShape).background(BackupColors.Success),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(120.dp))
            }
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.connected_title, name),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = BackupColors.TextPrimary,
            )
        }
    }
}
