package com.directstream.ps5.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.ftp.FtpClient
import com.directstream.ps5.ui.*
import com.directstream.ps5.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Conn {
    object Unknown : Conn
    object Testing : Conn
    data class Ok(val banner: String) : Conn
    data class Failed(val message: String) : Conn
}

class DashboardVm(private val app: DirectStreamApp) : ViewModel() {
    private val _conn = MutableStateFlow<Conn>(Conn.Unknown)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    /** A real connect + login against the configured console; nothing is assumed from the IP alone. */
    fun test() {
        val cfg = app.settings.config.value
        if (!cfg.configured) { _conn.value = Conn.Failed("Set the console's IP address in Settings."); return }
        _conn.value = Conn.Testing
        viewModelScope.launch {
            _conn.value = withContext(Dispatchers.IO) {
                val c = FtpClient(cfg.ftp(app.settings.password()))
                try { c.connect(); c.login(); Conn.Ok(c.banner.take(60)) }
                catch (e: Exception) { Conn.Failed(e.message ?: "Could not connect") }
                finally { runCatching { c.quit() }; c.close() }
            }
        }
    }
}

@Composable
fun DashboardScreen(onAdd: () -> Unit, onNav: (String) -> Unit) {
    val vm = appViewModel { DashboardVm(it) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as DirectStreamApp
    val cfg by app.settings.config.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val stats by app.manager.stats.collectAsStateWithLifecycle()
    val jobs by app.store.jobs.collectAsStateWithLifecycle()
    val live by app.manager.live.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Title("Direct Stream PS5") }
        item {
            Panel {
                Text("Console", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (cfg.configured) "${cfg.host}:${cfg.port}" else "Not configured", style = MaterialTheme.typography.titleMedium)
                when (val c = conn) {
                    Conn.Unknown -> Text("Not tested yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Conn.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is Conn.Ok -> Text("Connected and logged in. Server: ${c.banner}", color = Good)
                    is Conn.Failed -> Text(c.message, color = Bad)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::test, enabled = conn != Conn.Testing) { Text("Test connection") }
                    TextButton(onClick = { onNav("settings") }) { Text("Settings") }
                }
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Speed", Fmt.speed(stats.bytesPerSecond)); Metric("Transferred", Fmt.bytes(stats.totalTransferred)); Metric("ETA", Fmt.eta(stats.etaSeconds))
                }
                Text("${stats.active} active · ${stats.queued} queued", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add transfer") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNav("files") }) { Text("Files") }
            OutlinedButton(onClick = { onNav("diagnostics") }) { Text("Diagnostics") }
        } }
        val active = jobs.filter { it.active }
        if (active.isNotEmpty()) {
            item { Text("In progress", style = MaterialTheme.typography.titleSmall) }
            items(active, key = { it.id }) { j ->
                val l = live[j.id]; val total = l?.total ?: j.total; val done = l?.bytes ?: j.bytesDone
                Panel {
                    Text(j.fileName ?: j.displayName, maxLines = 1); StateChip(j.state)
                    if (total != null && total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${Fmt.bytes(done)} / ${Fmt.bytes(total)}" + (l?.let { " · ${Fmt.speed(it.bps)}" } ?: ""), fontSize = 12.sp)
                }
            }
        }
        val recent = jobs.filter { it.finished }.take(5)
        item { Text("Recent", style = MaterialTheme.typography.titleSmall) }
        if (recent.isEmpty()) item { Empty("Nothing yet", "Finished transfers appear here.") }
        items(recent, key = { it.id }) { j ->
            Panel { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(j.fileName ?: j.displayName, maxLines = 1, modifier = Modifier.weight(1f)); StateChip(j.state) } }
        }
    }
}

