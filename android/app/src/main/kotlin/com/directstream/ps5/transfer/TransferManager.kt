package com.directstream.ps5.transfer

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.directstream.ps5.core.archive.ArchiveStrategy
import com.directstream.ps5.core.console.ConsoleExtractor
import com.directstream.ps5.core.console.ExtractionResult
import com.directstream.ps5.core.console.UnrarConfig
import com.directstream.ps5.core.engine.*
import com.directstream.ps5.core.ftp.FtpClient
import com.directstream.ps5.core.http.HttpSource
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.resolver.Resolver
import com.directstream.ps5.core.util.Redaction
import com.directstream.ps5.data.CredentialStore
import com.directstream.ps5.data.JobRecord
import com.directstream.ps5.data.JobStore
import com.directstream.ps5.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class Live(val phase: Phase, val bytes: Long, val total: Long?, val bps: Double, val mode: TransferMode?, val message: String?)

data class Stats(
    val active: Int = 0, val queued: Int = 0, val bytesPerSecond: Double = 0.0,
    val totalTransferred: Long = 0, val etaSeconds: Long? = null
)

/**
 * Owns the queue. It persists every state change (so the app can be killed at any time), runs the engine
 * from the core module, reacts to network loss, and decides what a restarted process may safely resume.
 */
class TransferManager(
    private val app: Application,
    val store: JobStore,
    val settings: SettingsRepository,
    private val secrets: CredentialStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Running(val control: TransferControl, val job: Job)
    private val running = ConcurrentHashMap<String, Running>()
    private val waitingForNetwork = ConcurrentHashMap.newKeySet<String>()
    private val updateLock = Mutex()
    private val pumpLock = Any()
    @Volatile private var networkUp = true
    @Volatile private var lastNetworkLoss = 0L

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    val stats: StateFlow<Stats> = combine(store.jobs, _live) { jobs, live ->
        val speed = live.values.sumOf { it.bps }
        val remaining = jobs.filter { it.active }.sumOf { j -> ((j.total ?: 0L) - (live[j.id]?.bytes ?: j.bytesDone)).coerceAtLeast(0) }
        Stats(
            active = jobs.count { it.active }, queued = jobs.count { it.state == JobState.QUEUED }, bytesPerSecond = speed,
            totalTransferred = jobs.filter { it.state == JobState.COMPLETED }.sumOf { it.bytesDone } + jobs.filter { it.active }.sumOf { live[it.id]?.bytes ?: it.bytesDone },
            etaSeconds = if (speed > 50_000 && remaining > 0) (remaining / speed).toLong() else null
        )
    }.stateIn(scope, SharingStarted.Eagerly, Stats())

    val hasWork: StateFlow<Boolean> = store.jobs.map { l -> l.any { it.active || it.state == JobState.QUEUED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            store.refresh()
            recover()
            watchNetwork()
            pump()
        }
    }

    // ------------------------------------------------------------------ recovery

    /** After the process was killed: nothing is "running" any more, so decide for each job what is safe. */
    private suspend fun recover() {
        for (r in store.jobs.value) {
            val perm = r.remote || (r.localUri?.let { LocalSources.hasPersistedPermission(app, it) } ?: false)
            val p = PersistedJob(r.state, r.remote, r.rangesOk, r.hasValidator, r.seekable, perm, r.bytesDone > 0 && r.partPath != null)
            when (JobRecovery.onProcessRestart(p)) {
                RecoveryAction.PAUSE_RESUMABLE -> store.upsert(r.copy(state = JobState.PAUSED, recoverable = true, restartRequired = false, detail = "Interrupted. Resumes from the partial file on the console."))
                RecoveryAction.PAUSE_RESTART_REQUIRED -> store.upsert(r.copy(state = JobState.PAUSED, recoverable = true, restartRequired = true, detail = "Interrupted. Must restart from the beginning: " + (JobRecovery.restartReason(p) ?: "partial file not verified.")))
                RecoveryAction.FAIL_SOURCE_LOST -> store.upsert(r.copy(state = JobState.FAILED, errorKind = "SOURCE_LOST", error = "Access to the selected file was lost. Add it again.", recoverable = false))
                RecoveryAction.UNCHANGED -> {}
            }
        }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        networkUp = cm.activeNetwork != null
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { networkUp = false; lastNetworkLoss = System.currentTimeMillis() }
            override fun onAvailable(network: Network) {
                networkUp = true
                if (settings.config.value.autoResumeOnNetwork) waitingForNetwork.toList().forEach { id -> waitingForNetwork.remove(id); resume(id) }
            }
        })
    }

    // ------------------------------------------------------------------ queue operations

    fun enqueue(r: JobRecord) {
        scope.launch { store.upsert(r.copy(state = JobState.QUEUED)); pump(); ensureService() }
    }

    fun pause(id: String) { running[id]?.control?.pause() }
    fun pauseAll() { running.values.forEach { it.control.pause() } }

    fun resume(id: String) {
        scope.launch {
            val r = store.get(id) ?: return@launch
            if (r.state != JobState.PAUSED && !(r.state == JobState.FAILED && r.recoverable)) return@launch
            store.upsert(r.copy(state = JobState.QUEUED, error = null, errorKind = null)); pump(); ensureService()
        }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String, deletePartial: Boolean) {
        val run = running[id]
        if (run != null) { run.control.cancel(deletePartial); if (run.control.reason == null) run.job.cancel(); return }
        scope.launch {
            val r = store.get(id) ?: return@launch
            store.upsert(r.copy(state = JobState.CANCELLED, detail = null))
            if (deletePartial && r.partPath != null) deleteRemote(r.partPath)
        }
    }

    fun cancelAll() { store.jobs.value.filter { it.active || it.state == JobState.QUEUED || it.state == JobState.PAUSED }.forEach { cancel(it.id, false) } }

    fun remove(ids: Collection<String>) { scope.launch { store.delete(ids.filter { id -> store.get(id)?.let { !it.active } ?: true }) } }
    fun clearHistory() { remove(store.jobs.value.filter { it.finished }.map { it.id }) }

    private suspend fun deleteRemote(path: String) = withContext(Dispatchers.IO) {
        runCatching {
            val c = FtpClient(settings.config.value.ftp(settings.password())); c.connect(); c.login()
            if (c.fileExists(path)) c.delete(path); c.quit()
        }
    }

    private fun pump() {
        synchronized(pumpLock) {
            val max = settings.config.value.maxParallelJobs.coerceIn(1, 3)
            val next = store.jobs.value.filter { it.state == JobState.QUEUED && !running.containsKey(it.id) }.sortedBy { it.createdAt }
            for (r in next) {
                if (running.size >= max) break
                start(r.id)
            }
        }
    }

    private fun ensureService() {
        try { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
        catch (e: IllegalStateException) { Log.w(TAG, "Foreground service could not start from the background: ${e.message}") }
    }

    // ------------------------------------------------------------------ running a job

    private suspend fun update(id: String, f: (JobRecord) -> JobRecord) = updateLock.withLock { store.get(id)?.let { store.upsert(f(it)) } }

    private fun start(id: String) {
        val control = TransferControl()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { execute(id, control) }
            finally {
                withContext(NonCancellable) {
                    running.remove(id); _live.update { it - id }
                    update(id) { r -> if (r.active) r.copy(state = JobState.CANCELLED) else r }
                }
                pump()
            }
        }
        running[id] = Running(control, job)
        job.start()
    }

    private fun parseHeaders(text: String?): Map<String, String> =
        text.orEmpty().lines().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()

    private suspend fun execute(id: String, control: TransferControl) {
        val startedAt = System.currentTimeMillis()
        var rec = store.get(id) ?: return
        val cfg = settings.config.value
        update(id) { it.copy(state = JobState.PREPARING, error = null, errorKind = null, detail = null) }
        if (!cfg.configured) { fail(id, "SETTINGS", "Set your console's IP address in Settings first.", false); return }

        val consoleExtract = rec.strategy == ArchiveStrategy.EXTRACT_ON_CONSOLE.name
        val source: SourceSpec = try {
            if (rec.remote) SourceSpec.Remote(secrets.decrypt(rec.urlEnc) ?: throw IllegalStateException("Stored link could not be decrypted"), parseHeaders(secrets.decrypt(rec.headersEnc)))
            else withContext(Dispatchers.IO) { LocalSources.open(app, Uri.parse(rec.localUri)) }
        } catch (e: Exception) { fail(id, "SOURCE_LOST", e.message ?: "Cannot open the source", false); return }

        if (source is SourceSpec.Local) update(id) { it.copy(identity = source.identity, seekable = source.seekable, length = source.length) }

        val hint = if (!rec.restartRequired && rec.bytesDone > 0 && rec.partPath != null)
            ResumeHint(rec.etag, rec.lastModified, rec.length, rec.identity) else null
        val uploadDir = if (consoleExtract) UnrarConfig.DIR else rec.destDir
        val req = TransferRequest(rec.id, source, uploadDir, rec.fileName, rec.overwrite, hint)

        val httpPolicy = UrlPolicy(cfg.allowLanSources)
        val engine = TransferEngine(cfg.ftp(settings.password()), HttpSource(httpPolicy, cfg.connectTimeoutSec * 1000, 30_000),
            memoryBudget, cfg.engine()) { line -> Log.d(TAG, Redaction.redactText(line)) }

        var lastPersist = 0L
        val outcome = engine.run(req, control,
            onResolved = { rs ->
                scope.launch { update(id) { it.copy(fileName = rs.fileName, length = rs.length ?: it.length, total = rs.length ?: it.total, etag = rs.etag, lastModified = rs.lastModified,
                    rangesOk = rs.acceptsRanges, partPath = rs.partPath, finalPath = rs.finalPath, detail = rs.notes.firstOrNull()) } }
            },
            onProgress = { p ->
                _live.update { it + (id to Live(p.phase, p.bytesDone, p.totalBytes, p.bytesPerSecond, p.mode, p.message)) }
                val now = System.currentTimeMillis()
                if (now - lastPersist > 2000) {
                    lastPersist = now
                    scope.launch { update(id) { it.copy(state = when (p.phase) { Phase.PREPARING -> JobState.PREPARING; Phase.TRANSFERRING -> JobState.TRANSFERRING
                        Phase.VERIFYING -> JobState.VERIFYING; Phase.FINALIZING -> JobState.FINALIZING }, bytesDone = p.bytesDone, total = p.totalBytes ?: it.total, mode = p.mode?.name, detail = p.message) } }
                }
            })

        when (outcome) {
            is TransferOutcome.Completed -> {
                update(id) { it.copy(state = JobState.COMPLETED, bytesDone = outcome.bytes, total = outcome.bytes, verification = outcome.verification.description,
                    mode = outcome.mode.name, avgBps = outcome.averageBytesPerSecond, finalPath = outcome.finalPath, error = null, detail = null, recoverable = false) }
                if (consoleExtract) extractOnConsole(id, outcome.finalPath.substringAfterLast('/'), cfg)
                else Notifications.done(app, rec.displayName, true, null)
            }
            is TransferOutcome.Paused -> update(id) { it.copy(state = JobState.PAUSED, bytesDone = outcome.bytesDone, recoverable = true, detail = "Paused. Partial file kept on the console.") }
            is TransferOutcome.Cancelled -> update(id) { it.copy(state = JobState.CANCELLED, detail = if (outcome.partialDeleted) "Partial file deleted." else "Partial file kept on the console.") }
            is TransferOutcome.Failed -> {
                val lostNetwork = lastNetworkLoss >= startedAt || !networkUp
                if (outcome.error.recoverable && lostNetwork && cfg.autoResumeOnNetwork) {
                    waitingForNetwork += id
                    update(id) { it.copy(state = JobState.PAUSED, recoverable = true, detail = "Waiting for the network to come back…", error = null) }
                } else {
                    val restart = outcome.error.kind == ErrorKind.SOURCE_CHANGED || outcome.error.kind == ErrorKind.CHECKSUM_MISMATCH
                    update(id) { it.copy(state = JobState.FAILED, error = Redaction.redactText(outcome.error.message), errorKind = outcome.error.kind.name,
                        recoverable = outcome.error.recoverable, restartRequired = restart, bytesDone = if (restart) 0 else it.bytesDone, detail = null) }
                    Notifications.done(app, rec.displayName, false, outcome.error.message)
                }
            }
        }
    }

    private suspend fun extractOnConsole(id: String, archiveName: String, cfg: com.directstream.ps5.data.AppConfig) {
        val rec = store.get(id) ?: return
        if (!settings.payloadImported()) { fail(id, "EXTRACTION", "No extractor payload has been imported (Settings → Console extraction).", false); return }
        update(id) { it.copy(state = JobState.FINALIZING, detail = "Starting extraction on the console") }
        val password = secrets.decrypt(rec.passwordEnc) ?: Resolver.explicitPasswordHint(archiveName)
        val result = ConsoleExtractor(cfg.ftp(settings.password())).extract(
            archiveName, rec.extractDir ?: cfg.destinationDir, password, cfg.deleteArchiveAfterExtract,
            withContext(Dispatchers.IO) { settings.payloadFile.readBytes() }, cfg.payloadPort
        ) { status -> scope.launch { update(id) { it.copy(detail = status) } } }
        when (result) {
            is ExtractionResult.Success -> { update(id) { it.copy(state = JobState.COMPLETED, detail = result.detail + (result.finalPath?.let { " → $it" } ?: "")) }; Notifications.done(app, rec.displayName, true, null) }
            is ExtractionResult.Failure -> fail(id, "EXTRACTION", result.reason, false)
        }
    }

    private suspend fun fail(id: String, kind: String, message: String, recoverable: Boolean) {
        update(id) { it.copy(state = JobState.FAILED, errorKind = kind, error = Redaction.redactText(message), recoverable = recoverable) }
    }

    /** One budget for the whole app, capped by what the device can spare. */
    private val memoryBudget: MemoryBudget by lazy {
        val deviceCap = Runtime.getRuntime().maxMemory() / 4
        MemoryBudget(minOf(settings.config.value.bufferMiB.toLong() shl 20, deviceCap).coerceAtLeast(8L shl 20))
    }

    companion object { private const val TAG = "DS5" }
}
