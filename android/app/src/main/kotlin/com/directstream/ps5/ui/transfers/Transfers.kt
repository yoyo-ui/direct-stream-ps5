package com.directstream.ps5.ui.transfers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.directstream.ps5.BuildConfig
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.diag.ErrorReport
import com.directstream.ps5.core.engine.JobState
import com.directstream.ps5.data.JobRecord
import com.directstream.ps5.ui.*
import com.directstream.ps5.ui.theme.*

@Composable
fun TransfersScreen(onAdd: () -> Unit) {
    val app = LocalContext.current.applicationContext as DirectStreamApp
    val jobs by app.store.jobs.collectAsStateWithLifecycle()
    val live by app.manager.live.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<JobRecord?>(null) }
    var confirmCancel by remember { mutableStateOf<JobRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = if (tab == 0) jobs.filter { !it.finished } else jobs.filter { it.finished }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Title("Transfers"); Button(onClick = onAdd) { Text("Add") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Queue (${jobs.count { !it.finished }})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History (${jobs.count { it.finished }})") })
        }
        if (tab == 1 && shown.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
        if (shown.isEmpty()) Empty(if (tab == 0) "Queue is empty" else "No history yet", "Use Add to stream a link or a local file to your PS5.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(shown, key = { it.id }) { j ->
                val l = live[j.id]; val total = l?.total ?: j.total; val done = l?.bytes ?: j.bytesDone
                Panel(Modifier) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(j.fileName ?: j.displayName, maxLines = 1, modifier = Modifier.weight(1f)); StateChip(j.state)
                    }
                    Text(j.sourceLabel + " → " + j.destDir, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (!j.finished) {
                        if (total != null && total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        else if (j.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(buildString {
                            append("${Fmt.bytes(done)} / ${Fmt.bytes(total)}")
                            l?.let { if (it.bps > 0) append(" · ${Fmt.speed(it.bps)}"); it.mode?.let { m -> append(" · ${if (m.name.startsWith("PAR")) "parallel" else "single stream"}") } }
                        }, fontSize = 12.sp)
                    }
                    (l?.message ?: j.detail)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (j.state == JobState.FAILED) Text(j.error.orEmpty(), color = Bad, fontSize = 13.sp, maxLines = 3)
                    if (j.state == JobState.COMPLETED) j.verification?.let { Text(it, fontSize = 12.sp, color = Good) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            j.active -> TextButton(onClick = { app.manager.pause(j.id) }) { Text("Pause") }
                            j.state == JobState.PAUSED || (j.state == JobState.FAILED && j.recoverable) ->
                                TextButton(onClick = { app.manager.resume(j.id) }) { Text(if (j.restartRequired) "Restart" else "Resume") }
                            j.state == JobState.FAILED || j.state == JobState.CANCELLED -> TextButton(onClick = { app.manager.retry(j.id) }) { Text("Retry") }
                        }
                        if (!j.finished) TextButton(onClick = { confirmCancel = j }) { Text("Cancel") }
                        TextButton(onClick = { details = j }) { Text("Details") }
                        if (j.finished) TextButton(onClick = { app.manager.remove(listOf(j.id)) }) { Text("Remove") }
                    }
                }
            }
        }
    }

    confirmCancel?.let { j ->
        AlertDialog(onDismissRequest = { confirmCancel = null }, title = { Text("Cancel transfer?") },
            text = { Text("The partial file on your console can be kept (so you could resume later) or deleted.") },
            confirmButton = { TextButton(onClick = { app.manager.cancel(j.id, true); confirmCancel = null }) { Text("Cancel and delete partial") } },
            dismissButton = { TextButton(onClick = { app.manager.cancel(j.id, false); confirmCancel = null }) { Text("Cancel, keep partial") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear history?") },
        text = { Text("Removes the records only. Files on your console are not touched.") },
        confirmButton = { TextButton(onClick = { app.manager.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } })
    details?.let { DetailsDialog(it) { details = null } }
}

@Composable
private fun DetailsDialog(j: JobRecord, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val receipt = ErrorReport.build(ErrorReport.Input(
        BuildConfig.VERSION_NAME, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}",
        if (j.remote) j.sourceLabel.substringAfter("://").substringBefore('/') else null, j.errorKind ?: j.state.name, j.error ?: j.detail ?: "",
        j.state.name, j.bytesDone, j.total, j.mode, j.rangesOk, j.hasValidator, null))
    AlertDialog(onDismissRequest = onClose, title = { Text("Transfer details") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Source: ${j.sourceLabel}", fontSize = 13.sp); Text("Destination: ${j.finalPath ?: j.destDir}", fontSize = 13.sp)
            Text("Range requests: ${if (j.rangesOk) "yes" else "no"}   Resume validator: ${if (j.hasValidator) "yes" else "no"}", fontSize = 13.sp)
            j.verification?.let { Text("Verification: $it", fontSize = 13.sp) }
            j.avgBps?.let { Text("Average speed: ${Fmt.speed(it)}", fontSize = 13.sp) }
            if (j.state == JobState.FAILED) { Text("Error receipt (sanitised):", fontSize = 12.sp); Text(receipt, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } },
        confirmButton = { Row {
            if (j.state == JobState.FAILED) {
                TextButton(onClick = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("receipt", receipt)) }) { Text("Copy") }
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ErrorReport.githubIssueUrl("Android: ${j.errorKind}", receipt))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Report on GitHub") }
            }
            TextButton(onClick = onClose) { Text("Close") }
        } })
}
