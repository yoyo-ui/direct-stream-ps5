package com.directstream.ps5.ui.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.engine.JobState
import com.directstream.ps5.core.ftp.FtpClient
import com.directstream.ps5.core.ftp.FtpEntry
import com.directstream.ps5.core.util.Names
import com.directstream.ps5.data.JobRecord
import com.directstream.ps5.transfer.LocalSources
import com.directstream.ps5.ui.*
import com.directstream.ps5.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class FilesUi(val path: String = "/", val entries: List<FtpEntry> = emptyList(), val loading: Boolean = false, val error: String? = null, val loaded: Boolean = false)

/** Each operation opens its own short FTP session: simple, and never holds a console connection idle. */
class FilesVm(private val app: DirectStreamApp) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUi(path = app.settings.config.value.destinationDir))
    val ui: StateFlow<FilesUi> = _ui.asStateFlow()

    private suspend fun <T> withFtp(block: (FtpClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        val cfg = app.settings.config.value
        if (!cfg.configured) return@withContext Result.failure(IllegalStateException("Set the console's IP address in Settings."))
        val c = FtpClient(cfg.ftp(app.settings.password()))
        try { c.connect(); c.login(); Result.success(block(c)) } catch (e: Exception) { Result.failure(e) } finally { runCatching { c.quit() }; c.close() }
    }

    fun open(path: String) {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = withFtp { c -> c.list(Names.normalizeRemoteDir(path)).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
            _ui.update { r.fold({ e -> it.copy(path = Names.normalizeRemoteDir(path), entries = e, loading = false, loaded = true) },
                { err -> it.copy(loading = false, loaded = true, error = describe(err)) }) }
        }
    }
    fun up() { val p = _ui.value.path; open(p.substringBeforeLast('/').ifEmpty { "/" }) }
    fun refresh() = open(_ui.value.path)
    private fun child(name: String) = Names.joinRemote(_ui.value.path, name)

    private fun act(block: (FtpClient) -> Unit) { viewModelScope.launch { withFtp(block).onFailure { e -> _ui.update { it.copy(error = describe(e)) } }.onSuccess { refresh() } } }
    fun mkdir(name: String) = act { it.makeDirectories(child(name)) }
    fun rename(entry: FtpEntry, newName: String) = act { it.rename(child(entry.name), child(newName)) }
    fun delete(entry: FtpEntry) = act { if (entry.isDirectory) it.removeDirectory(child(entry.name)) else it.delete(child(entry.name)) }
    fun upload(uri: Uri) {
        val info = LocalSources.describe(app, uri)
        app.manager.enqueue(JobRecord(id = UUID.randomUUID().toString(), remote = false, displayName = info.name, sourceLabel = "Local file", localUri = uri.toString(),
            destDir = _ui.value.path, total = info.size, seekable = LocalSources.isSeekable(app, uri), state = JobState.QUEUED))
    }

    private fun describe(e: Throwable) = when (e) {
        is com.directstream.ps5.core.ftp.FtpAuthException -> "Login rejected by the console's FTP server. Check the username and password in Settings."
        is com.directstream.ps5.core.ftp.FtpConnectionException -> e.message ?: "Could not reach the console."
        is com.directstream.ps5.core.ftp.FtpCommandException -> if (e.reply.code == 550) "The console refused: permission denied or it doesn't exist (${e.reply.message})." else e.message ?: "FTP error"
        else -> e.message ?: "Unexpected error"
    }
}

@Composable
fun ConsoleFilesScreen() {
    val vm = appViewModel { FilesVm(it) }
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var mkdir by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<FtpEntry?>(null) }
    var delete by remember { mutableStateOf<FtpEntry?>(null) }
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.upload(uri) }
    }
    LaunchedEffect(Unit) { if (!ui.loaded) vm.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Title("Console files")
        Text(ui.path, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = vm::up, enabled = ui.path != "/") { Text("Up") }
            TextButton(onClick = vm::refresh) { Text("Refresh") }
            TextButton(onClick = { text = ""; mkdir = true }) { Text("New folder") }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Upload here") }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Banner(it, Bad) }
        if (ui.loaded && ui.error == null && ui.entries.isEmpty() && !ui.loading) Empty("Empty folder", "Nothing here yet.")
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(ui.entries, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable(enabled = e.isDirectory) { vm.open(Names.joinRemote(ui.path, e.name)) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text((if (e.isDirectory) "▸ " else "") + e.name, maxLines = 1)
                        Text(listOfNotNull(if (e.isDirectory) "folder" else Fmt.bytes(e.size), e.modified).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { text = e.name; rename = e }) { Text("Rename") }
                    TextButton(onClick = { delete = e }) { Text("Delete", color = Bad) }
                }
                HorizontalDivider()
            }
        }
    }
    if (mkdir) AlertDialog(onDismissRequest = { mkdir = false }, title = { Text("New folder") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { vm.mkdir(Names.sanitizeFileName(text, "new-folder")); mkdir = false }, enabled = text.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { mkdir = false }) { Text("Cancel") } })
    rename?.let { e -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("New name") }) },
        confirmButton = { TextButton(onClick = { vm.rename(e, Names.sanitizeFileName(text, e.name)); rename = null }, enabled = text.isNotBlank() && text != e.name) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } }) }
    delete?.let { e -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${e.name}?") },
        text = { Text(if (e.isDirectory) "The folder must be empty to be removed. This can't be undone." else "This permanently deletes the file from your console. This can't be undone.") },
        confirmButton = { TextButton(onClick = { vm.delete(e); delete = null }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
}
