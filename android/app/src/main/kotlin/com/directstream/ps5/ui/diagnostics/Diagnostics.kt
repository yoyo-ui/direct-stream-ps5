package com.directstream.ps5.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.diag.*
import com.directstream.ps5.core.http.HttpSource
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.util.Redaction
import com.directstream.ps5.ui.*
import com.directstream.ps5.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagUi(
    val running: String? = null, val steps: List<DiagStep> = emptyList(), val upload: BenchResult? = null, val remote: BenchResult? = null,
    val ranges: Boolean? = null, val error: String? = null
)

class DiagVm(private val app: DirectStreamApp) : ViewModel() {
    private val _ui = MutableStateFlow(DiagUi())
    val ui: StateFlow<DiagUi> = _ui.asStateFlow()

    private fun diag(): Diagnostics {
        val cfg = app.settings.config.value
        return Diagnostics(cfg.ftp(app.settings.password()), HttpSource(UrlPolicy(cfg.allowLanSources), cfg.connectTimeoutSec * 1000, 30_000))
    }
    private fun run(label: String, block: suspend () -> Unit) {
        if (!app.settings.config.value.configured) { _ui.update { it.copy(error = "Set the console's IP address in Settings first.") }; return }
        _ui.update { it.copy(running = label, error = null) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { _ui.update { it.copy(error = Redaction.redactText(e.message ?: "Test failed")) } }
            _ui.update { it.copy(running = null) }
        }
    }

    fun console() = run("Testing the console") {
        val dir = app.settings.config.value.destinationDir
        val steps = withContext(Dispatchers.IO) { diag().testConsole("/", dir) }
        _ui.update { it.copy(steps = steps) }
    }
    fun source(url: String) = run("Checking the link") {
        val steps = withContext(Dispatchers.IO) { diag().testSource(url.trim()) }
        _ui.update { it.copy(steps = steps, ranges = steps.firstOrNull { s -> s.name == "Range requests" }?.let { s -> s.status == StepStatus.PASS }) }
    }
    fun benchUpload() = run("Uploading 32 MB test data") {
        val r = withContext(Dispatchers.IO) { diag().benchmarkUpload(app.settings.config.value.destinationDir) }
        _ui.update { it.copy(upload = r) }
    }
    fun benchRemote(url: String) = run("Streaming 32 MB from the link") {
        val r = withContext(Dispatchers.IO) { diag().benchmarkRemote(url.trim(), emptyMap(), app.settings.config.value.destinationDir) }
        _ui.update { it.copy(remote = r) }
    }
}

@Composable
fun DiagnosticsScreen() {
    val vm = appViewModel { DiagVm(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val busy = ui.running != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Diagnostics")
        Text("Every result below comes from a real test just now. Nothing is estimated.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel {
            Text("Console", style = MaterialTheme.typography.titleSmall)
            Text("Connects, logs in, lists a folder and proves write access with a tiny file that is deleted again.", fontSize = 12.sp)
            Button(onClick = vm::console, enabled = !busy) { Text("Test console") }
        }
        Panel {
            Text("Download link", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTP / HTTPS link") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.source(url) }, enabled = !busy && url.isNotBlank()) { Text("Check link") }
                OutlinedButton(onClick = { vm.benchRemote(url) }, enabled = !busy && url.isNotBlank()) { Text("Benchmark") }
            }
        }
        Panel {
            Text("Phone → console speed", style = MaterialTheme.typography.titleSmall)
            Text("Uploads 32 MB of test data to your destination folder, then deletes it.", fontSize = 12.sp)
            Button(onClick = vm::benchUpload, enabled = !busy) { Text("Run upload benchmark") }
        }
        ui.running?.let { Text(it + "…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        ui.error?.let { Banner(it, Bad) }
        if (ui.steps.isNotEmpty()) Panel {
            ui.steps.forEach { s ->
                val c = when (s.status) { StepStatus.PASS -> Good; StepStatus.WARN -> Warn; StepStatus.FAIL -> Bad; StepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant }
                Text("${s.status.name}  ${s.name}  (${s.durationMs} ms)", color = c, fontSize = 13.sp); Text(s.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.upload?.let { Metric("Upload to console", "${"%.1f".format(it.megabytesPerSecond)} MB/s (${Fmt.bytes(it.bytes)} in ${it.durationMs} ms)") }
        ui.remote?.let { Metric("Link → console", "${"%.1f".format(it.megabytesPerSecond)} MB/s (${Fmt.bytes(it.bytes)} in ${it.durationMs} ms)") }
        val advice = Diagnostics.recommend(ui.upload, ui.remote, ui.ranges)
        if (advice.isNotEmpty()) Panel { Text("What this means", style = MaterialTheme.typography.titleSmall); advice.forEach { Text("• $it", fontSize = 13.sp) } }
    }
}
