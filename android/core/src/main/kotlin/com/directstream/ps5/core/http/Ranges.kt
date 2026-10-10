package com.directstream.ps5.core.http

/** Parsed `Content-Range: bytes start-end/total`. `total` is null for `*`. */
data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?) {
    val length get() = endInclusive - start + 1
}

object RangeParser {
    private val FULL = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
    private val UNSATISFIED = Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** Returns null for anything malformed or self-contradictory (end < start, end >= total). */
    fun parseContentRange(header: String?): ContentRange? {
        val m = FULL.matchEntire(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = if (m.groupValues[3] == "*") null else (m.groupValues[3].toLongOrNull() ?: return null)
        if (end < start) return null
        if (total != null && end >= total) return null
        return ContentRange(start, end, total)
    }

    // Total size from the `bytes <star>/N` form that 416 responses carry.
    fun parseUnsatisfiedTotal(header: String?): Long? {
        val m = UNSATISFIED.matchEntire(header ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }
}

data class Chunk(val index: Int, val start: Long, val endInclusive: Long) {
    val length get() = endInclusive - start + 1
}

object ChunkPlanner {
    /** Splits [startOffset, total) into consecutive chunks: no gaps, no overlap, last one may be shorter. */
    fun plan(total: Long, chunkSize: Long, startOffset: Long = 0): List<Chunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(startOffset in 0..total) { "startOffset out of range" }
        val out = ArrayList<Chunk>(((total - startOffset) / chunkSize + 1).toInt())
        var pos = startOffset
        var i = 0
        while (pos < total) {
            val end = minOf(pos + chunkSize, total) - 1
            out += Chunk(i++, pos, end)
            pos = end + 1
        }
        return out
    }

    /** Test/diagnostic helper: true iff chunks tile [startOffset, total) exactly. */
    fun coversExactly(chunks: List<Chunk>, total: Long, startOffset: Long = 0): Boolean {
        var pos = startOffset
        for (c in chunks) { if (c.start != pos || c.endInclusive < c.start) return false; pos = c.endInclusive + 1 }
        return pos == total
    }
}
