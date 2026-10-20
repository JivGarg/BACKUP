package app.backupcircle.guardian.ui.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.WaveBackground
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.ErrorText
import app.backupcircle.guardian.ui.PrimaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** G5: the reverse of the reference code screen; six digit boxes, submits on the 6th digit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterCodeScreen(onBack: () -> Unit, onPaired: (String) -> Unit) {
    val repo = LocalContext.current.guardianGraph.repository
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(250)
        focus.requestFocus()
        keyboard?.show()
    }

    fun submit(value: String) {
        loading = true
        error = null
        scope.launch {
            try {
                val member = repo.pair(value)
                onPaired(member.memberId)
            } catch (e: ApiException) {
                error = e.message
                code = ""
                focus.requestFocus()
                keyboard?.show()
            } finally {
                loading = false
            }
        }
    }

    WaveBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = BackupColors.TextPrimary)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Enter the code shown on their phone",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Normal,
                color = BackupColors.TextPrimary,
            )
            Spacer(Modifier.height(28.dp))
            BasicTextField(
                value = code,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(6)
                    code = digits
                    if (digits.isNotEmpty()) error = null
                    if (digits.length == 6 && !loading) submit(digits)
                },
                enabled = !loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { _ ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(6) { i ->
                            val active = i == code.length && !loading
                            Box(
                                Modifier
                                    .size(width = 42.dp, height = 58.dp)
                                    .border(2.dp, if (active) BackupColors.Primary else BackupColors.Border, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    code.getOrNull(i)?.toString() ?: "",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BackupColors.TextPrimary,
                                )
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…", color = BackupColors.TextPrimary)
                }
            }
            ErrorText(error)
            Spacer(Modifier.height(20.dp))
            Text(
                "Where is the code?",
                color = BackupColors.Primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showHelp = true }.padding(vertical = 6.dp),
            )
        }
    }

    if (showHelp) {
        ModalBottomSheet(onDismissRequest = { showHelp = false }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Where is the code?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    "Open Backup on their phone. The 6-digit code is on the screen. If it says expired, tap New code.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { showHelp = false },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BackupColors.PrimarySoft, contentColor = BackupColors.Primary),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Got it", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private val NAMES = listOf("Papa", "Mummy", "Dadi", "Dada", "Nani", "Nana", "Other")

/** G6: choose what to call them and optionally save their number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NameThemScreen(memberId: String, onDone: () -> Unit) {
    val repo = LocalContext.current.guardianGraph.repository
    val members by repo.members.collectAsStateWithLifecycle()
    val existing = members.firstOrNull { it.id == memberId }
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<String?>(existing?.displayName?.takeIf { it in NAMES }) }
    var other by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = if (choice == "Other") other.trim() else choice.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(BackupColors.Background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = BackupColors.Success)
            Spacer(Modifier.width(8.dp))
            Text("Connected", color = BackupColors.Success, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("What do you call them?", style = MaterialTheme.typography.headlineLarge, color = BackupColors.TextPrimary)
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NAMES.forEach { option ->
                val selected = option == choice
                Text(
                    option,
                    color = if (selected) Color.White else BackupColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) BackupColors.Primary else Color.White)
                        .border(1.dp, if (selected) BackupColors.Primary else BackupColors.Border, RoundedCornerShape(50))
                        .clickable { choice = option }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
        if (choice == "Other") {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = other,
                onValueChange = { other = it.take(40) },
                label = { Text("Their name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Their phone number (optional)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.take(30) },
            placeholder = { Text("+91 98765 43210") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Used for the Call button.", color = BackupColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        ErrorText(error)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            "Save",
            enabled = name.isNotEmpty(),
            loading = saving,
            onClick = {
                saving = true
                error = null
                scope.launch {
                    try {
                        repo.updateMember(memberId, displayName = name, phone = phone.trim().ifEmpty { null })
                        onDone()
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        saving = false
                    }
                }
            },
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}
