package com.directstream.ps5.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.core.engine.JobState
import com.directstream.ps5.ui.theme.*

object Fmt {
    fun bytes(n: Long?): String {
        if (n == null) return "unknown"
        val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = n.toDouble(); var i = 0
        while (v >= 1000 && i < u.lastIndex) { v /= 1000; i++ }
        return if (i == 0) "$n B" else "%.2f %s".format(v, u[i])
    }
    fun speed(bps: Double) = "%.1f MB/s".format(bps / 1e6)
    fun eta(s: Long?): String = when {
        s == null -> "—"; s < 90 -> "${s}s"; s < 5400 -> "${s / 60} min"; else -> "%d h %02d min".format(s / 3600, (s % 3600) / 60)
    }
}

@Composable
inline fun <reified T : ViewModel> appViewModel(crossinline make: (DirectStreamApp) -> T): T {
    val app = LocalContext.current.applicationContext as DirectStreamApp
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = make(app) as V
    })
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Title(text: String) = Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))

@Composable
fun StateChip(state: JobState) {
    val (c, label) = when (state) {
        JobState.QUEUED -> Blue to "Queued"; JobState.PREPARING -> Blue to "Preparing"; JobState.TRANSFERRING -> Violet to "Transferring"
        JobState.VERIFYING -> Violet to "Verifying"; JobState.FINALIZING -> Violet to "Finalizing"; JobState.PAUSED -> Warn to "Paused"
        JobState.COMPLETED -> Good to "Completed"; JobState.FAILED -> Bad to "Failed"; JobState.CANCELLED -> Color.Gray to "Cancelled"
    }
    Surface(color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(label, color = c, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun Empty(title: String, hint: String) = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, fontWeight = FontWeight.SemiBold); Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
fun Banner(text: String, color: Color = Warn) = Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
}

const val FTP_WARNING = "FTP is not encrypted. Your console's login and the file contents cross your network in clear text. Only use this on a network you trust."
