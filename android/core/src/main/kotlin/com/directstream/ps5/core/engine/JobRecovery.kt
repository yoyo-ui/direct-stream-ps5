package com.directstream.ps5.core.engine

enum class JobState { QUEUED, PREPARING, TRANSFERRING, VERIFYING, FINALIZING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** The persisted facts needed to decide what a job may do after the app process died. */
data class PersistedJob(
    val state: JobState,
    val remote: Boolean,
    val sourceAcceptsRanges: Boolean,
    val hasValidator: Boolean,
    val localSeekable: Boolean,
    val localPermissionRetained: Boolean,
    val partialKnownOnConsole: Boolean
)

enum class RecoveryAction {
    /** Nothing in flight; leave as is. */
    UNCHANGED,
    /** Was running. Mark paused; resume continues from the console's partial file. */
    PAUSE_RESUMABLE,
    /** Was running. Mark paused; resuming would have to restart from byte 0 (reason is shown to the user). */
    PAUSE_RESTART_REQUIRED,
    /** Was running but its source can no longer be opened (e.g. lost permission); the user must re-select it. */
    FAIL_SOURCE_LOST
}

object JobRecovery {
    private val ACTIVE = setOf(JobState.PREPARING, JobState.TRANSFERRING, JobState.VERIFYING, JobState.FINALIZING)

    fun onProcessRestart(j: PersistedJob): RecoveryAction {
        if (j.state !in ACTIVE) return RecoveryAction.UNCHANGED
        if (!j.remote && !j.localPermissionRetained) return RecoveryAction.FAIL_SOURCE_LOST
        val canResume = j.partialKnownOnConsole && if (j.remote) j.sourceAcceptsRanges && j.hasValidator else j.localSeekable
        return if (canResume) RecoveryAction.PAUSE_RESUMABLE else RecoveryAction.PAUSE_RESTART_REQUIRED
    }

    fun restartReason(j: PersistedJob): String? = when {
        j.remote && !j.sourceAcceptsRanges -> "The server doesn't support range requests."
        j.remote && !j.hasValidator -> "The server gives no ETag or Last-Modified, so the file can't be checked for changes."
        !j.remote && !j.localSeekable -> "This file source can't seek."
        !j.partialKnownOnConsole -> "No partial file is known on the console."
        else -> null
    }
}
