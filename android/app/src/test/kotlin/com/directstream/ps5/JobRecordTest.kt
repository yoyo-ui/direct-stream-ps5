package com.directstream.ps5

import com.directstream.ps5.core.engine.JobState
import com.directstream.ps5.data.JobRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Process-recreation safety: everything the manager needs to resume must survive the database round trip. */
class JobRecordTest {
    private val full = JobRecord(
        id = "id-1", remote = true, displayName = "a.pkg", sourceLabel = "https://h.example/a.pkg?token=***", urlEnc = "enc-url", headersEnc = "enc-h",
        fileName = "a.pkg", destDir = "/data/pkg", overwrite = true, strategy = "EXTRACT_ON_CONSOLE", passwordEnc = "enc-pw", extractDir = "/data/homebrew",
        state = JobState.TRANSFERRING, bytesDone = 123, total = 456, etag = "\"v1\"", lastModified = "Wed, 01 Jan 2025 00:00:00 GMT", length = 456,
        identity = "x", rangesOk = true, seekable = true, partPath = "/data/pkg/a.pkg.id-1.ps5part", finalPath = "/data/pkg/a.pkg", avgBps = 12.5, restartRequired = true, createdAt = 10, updatedAt = 20
    )

    @Test fun everythingNeededToResumeSurvivesTheDatabase() {
        val back = JobRecord.fromRow(full.id, full.state.name, full.createdAt, full.updatedAt, full.toJson())
        assertEquals(full, back)
        assertTrue(back.hasValidator); assertTrue(back.active)
    }

    @Test fun nullsStayNull() {
        val min = JobRecord(id = "i", remote = false, displayName = "f", sourceLabel = "Local file", destDir = "/d")
        val back = JobRecord.fromRow("i", "QUEUED", min.createdAt, min.updatedAt, min.toJson())
        assertNull(back.total); assertNull(back.etag); assertNull(back.avgBps); assertFalse(back.hasValidator)
    }

    @Test fun serialisedFormNeverContainsPlainSecrets() {
        val json = full.toJson()
        assertFalse("token=SECRET" in json); assertTrue("enc-url" in json)
    }

    @Test fun unknownStatesBecomeFailedRatherThanCrashing() {
        assertEquals(JobState.FAILED, JobRecord.fromRow("i", "SOMETHING_NEW", 1, 1, full.toJson()).state)
    }
}
