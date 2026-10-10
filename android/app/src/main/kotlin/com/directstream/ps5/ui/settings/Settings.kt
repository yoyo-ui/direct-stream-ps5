package com.directstream.ps5.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.diag.Issue
import com.directstream.ps5.core.diag.Presets
import com.directstream.ps5.core.diag.Severity
import com.directstream.ps5.data.AppConfig
import com.directstream.ps5.data.ThemeMode
import com.directstream.ps5.ui.*
import com.directstream.ps5.ui.theme.*

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as DirectStreamApp
    val ctx = LocalContext.current
    val saved by app.settings.config.collectAsStateWithLifecycle()
    var c by remember(saved) { mutableStateOf(saved) }
    var password by remember { mutableStateOf(app.settings.password()) }
    var issues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var payloadOk by remember { mutableStateOf(app.settings.payloadImported()) }
    var payloadMsg by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf(false) }

    val payloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            payloadOk = bytes != null && bytes.size < 64 * 1024 * 1024 && app.settings.importPayload(bytes)
            payloadMsg = if (payloadOk) "Imported (${Fmt.bytes(bytes!!.size.toLong())})." else "That file is not a valid ELF payload (or is too large)."
        }
    }

    @Composable fun num(label: String, v: Int, onChange: (Int) -> Unit, suffix: String? = null, field: String) {
        OutlinedTextField(v.toString(), { s -> s.filter { it.isDigit() }.take(6).toIntOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, suffix = suffix?.let { { Text(it) } },
            isError = issues.any { it.field == field && it.severity == Severity.ERROR }, supportingText = issues.firstOrNull { it.field == field }?.let { { Text(it.message) } })
    }
    @Composable fun toggle(label: String, v: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f)); Switch(v, f)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Settings")
        Banner(FTP_WARNING)
        Panel {
            Text("Console", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(c.host, { c = c.copy(host = it) }, Modifier.fillMaxWidth(), label = { Text("PS5 IP address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = issues.any { it.field == "host" && it.severity == Severity.ERROR }, supportingText = issues.firstOrNull { it.field == "host" }?.let { { Text(it.message) } })
            num("FTP port", c.port, { c = c.copy(port = it) }, field = "port")
            OutlinedTextField(c.username, { c = c.copy(username = it) }, Modifier.fillMaxWidth(), label = { Text("FTP username (blank = anonymous)") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("FTP password (stored encrypted)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(c.destinationDir, { c = c.copy(destinationDir = it) }, Modifier.fillMaxWidth(), label = { Text("Default destination folder") }, singleLine = true,
                isError = issues.any { it.field == "destination" }, supportingText = issues.firstOrNull { it.field == "destination" }?.let { { Text(it.message) } })
            toggle("Route by file type (.pkg → /data/pkg, disc images → /data/ShadowMount)", c.routingEnabled) { c = c.copy(routingEnabled = it) }
        }
        Panel {
            Text("Transfer engine", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { c = c.copy(workers = Presets.CONSERVATIVE.workers, chunkMiB = (Presets.CONSERVATIVE.chunkSizeBytes shr 20).toInt(), bufferMiB = (Presets.CONSERVATIVE.bufferBytes shr 20).toInt(), retries = Presets.CONSERVATIVE.maxRetries) }, label = { Text("Conservative") })
                AssistChip(onClick = { c = c.copy(workers = Presets.BALANCED.workers, chunkMiB = (Presets.BALANCED.chunkSizeBytes shr 20).toInt(), bufferMiB = (Presets.BALANCED.bufferBytes shr 20).toInt(), retries = Presets.BALANCED.maxRetries) }, label = { Text("Balanced") })
                AssistChip(onClick = { c = c.copy(workers = Presets.TURBO.workers, chunkMiB = (Presets.TURBO.chunkSizeBytes shr 20).toInt(), bufferMiB = (Presets.TURBO.bufferBytes shr 20).toInt(), retries = Presets.TURBO.maxRetries) }, label = { Text("Turbo") })
            }
            num("Download workers (parallel ranges)", c.workers, { c = c.copy(workers = it) }, field = "workers")
            num("Chunk size", c.chunkMiB, { c = c.copy(chunkMiB = it) }, "MiB", "chunk")
            num("Memory buffer", c.bufferMiB, { c = c.copy(bufferMiB = it) }, "MiB", "buffer")
            num("Retries", c.retries, { c = c.copy(retries = it) }, field = "retries")
            num("Connection timeout", c.connectTimeoutSec, { c = c.copy(connectTimeoutSec = it) }, "s", "timeout")
            Text("Workers download different parts of the file at once, but a single ordered writer sends them to the console, so the file is never written out of order. Actual speed depends on your network: use Diagnostics to measure it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Text("Behaviour", style = MaterialTheme.typography.titleSmall)
            toggle("Replace existing files by default", c.overwriteByDefault) { c = c.copy(overwriteByDefault = it) }
            toggle("Allow links on my local network (e.g. a NAS)", c.allowLanSources) { c = c.copy(allowLanSources = it) }
            toggle("Resume automatically when the network returns", c.autoResumeOnNetwork) { c = c.copy(autoResumeOnNetwork = it) }
            toggle("Notify when transfers finish", c.notifications) { c = c.copy(notifications = it) }
            Text("Theme", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.values().forEach { m -> FilterChip(selected = c.theme == m, onClick = { c = c.copy(theme = m) }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
        }
        Panel {
            Text("Console extraction (optional)", style = MaterialTheme.typography.titleSmall)
            Text("To unpack archives on the console you must supply your own extractor payload (unrar_ps5.elf) and run a payload loader on the console. This app sends the file to that loader, one time per transfer, after you confirm. It is not bundled and the phone never runs it.", fontSize = 12.sp)
            num("Loader port", c.payloadPort, { c = c.copy(payloadPort = it) }, field = "payloadPort")
            toggle("Delete the archive on the console after extracting", c.deleteArchiveAfterExtract) { c = c.copy(deleteArchiveAfterExtract = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { payloadPicker.launch(arrayOf("*/*")) }) { Text(if (payloadOk) "Replace payload…" else "Import payload…") }
                if (payloadOk) TextButton(onClick = { app.settings.removePayload(); payloadOk = false; payloadMsg = "Removed." }) { Text("Remove") }
            }
            Text(payloadMsg ?: if (payloadOk) "A payload is imported." else "No payload imported.", fontSize = 12.sp)
        }
        issues.filter { it.severity == Severity.WARNING }.forEach { Banner(it.message) }
        if (savedMsg) Text("Saved.", color = Good)
        Button(onClick = {
            app.settings.setPassword(password)
            issues = app.settings.save(c)
            savedMsg = issues.none { it.severity == Severity.ERROR }
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save settings") }
        Text("Direct Stream for PlayStation 5 is MIT licensed. Android port based on ChillQuant/direct-stream-ps5 v2.9.0.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
