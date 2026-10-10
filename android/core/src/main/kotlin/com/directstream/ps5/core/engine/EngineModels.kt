package com.directstream.ps5.core.engine

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

sealed interface SourceSpec {
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : SourceSpec

    /**
     * A local stream (e.g. from Android's ContentResolver). [openAt] must return a stream positioned at the
     * given offset; set [seekable] only if it does so cheaply and reliably. [identity] should change if the file
     * changes (e.g. "size:mtime"); without it, a resume is never attempted.
     */
    class Local(
        val displayName: String,
        val length: Long?,
        val identity: String?,
        val seekable: Boolean,
        val openAt: (Long) -> InputStream
    ) : SourceSpec
}

/** What we learned about the source on a previous run; used to decide whether resuming is safe. */
data class ResumeHint(val etag: String?, val lastModified: String?, val length: Long?, val identity: String?)

data class TransferRequest(
    val jobId: String,
    val source: SourceSpec,
    val destinationDir: String,
    val fileName: String? = null,
    val overwrite: Boolean = false,
    val resume: ResumeHint? = null
)

data class EngineSettings(
    val workers: Int = 4,
    val chunkSizeBytes: Long = 8L shl 20,
    val bufferBytes: Long = 64L shl 20,
    val maxRetries: Int = 3,
    val retryBaseDelayMs: Long = 1_000,
    val parallel: Boolean = true,
    val ioBlockBytes: Int = 256 * 1024,
    val progressIntervalMs: Long = 250
) {
    fun sanitized() = copy(
        workers = workers.coerceIn(1, 16),
        chunkSizeBytes = chunkSizeBytes.coerceIn(256L shl 10, 64L shl 20),
        bufferBytes = bufferBytes.coerceIn(2L shl 20, 1024L shl 20),
        maxRetries = maxRetries.coerceIn(0, 20),
        retryBaseDelayMs = retryBaseDelayMs.coerceIn(0, 60_000),
        ioBlockBytes = ioBlockBytes.coerceIn(16 * 1024, 4 shl 20)
    )
}

enum class Phase { PREPARING, TRANSFERRING, VERIFYING, FINALIZING }
enum class TransferMode { SINGLE_STREAM, PARALLEL_ORDERED }

data class Progress(
    val phase: Phase,
    val bytesDone: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double,
    val mode: TransferMode?,
    val message: String? = null
)

/** What was actually checked. Never claims more than was done. */
enum class Verification(val description: String) {
    CHECKSUM_MATCH("Checksum matched on the console (server-side hash)."),
    SIZE_MATCH("Console file size equals the source size. No checksum was available."),
    REMOTE_SIZE_MATCHES_SENT("Source size was unknown; console file size equals the bytes sent."),
    SENT_ONLY("Console can't report file sizes; only the byte count sent could be checked.")
}

enum class ErrorKind {
    SOURCE_UNREACHABLE, SOURCE_AUTH, SOURCE_GONE, SOURCE_CHANGED, URL_REJECTED, FTP_UNREACHABLE, FTP_AUTH, FTP_ERROR,
    DESTINATION_EXISTS, INSUFFICIENT_SPACE, INCOMPLETE_UPLOAD, CHECKSUM_MISMATCH, RENAME_FAILED, IO, INVALID_REQUEST, INTERNAL
}

data class TransferError(val kind: ErrorKind, val message: String, val recoverable: Boolean)

data class ResolvedSource(
    val fileName: String,
    val length: Long?,
    val etag: String?,
    val lastModified: String?,
    val acceptsRanges: Boolean,
    val finalPath: String,
    val partPath: String,
    val notes: List<String>
)

sealed class TransferOutcome {
    data class Completed(
        val bytes: Long, val finalPath: String, val verification: Verification,
        val mode: TransferMode, val elapsedMs: Long, val resumedFrom: Long
    ) : TransferOutcome() {
        val averageBytesPerSecond: Double get() = if (elapsedMs <= 0) 0.0 else (bytes - resumedFrom) * 1000.0 / elapsedMs
    }
    data class Failed(val error: TransferError, val partPath: String?) : TransferOutcome()
    data class Paused(val bytesDone: Long) : TransferOutcome()
    data class Cancelled(val partialDeleted: Boolean) : TransferOutcome()
}

enum class StopReason { PAUSE, CANCEL }

/** Lets the UI pause or cancel a running transfer without cancelling its own coroutine scope. */
class TransferControl {
    @Volatile var reason: StopReason? = null; private set
    /** Last known absolute byte position, updated by the engine. Useful right after a pause. */
    @Volatile var lastPosition: Long = 0; internal set
    @Volatile var deletePartialOnCancel: Boolean = false; private set
    private val target = AtomicReference<kotlinx.coroutines.Job?>(null)
    internal fun attach(job: kotlinx.coroutines.Job) {
        target.set(job)
        if (reason != null) job.cancel()    // stop requested before the work started
    }
    fun pause() { reason = StopReason.PAUSE; target.get()?.cancel() }
    fun cancel(deletePartial: Boolean = false) {
        reason = StopReason.CANCEL; deletePartialOnCancel = deletePartial; target.get()?.cancel()
    }
}
