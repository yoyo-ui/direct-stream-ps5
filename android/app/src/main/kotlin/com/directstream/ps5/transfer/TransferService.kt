package com.directstream.ps5.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.directstream.ps5.DirectStreamApp
import com.directstream.ps5.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

object Notifications {
    const val CH_PROGRESS = "transfers"
    const val CH_RESULT = "results"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Active transfers", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun done(ctx: Context, name: String, ok: Boolean, error: String?) {
        if (!DirectStreamApp.from(ctx.applicationContext as android.app.Application).settings.config.value.notifications) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_RESULT).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(if (ok) "Transfer complete" else "Transfer failed").setContentText(if (ok) name else "$name: ${error.orEmpty().take(120)}")
            .setAutoCancel(true).setContentIntent(open(ctx)).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(name.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/**
 * Keeps long, user-started transfers alive with the screen off. Runs only while there is work, holds the Wi-Fi
 * and CPU locks only for that time, and stops itself afterwards. Not WorkManager on purpose: a multi-hour
 * continuous stream needs an immediate foreground service.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null
    private lateinit var manager: TransferManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = DirectStreamApp.from(application).manager
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> manager.pauseAll()
            ACTION_CANCEL_ALL -> manager.cancelAll()
        }
        ServiceCompat.startForeground(this, NOTIF_ID, build(manager.stats.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireLocks()
        scope.coroutineContext.cancelChildren()
        scope.launch {
            manager.stats.collectLatest { s ->
                if (!manager.hasWork.value) { shutDown(); return@collectLatest }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s))
                delay(1000)
            }
        }
        return START_NOT_STICKY   // the persisted queue, not the system, decides what runs after a restart
    }

    /** Android 15 limits dataSync foreground services to ~6 h; pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.pauseAll()
        shutDown()
    }

    private fun build(s: Stats): Notification {
        val text = when {
            s.active == 0 && s.queued > 0 -> "${s.queued} queued"
            s.active > 0 -> "%.1f MB/s".format(s.bytesPerSecond / 1e6) + (s.etaSeconds?.let { " · ${it / 60} min left" } ?: "") + (if (s.queued > 0) " · ${s.queued} queued" else "")
            else -> "Finishing…"
        }
        fun action(label: String, act: String, code: Int) = NotificationCompat.Action.Builder(0, label,
            PendingIntent.getService(this, code, Intent(this, TransferService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        return NotificationCompat.Builder(this, Notifications.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Streaming to PS5").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(Notifications.open(this))
            .addAction(action("Pause", ACTION_PAUSE_ALL, 1)).addAction(action("Cancel", ACTION_CANCEL_ALL, 2)).build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "directstream:wifi").apply { setReferenceCounted(false); acquire() }
        if (cpu == null) cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "directstream:cpu").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseLocks() {
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
        runCatching { cpu?.takeIf { it.isHeld }?.release() }; cpu = null
    }

    private fun shutDown() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); releaseLocks(); super.onDestroy() }

    companion object {
        const val NOTIF_ID = 4101
        const val ACTION_PAUSE_ALL = "com.directstream.ps5.PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.directstream.ps5.CANCEL_ALL"
    }
}
