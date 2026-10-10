package com.directstream.ps5

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.directstream.ps5.ui.dashboard.DashboardScreen
import com.directstream.ps5.ui.diagnostics.DiagnosticsScreen
import com.directstream.ps5.ui.files.ConsoleFilesScreen
import com.directstream.ps5.ui.settings.SettingsScreen
import com.directstream.ps5.ui.theme.DirectStreamTheme
import com.directstream.ps5.ui.transfers.AddTransferScreen
import com.directstream.ps5.ui.transfers.TransfersScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("dashboard", "Dashboard", Icons.Filled.Dashboard), Tab("transfers", "Transfers", Icons.Filled.SwapVert),
    Tab("files", "Console", Icons.Filled.Folder), Tab("diagnostics", "Diagnostics", Icons.Filled.Speed), Tab("settings", "Settings", Icons.Filled.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as DirectStreamApp
        setContent {
            val cfg by app.settings.config.collectAsStateWithLifecycle()
            DirectStreamTheme(cfg.theme) { Root() }
        }
    }
}

@Composable
private fun Root() {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            if (route != "add") NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = {
                        nav.navigate(t.route) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) })
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(pad)) {
            composable("dashboard") { DashboardScreen(onAdd = { nav.navigate("add") }, onNav = { nav.navigate(it) { launchSingleTop = true } }) }
            composable("transfers") { TransfersScreen(onAdd = { nav.navigate("add") }) }
            composable("add") { AddTransferScreen(onDone = { nav.popBackStack() }) }
            composable("files") { ConsoleFilesScreen() }
            composable("diagnostics") { DiagnosticsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
