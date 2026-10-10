package com.directstream.ps5.ui.transfers

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.archive.*
import com.directstream.ps5.core.engine.JobState
import com.directstream.ps5.core.http.HttpSource
import com.directstream.ps5.core.http.SourceInfo
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.routing.DestinationRouter
import com.directstream.ps5.core.util.Names
import com.directstream.ps5.core.util.Redaction
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

sealed interface Pre {
    object Idle : Pre
    object Running : Pre
    data class Ok(val info: SourceInfo) : Pre
    data class Error(val message: String) : Pre
}

data class AddUi(
    val local: Boolean = false, val url: String = "", val headers: String = "", val nameOverride: String = "",
    val destination: String = "", val destEdited: Boolean = false, val overwrite: Boolean = false,
    val pre: Pre = Pre.Idle, val localUri: Uri? = null, val localName: String? = null, val localSize: Long? = null, val localSeekable: Boolean = false,
    val strategy: ArchiveStrategy = ArchiveStrategy.SEND_ARCHIVE, val password: String = "", val error: String? = null
)

class AddVm(private val app: DirectStreamApp) : ViewModel() {
    private val _ui = MutableStateFlow(AddUi(destination = app.settings.config.value.destinationDir))
    val ui: StateFlow<AddUi> = _ui.asStateFlow()
    fun edit(f: (AddUi) -> AddUi) = _ui.update(f)

    private fun routedDestination(name: String?): String {
        val cfg = app.settings.config.value
        return if (cfg.routingEnabled && name != null) runCatching { DestinationRouter(defaultDirectory = cfg.destinationDir).directoryFor(name) }.getOrDefault(cfg.destinationDir) else cfg.destinationDir
    }

    fun parseHeaders(text: String) = text.lines().filter { it.contains(':') }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

    /** The original app verifies every link before it may enter the queue; this does the same with real network requests. */
    fun verify() {
        val u = _ui.value
        edit { it.copy(pre = Pre.Running, error = null) }
        viewModelScope.launch {
            val cfg = app.settings.config.value
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    HttpSource(UrlPolicy(cfg.allowLanSources), cfg.connectTimeoutSec * 1000, 30_000)
                        .preflight(u.url.trim(), parseHeaders(u.headers), u.nameOverride.ifBlank { null })
                }
            }
            r.onSuccess { info ->
                edit { cur -> cur.copy(pre = Pre.Ok(info), destination = if (cur.destEdited) cur.destination else routedDestination(info.fileName)) }
            }.onFailure { e -> edit { it.copy(pre = Pre.Error(Redaction.redactText(e.message ?: "Verification failed"))) } }
        }
    }

    fun pickLocal(uri: Uri) {
        val info = LocalSources.describe(app, uri)
        edit { it.copy(localUri = uri, localName = info.name, localSize = info.size, localSeekable = LocalSources.isSeekable(app, uri),
            destination = if (it.destEdited) it.destination else routedDestination(info.name), error = null) }
    }

    fun archiveOptions(name: String?) = if (name != null && ArchiveDetector.fromName(name) != null) ArchiveStrategies.options(false, app.settings.payloadImported()) else emptyList()

    fun canSubmit(): Boolean {
        val u = _ui.value
        return if (u.local) u.localUri != null else u.pre is Pre.Ok
    }

    fun submit(): Boolean {
        val u = _ui.value; val cfg = app.settings.config.value
        val dest = try { Names.normalizeRemoteDir(u.destination.ifBlank { cfg.destinationDir }) } catch (e: IllegalArgumentException) { edit { it.copy(error = e.message) }; return false }
        val id = UUID.randomUUID().toString()
        val rec = if (u.local) JobRecord(id = id, remote = false, displayName = u.localName ?: "file", sourceLabel = "Local file", localUri = u.localUri.toString(),
            fileName = u.nameOverride.ifBlank { null }, destDir = dest, overwrite = u.overwrite, strategy = u.strategy.name, passwordEnc = u.password.takeIf { it.isNotEmpty() }?.let(app.secrets::encrypt),
            extractDir = dest, total = u.localSize, seekable = u.localSeekable)
        else {
            val info = (u.pre as Pre.Ok).info
            JobRecord(id = id, remote = true, displayName = info.fileName, sourceLabel = Redaction.redactUrl(u.url.trim()), urlEnc = app.secrets.encrypt(u.url.trim()),
                headersEnc = u.headers.takeIf { it.isNotBlank() }?.let(app.secrets::encrypt), fileName = u.nameOverride.ifBlank { info.fileName }, destDir = dest, overwrite = u.overwrite,
                strategy = u.strategy.name, passwordEnc = u.password.takeIf { it.isNotEmpty() }?.let(app.secrets::encrypt), extractDir = dest, total = info.contentLength, length = info.contentLength,
                rangesOk = info.acceptsRanges, etag = info.strongEtag, lastModified = info.lastModified, state = JobState.QUEUED)
        }
        app.manager.enqueue(rec)
        return true
    }
}

@Composable
fun AddTransferScreen(onDone: () -> Unit) {
    val vm = appViewModel { AddVm(it) }
    val app = LocalContext.current.applicationContext as DirectStreamApp
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg by app.settings.config.collectAsStateWithLifecycle()
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmPayload by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.pickLocal(uri)
        }
    }
    val name = if (ui.local) ui.localName else (ui.pre as? Pre.Ok)?.info?.fileName
    val options = vm.archiveOptions(name)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Add transfer")
        if (!cfg.configured) Banner("Set your console's address in Settings before adding transfers.", Bad)
        Banner(FTP_WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !ui.local, onClick = { vm.edit { it.copy(local = false) } }, label = { Text("Link") })
            FilterChip(selected = ui.local, onClick = { vm.edit { it.copy(local = true) } }, label = { Text("Local file") })
        }
        if (!ui.local) {
            OutlinedTextField(ui.url, { v -> vm.edit { it.copy(url = v, pre = Pre.Idle) } }, Modifier.fillMaxWidth(), label = { Text("HTTP / HTTPS link") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), minLines = 2)
            OutlinedTextField(ui.headers, { v -> vm.edit { it.copy(headers = v, pre = Pre.Idle) } }, Modifier.fillMaxWidth(),
                label = { Text("Optional request headers (Name: value per line)") }, minLines = 1)
            Button(onClick = vm::verify, enabled = ui.url.isNotBlank() && ui.pre != Pre.Running) { Text(if (ui.pre == Pre.Running) "Checking…" else "Verify link") }
            when (val p = ui.pre) {
                Pre.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Pre.Error -> Banner(p.message, Bad)
                is Pre.Ok -> Panel {
                    val i = p.info
                    Text("Link verified", color = Good, fontSize = 15.sp)
                    Text("File: ${i.fileName}"); Text("Size: ${Fmt.bytes(i.contentLength)}")
                    Text("Range requests: ${if (i.acceptsRanges) "supported (parallel download possible)" else "not supported (single stream)"}")
                    Text("Resume after interruption: ${if (i.hasResumeValidator && i.acceptsRanges) "possible" else "would restart from zero"}")
                    Text(i.verificationNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    i.notes.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Pre.Idle -> {}
            }
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (ui.localUri == null) "Choose file…" else "Choose a different file…") }
            if (ui.localUri != null) Panel {
                Text("File: ${ui.localName}"); Text("Size: ${Fmt.bytes(ui.localSize)}")
                Text(if (ui.localSeekable) "Resume after interruption: possible" else "This file source can't seek: an interrupted transfer would restart from zero.", fontSize = 13.sp)
            }
        }
        OutlinedTextField(ui.nameOverride, { v -> vm.edit { it.copy(nameOverride = v) } }, Modifier.fillMaxWidth(), label = { Text("File name (optional override)") }, singleLine = true)
        OutlinedTextField(ui.destination, { v -> vm.edit { it.copy(destination = v, destEdited = true) } }, Modifier.fillMaxWidth(),
            label = { Text("Destination folder on the console") }, supportingText = { Text("Suggested from the file type; paths are not checked until the transfer starts.") }, singleLine = true)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(ui.overwrite, { on -> if (on) confirmOverwrite = true else vm.edit { it.copy(overwrite = false) } }); Spacer(Modifier.width(8.dp)); Text("Replace the file if it already exists")
        }
        if (options.isNotEmpty()) Panel {
            Text("This looks like an archive. What should happen to it?", fontSize = 14.sp)
            options.forEach { o ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = ui.strategy == o.strategy, enabled = o.available, onClick = { vm.edit { it.copy(strategy = o.strategy) } })
                    Column { Text(when (o.strategy) { ArchiveStrategy.SEND_ARCHIVE -> "Send as is"; ArchiveStrategy.EXTRACT_ON_DEVICE -> "Extract on this phone"; else -> "Extract on the console" })
                        Text(o.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (ui.strategy == ArchiveStrategy.EXTRACT_ON_CONSOLE) OutlinedTextField(ui.password, { v -> vm.edit { it.copy(password = v) } }, Modifier.fillMaxWidth(),
                label = { Text("Archive password (if any)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        ui.error?.let { Banner(it, Bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Button(enabled = cfg.configured && vm.canSubmit(), onClick = {
                if (ui.strategy == ArchiveStrategy.EXTRACT_ON_CONSOLE) confirmPayload = true else if (vm.submit()) onDone()
            }) { Text("Add to queue") }
        }
    }

    if (confirmOverwrite) AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("Replace existing file?") },
        text = { Text("If a file with this name already exists in the destination folder it will be deleted and replaced when the transfer finishes.") },
        confirmButton = { TextButton(onClick = { vm.edit { it.copy(overwrite = true) }; confirmOverwrite = false }) { Text("Allow replacing") } },
        dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Don't replace") } })
    if (confirmPayload) AlertDialog(onDismissRequest = { confirmPayload = false }, title = { Text("Send extractor to your console?") },
        text = { Text("After the upload, this app will send the extractor payload file you imported to ${cfg.host}:${cfg.payloadPort} (your console's payload loader) so it can unpack the archive. This phone never runs the payload itself.") },
        confirmButton = { TextButton(onClick = { confirmPayload = false; if (vm.submit()) onDone() }) { Text("Send and continue") } },
        dismissButton = { TextButton(onClick = { confirmPayload = false }) { Text("Back") } })
}
