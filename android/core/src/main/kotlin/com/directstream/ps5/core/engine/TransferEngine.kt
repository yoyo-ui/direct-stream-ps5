package com.directstream.ps5.core.engine

import com.directstream.ps5.core.ftp.*
import com.directstream.ps5.core.http.*
import com.directstream.ps5.core.net.UrlRejectedException
import com.directstream.ps5.core.util.Names
import com.directstream.ps5.core.util.Redaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

/**
 * source -> bounded RAM -> FTP data connection -> `<name>.<job>.ps5part` -> verify -> rename.
 *
 * Parallel mode downloads byte ranges concurrently but delivers them to ONE ordered FTP writer, so the
 * destination never sees concurrent or out-of-order writes. Memory is bounded by [MemoryBudget].
 */
class TransferEngine(
    private val ftpConfig: FtpConfig,
    private val http: HttpSource,
    private val budget: MemoryBudget,
    settings: EngineSettings = EngineSettings(),
    private val log: (String) -> Unit = {}
) {
    private val cfg = settings.sanitized()

    // ------------------------------------------------------------------------------- public entry

    suspend fun run(
        request: TransferRequest,
        control: TransferControl = TransferControl(),
        onResolved: (ResolvedSource) -> Unit = {},
        onProgress: (Progress) -> Unit = {}
    ): TransferOutcome = coroutineScope {
        val work = async(Dispatchers.IO) { Execution(request, control, onResolved, onProgress).execute() }
        control.attach(work)
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!coroutineContext.isActive) throw e         // the caller itself was cancelled
            work.join()
            when (control.reason) {
                StopReason.PAUSE -> TransferOutcome.Paused(control.lastPosition)
                else -> TransferOutcome.Cancelled(control.deletePartialOnCancel)
            }
        }
    }

    // ------------------------------------------------------------------------------- implementation

    private class Killers {
        private val set = ConcurrentHashMap.newKeySet<Closeable>()
        fun <T : Closeable> track(c: T): T { set += c; return c }
        fun untrack(c: Closeable) { set -= c }
        fun killAll() = set.forEach { runCatching { it.close() } }
    }

    private class SourceStream(val input: InputStream, private val owner: Closeable, val remaining: Long?) : Closeable {
        override fun close() { owner.close() }
    }

    private class Failure(val error: TransferError, val partPath: String?) : Exception(error.message)

    private inner class Execution(
        val req: TransferRequest,
        val control: TransferControl,
        val onResolved: (ResolvedSource) -> Unit,
        val onProgress: (Progress) -> Unit
    ) {
        val killers = Killers()
        val ftp = FtpClient(ftpConfig) { log(Redaction.redactText(it)) }
        val position = AtomicLong(0)          // absolute offset of next byte to be written
        @Volatile var phase = Phase.PREPARING
        @Volatile var mode: TransferMode? = null
        @Volatile var total: Long? = null
        @Volatile var message: String? = null
        var digest: MessageDigest? = null
        var parallelDisabled = false
        lateinit var src: ResolvedSource
        var remoteInfo: SourceInfo? = null
        var local: SourceSpec.Local? = null
        var remote: SourceSpec.Remote? = null
        var resumedFrom = 0L
        var sourceChangeChecked = false

        suspend fun execute(): TransferOutcome {
            val started = System.currentTimeMillis()
            return try {
                withKillSwitch {
                    val ticker = launch { progressLoop() }
                    try { doTransfer(started) } finally { ticker.cancel() }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onStopped() }
                throw e
            } catch (f: Failure) {
                withContext(NonCancellable) { ftp.close() }
                TransferOutcome.Failed(f.error, f.partPath)
            } catch (t: Throwable) {
                withContext(NonCancellable) { ftp.close() }
                TransferOutcome.Failed(map(t), if (this::src.isInitialized) src.partPath else null)
            }
        }

        private suspend fun <T> withKillSwitch(block: suspend CoroutineScope.() -> T): T = coroutineScope {
            val done = AtomicBoolean(false)
            val watcher = launch(Dispatchers.Default) {
                try { awaitCancellation() } finally { if (!done.get()) { ftp.abortNow(); killers.killAll() } }
            }
            try { block() } finally { done.set(true); watcher.cancel() }
        }

        /** Runs on pause/cancel: close connections, optionally delete the partial file. */
        private suspend fun onStopped() {
            control.lastPosition = position.get()
            ftp.abortNow(); ftp.close()
            if (control.reason == StopReason.CANCEL && control.deletePartialOnCancel && this::src.isInitialized) {
                runCatching {
                    withTimeout(10_000) {
                        withContext(Dispatchers.IO) {
                            val c = FtpClient(ftpConfig); c.connect(); c.login()
                            if (c.fileExists(src.partPath)) c.delete(src.partPath)
                            c.quit()
                        }
                    }
                }
            }
        }

        private suspend fun progressLoop() {
            var lastBytes = position.get(); var lastTime = System.nanoTime(); var ema = 0.0
            while (true) {
                delay(cfg.progressIntervalMs)
                val now = System.nanoTime(); val b = position.get()
                val inst = (b - lastBytes) * 1e9 / (now - lastTime).coerceAtLeast(1)
                ema = if (ema == 0.0) inst else ema * 0.7 + inst * 0.3
                lastBytes = b; lastTime = now; control.lastPosition = b
                onProgress(Progress(phase, b, total, if (phase == Phase.TRANSFERRING) ema else 0.0, mode, message))
            }
        }

        private fun emit() = onProgress(Progress(phase, position.get(), total, 0.0, mode, message))

        // -------------------------------------------------------------------- main flow

        suspend fun doTransfer(startedAt: Long): TransferOutcome {
            validateRequest()
            resolveSource()
            total = src.length
            onResolved(src)
            emit()

            io { ensureConnected() }
            io { ftp.makeDirectories(Names.normalizeRemoteDir(req.destinationDir)) }
            val finalExists = io { ftp.fileExists(src.finalPath) }
            if (finalExists && !req.overwrite)
                throw Failure(TransferError(ErrorKind.DESTINATION_EXISTS, "${src.finalPath} already exists on the console. Enable overwrite to replace it.", false), null)

            // ---- upload, with session-level recovery
            var attempt = 0
            var firstSession = true
            while (true) {
                try {
                    val offset = io { decideOffset(firstSession) }
                    firstSession = false
                    if (attempt == 0) resumedFrom = offset
                    uploadSession(offset)
                    break
                } catch (e: CancellationException) { throw e
                } catch (e: RangeIgnoredException) {
                    log("Server ignores ranges; switching to a single stream")
                    parallelDisabled = true
                    firstSession = false
                    continue
                } catch (e: SourceChangedException) {
                    withContext(NonCancellable) { runCatching { io { if (ftp.isConnected || tryReconnect()) ftp.delete(src.partPath) } } }
                    throw Failure(TransferError(ErrorKind.SOURCE_CHANGED, e.message ?: "The source file changed during the transfer; the partial upload was discarded.", false), null)
                } catch (e: IOException) {
                    if (e is FtpAuthException || e is UrlRejectedException) throw e
                    if (e is SourceException && !isTransient(e)) throw e
                    if (e is FtpCommandException && e.reply.permanent && e.command != "SIZE") throw e
                    if (attempt >= cfg.maxRetries) throw e
                    val wait = backoff(attempt++)
                    message = "Connection problem, retrying in ${wait / 1000}s (${attempt}/${cfg.maxRetries}): ${e.message}"
                    log(message!!)
                    delay(wait)
                    ftp.close()
                    message = null
                }
            }

            // ---- verify
            phase = Phase.VERIFYING; emit()
            val verification = io { verify() }

            // ---- finalize
            phase = Phase.FINALIZING; emit()
            io {
                if (ftp.fileExists(src.finalPath)) {
                    if (!req.overwrite) throw Failure(TransferError(ErrorKind.DESTINATION_EXISTS, "${src.finalPath} appeared on the console while uploading; the finished upload is kept as ${src.partPath}.", true), src.partPath)
                    ftp.delete(src.finalPath)
                }
                try { ftp.rename(src.partPath, src.finalPath) }
                catch (e: FtpCommandException) {
                    throw Failure(TransferError(ErrorKind.RENAME_FAILED, "The upload is complete and verified, but the console refused to rename the temporary file: ${e.message}. It remains as ${src.partPath}.", true), src.partPath)
                }
                runCatching { ftp.quit() }
            }
            return TransferOutcome.Completed(position.get(), src.finalPath, verification, mode ?: TransferMode.SINGLE_STREAM,
                System.currentTimeMillis() - startedAt, resumedFrom)
        }

        private fun validateRequest() {
            val problem = try { Names.normalizeRemoteDir(req.destinationDir); null } catch (e: IllegalArgumentException) { e.message }
            if (problem != null) throw Failure(TransferError(ErrorKind.INVALID_REQUEST, problem, false), null)
            if (req.jobId.isBlank() || req.jobId.any { !(it.isLetterOrDigit() || it == '-') })
                throw Failure(TransferError(ErrorKind.INVALID_REQUEST, "Invalid job id", false), null)
        }

        private suspend fun resolveSource() {
            val s = req.source
            val name: String; val length: Long?; var etag: String? = null; var lm: String? = null
            var ranges = false; var notes = emptyList<String>()
            when (s) {
                is SourceSpec.Remote -> {
                    val info = io { http.preflight(s.url, s.headers, req.fileName) }
                    remoteInfo = info; remote = s
                    name = info.fileName; length = info.contentLength; etag = info.strongEtag; lm = info.lastModified
                    ranges = info.acceptsRanges; notes = info.notes
                }
                is SourceSpec.Local -> {
                    local = s
                    name = Names.sanitizeFileName(req.fileName ?: s.displayName); length = s.length
                    ranges = s.seekable
                }
            }
            val fileName = Names.sanitizeFileName(req.fileName?.takeIf { it.isNotBlank() } ?: name)
            val dir = Names.normalizeRemoteDir(req.destinationDir)
            src = ResolvedSource(
                fileName, length, etag, lm, ranges,
                Names.joinRemote(dir, fileName),
                Names.joinRemote(dir, "$fileName.${req.jobId.take(12)}.ps5part"),
                notes
            )
        }

        private fun ensureConnected() {
            if (ftp.isConnected) return
            ftp.connect(); ftp.login()
        }
        private fun tryReconnect(): Boolean = runCatching { ensureConnected(); true }.getOrDefault(false)

        /** Can bytes already on the console be trusted as a prefix of the source? */
        private fun canResumeAcrossRuns(): Boolean {
            val h = req.resume ?: return false
            remoteInfo?.let { i ->
                if (!i.acceptsRanges || i.contentLength == null || h.length != i.contentLength) return false
                return (h.etag != null && i.strongEtag == h.etag) || (h.etag == null && h.lastModified != null && i.lastModified == h.lastModified)
            }
            local?.let { l -> return l.seekable && l.identity != null && l.identity == h.identity && l.length == h.length }
            return false
        }

        /** Within one run the source was already validated by its ranged responses, so only seekability matters. */
        private fun canResumeWithinRun(): Boolean =
            remoteInfo?.let { it.acceptsRanges && it.contentLength != null } ?: local?.seekable ?: false

        private fun decideOffset(first: Boolean): Long {
            ensureConnected()
            val resumable = if (first) canResumeAcrossRuns() else canResumeWithinRun()
            if (!resumable) return 0
            return when (val s = ftp.size(src.partPath)) {
                is SizeResult.Found -> {
                    val t = total
                    if (t != null && s.bytes > t) { ftp.delete(src.partPath); 0 } else s.bytes
                }
                else -> 0
            }
        }

        private fun backoff(attempt: Int): Long {
            val base = cfg.retryBaseDelayMs * 2.0.pow(attempt)
            return min(30_000.0, base).toLong() + (0..(cfg.retryBaseDelayMs / 4).toInt().coerceAtLeast(1)).random()
        }

        private fun isTransient(e: SourceException) =
            e.kind == SourceErrorKind.UNREACHABLE || e.kind == SourceErrorKind.RATE_LIMITED ||
                (e.kind == SourceErrorKind.HTTP_ERROR && (e.status ?: 0) >= 500)

        // -------------------------------------------------------------------- upload

        private suspend fun uploadSession(requestedOffset: Long) {
            val t = total
            if (t != null && requestedOffset == t) { position.set(t); phase = Phase.TRANSFERRING; mode = mode ?: TransferMode.SINGLE_STREAM; return }

            phase = Phase.TRANSFERRING
            if (t != null) {
                // Same pre-check as the original engine: refuse early if the console says it lacks space.
                val free = io { ftp.freeSpace(Names.normalizeRemoteDir(req.destinationDir)) }
                if (free != null && t - requestedOffset > free)
                    throw Failure(TransferError(ErrorKind.INSUFFICIENT_SPACE,
                        "The console reports ${free / 1_000_000} MB free but ${(t - requestedOffset) / 1_000_000} MB is needed.", false), null)
            }
            val (upload, offset) = io { openUpload(requestedOffset) }
            position.set(offset)
            // A running digest is only meaningful when it has seen every byte from zero.
            digest = if (offset == 0L && ftp.supports("XMD5")) MessageDigest.getInstance("MD5") else null

            val chooseParallel = cfg.parallel && !parallelDisabled && remoteInfo?.let { i ->
                i.acceptsRanges && i.contentLength != null && cfg.workers > 1 &&
                    (i.contentLength - offset) > cfg.chunkSizeBytes &&
                    (min(cfg.bufferBytes, budget.totalBytes) / cfg.chunkSizeBytes) >= 2
            } == true
            mode = if (chooseParallel) TransferMode.PARALLEL_ORDERED else TransferMode.SINGLE_STREAM
            emit()

            try {
                if (chooseParallel) parallelCopy(upload, offset) else singleCopy(upload, offset)
                io { upload.finish() }
            } catch (t: Throwable) {
                withContext(NonCancellable) { runCatching { upload.abort() } }
                throw t
            }
        }

        private fun openUpload(offset: Long): Pair<FtpClient.Upload, Long> {
            if (offset == 0L) return ftp.beginStore(src.partPath) to 0L
            try { return ftp.beginStore(src.partPath, FtpClient.StoreMode.STOR, offset) to offset }
            catch (e: FtpCommandException) { if (e.command != "REST") throw e }
            log("Server refused REST; trying APPE")
            try { return ftp.beginStore(src.partPath, FtpClient.StoreMode.APPE) to offset }
            catch (e: FtpCommandException) { if (e.reply.transient) throw e }
            log("Server can't resume uploads; restarting from zero")
            return ftp.beginStore(src.partPath) to 0L
        }

        private suspend fun write(upload: FtpClient.Upload, data: ByteArray, len: Int = data.size) {
            io {
                upload.out.write(data, 0, len)
                digest?.update(data, 0, len)
            }
            position.addAndGet(len.toLong())
        }

        private suspend fun singleCopy(upload: FtpClient.Upload, offset: Long) = coroutineScope {
            val block = cfg.ioBlockBytes
            val slots = (min(cfg.bufferBytes, budget.totalBytes) / block).toInt().coerceIn(2, 256)
            val lease = budget.acquire(slots.toLong() * block)
            try {
                val channel = Channel<ByteArray>(slots - 1)
                val expected = total
                val reader = launch(Dispatchers.IO) {
                    val stream = openSource(offset)
                    killers.track(stream)
                    try {
                        while (true) {
                            val buf = ByteArray(block)
                            val n = readAtLeastOne(stream.input, buf)
                            if (n < 0) break
                            channel.send(if (n == block) buf else buf.copyOf(n))
                        }
                    } finally { killers.untrack(stream); runCatching { stream.close() }; channel.close() }
                }
                for (b in channel) write(upload, b)
                reader.join()
                val end = expected
                if (end != null && position.get() != end)
                    throw IOException("Source ended early: got ${position.get()} of $end bytes")
            } finally { lease.release() }
        }

        private fun readAtLeastOne(s: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) return if (n == 0) -1 else n; n += r }
            return n
        }

        /** Opens the source at [offset]. Falls back to read-and-discard when ranged access isn't available. */
        private fun openSource(offset: Long): SourceStream {
            val l = local
            if (l != null) {
                val s = if (offset == 0L || l.seekable) l.openAt(offset) else l.openAt(0).also { skipFully(it, offset) }
                return SourceStream(s, s, l.length?.minus(offset))
            }
            val info = remoteInfo!!; val r = remote!!
            val body = when {
                offset == 0L -> http.openFull(info, r.headers)
                info.acceptsRanges -> http.openRange(info, r.headers, offset, null)
                else -> http.openFull(info, r.headers).also { skipFully(it.input, offset) }
            }
            return SourceStream(body.input, body, body.expectedLength)
        }

        private fun skipFully(s: InputStream, n: Long) {
            var left = n; val sink = ByteArray(64 * 1024)
            while (left > 0) { val r = s.read(sink, 0, min(sink.size.toLong(), left).toInt()); if (r < 0) throw IOException("Source shorter than resume offset"); left -= r }
        }

        private suspend fun parallelCopy(upload: FtpClient.Upload, offset: Long) {
            val info = remoteInfo!!; val r = remote!!
            val totalBytes = info.contentLength!!
            val chunkSize = cfg.chunkSizeBytes
            val window = (min(cfg.bufferBytes, budget.totalBytes) / chunkSize).toInt().coerceIn(2, 64)
            val lease = budget.acquire(window.toLong() * chunkSize)
            try {
                coroutineScope {
                    val chunks = ChunkPlanner.plan(totalBytes, chunkSize, offset)
                    val permits = Semaphore(window)
                    val slots = Semaphore(cfg.workers)
                    val queue = Channel<Deferred<ByteArray>>(Channel.UNLIMITED)
                    launch {
                        try {
                            for (c in chunks) {
                                permits.acquire()
                                queue.send(async(Dispatchers.IO) { slots.withPermit { fetchChunk(info, r, c) } })
                            }
                        } finally { queue.close() }
                    }
                    for (d in queue) {
                        val data = d.await()
                        write(upload, data)
                        permits.release()
                    }
                }
            } finally { lease.release() }
            if (position.get() != totalBytes) throw IOException("Parallel download ended at ${position.get()} of $totalBytes bytes")
        }

        private suspend fun fetchChunk(info: SourceInfo, r: SourceSpec.Remote, c: Chunk): ByteArray {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var body: HttpSource.Body? = null
                var handle: Closeable? = null
                try {
                    body = http.openRange(info, r.headers, c.start, c.endInclusive)
                    handle = killers.track(body)
                    val out = ByteArray(c.length.toInt())
                    var n = 0
                    while (n < out.size) {
                        val k = body.input.read(out, n, out.size - n)
                        if (k < 0) throw IOException("Chunk ${c.index} ended early at $n of ${out.size} bytes")
                        n += k
                    }
                    return out
                } catch (e: CancellationException) { throw e
                } catch (e: SourceChangedException) { throw e
                } catch (e: RangeIgnoredException) { throw e
                } catch (e: UrlRejectedException) { throw e
                } catch (e: SourceException) { if (!isTransient(e)) throw e; if (attempt >= cfg.maxRetries) throw e
                } catch (e: IOException) { if (attempt >= cfg.maxRetries) throw e
                } finally { handle?.let { killers.untrack(it) }; body?.close() }
                delay(backoff(attempt++))
            }
        }

        // -------------------------------------------------------------------- verify

        private fun verify(): Verification {
            ensureConnected()
            val sent = position.get()
            val expected = src.length ?: sent
            val remoteSize = ftp.size(src.partPath)

            when (remoteSize) {
                is SizeResult.Found -> if (remoteSize.bytes != expected)
                    throw Failure(TransferError(ErrorKind.INCOMPLETE_UPLOAD,
                        "The console has ${remoteSize.bytes} of $expected bytes. The partial file was kept so the transfer can be resumed.", true), src.partPath)
                SizeResult.NotFound -> throw Failure(TransferError(ErrorKind.INCOMPLETE_UPLOAD, "The temporary file is missing on the console after upload.", true), src.partPath)
                SizeResult.Unsupported -> if (sent != expected)
                    throw Failure(TransferError(ErrorKind.INCOMPLETE_UPLOAD, "Sent $sent of $expected bytes.", true), src.partPath)
            }
            digest?.let { md ->
                val local = md.digest().joinToString("") { "%02x".format(it) }
                val remoteHash = runCatching { ftp.checksum("XMD5", src.partPath) }.getOrNull()
                if (remoteHash != null) {
                    if (remoteHash == local) return Verification.CHECKSUM_MATCH
                    runCatching { ftp.delete(src.partPath) }
                    throw Failure(TransferError(ErrorKind.CHECKSUM_MISMATCH, "Checksum mismatch: the uploaded file is corrupt and was discarded.", false), null)
                }
            }
            return when {
                remoteSize is SizeResult.Found && src.length != null -> Verification.SIZE_MATCH
                remoteSize is SizeResult.Found -> Verification.REMOTE_SIZE_MATCHES_SENT
                else -> Verification.SENT_ONLY
            }
        }

        // -------------------------------------------------------------------- helpers

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun map(t: Throwable): TransferError = when (t) {
            is UrlRejectedException -> TransferError(ErrorKind.URL_REJECTED, t.message ?: "Link rejected", false)
            is SourceException -> TransferError(
                when (t.kind) {
                    SourceErrorKind.AUTH_REQUIRED -> ErrorKind.SOURCE_AUTH
                    SourceErrorKind.GONE -> ErrorKind.SOURCE_GONE
                    else -> ErrorKind.SOURCE_UNREACHABLE
                }, t.message ?: "Source error", isTransient(t) || t.kind == SourceErrorKind.RATE_LIMITED)
            is SourceChangedException -> TransferError(ErrorKind.SOURCE_CHANGED, t.message ?: "Source changed", false)
            is FtpAuthException -> TransferError(ErrorKind.FTP_AUTH, "The console's FTP server rejected the login. ${t.message}", false)
            is FtpConnectionException -> TransferError(ErrorKind.FTP_UNREACHABLE, t.message ?: "Cannot reach the console", true)
            is FtpException -> TransferError(ErrorKind.FTP_ERROR, t.message ?: "FTP error", t.response?.transient == true)
            is IOException -> TransferError(ErrorKind.IO, t.message ?: "I/O error", true)
            is IllegalArgumentException -> TransferError(ErrorKind.INVALID_REQUEST, t.message ?: "Invalid request", false)
            else -> TransferError(ErrorKind.INTERNAL, "${t::class.simpleName}: ${t.message}", false)
        }
    }
}
