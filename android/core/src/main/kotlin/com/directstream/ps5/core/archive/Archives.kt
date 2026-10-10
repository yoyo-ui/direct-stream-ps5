package com.directstream.ps5.core.archive

enum class ArchiveType { ZIP, RAR, SEVEN_Z }
enum class SplitStyle { RAR_PART_N, NUMERIC_001, RAR_R00 }

data class SplitInfo(val baseName: String, val index: Int, val style: SplitStyle, val type: ArchiveType)
data class ArchiveInfo(val type: ArchiveType, val split: SplitInfo?)

data class SequenceCheck(val ordered: List<String>, val missingIndexes: List<Int>, val problems: List<String>) {
    val complete get() = missingIndexes.isEmpty() && problems.isEmpty()
}

object ArchiveDetector {
    private val PART_RAR = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("""^(.*\.(?:7z|zip|rar))\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val R_NN = Regex("""^(.*)\.r(\d{2})$""", RegexOption.IGNORE_CASE)

    fun fromName(name: String): ArchiveInfo? {
        PART_RAR.matchEntire(name)?.let {
            return ArchiveInfo(ArchiveType.RAR, SplitInfo(it.groupValues[1], it.groupValues[2].toInt(), SplitStyle.RAR_PART_N, ArchiveType.RAR))
        }
        NUMERIC.matchEntire(name)?.let {
            val base = it.groupValues[1]
            val t = typeOf(base) ?: return null
            return ArchiveInfo(t, SplitInfo(base, it.groupValues[2].toInt(), SplitStyle.NUMERIC_001, t))
        }
        R_NN.matchEntire(name)?.let {
            return ArchiveInfo(ArchiveType.RAR, SplitInfo(it.groupValues[1], it.groupValues[2].toInt() + 1, SplitStyle.RAR_R00, ArchiveType.RAR))
        }
        val t = typeOf(name) ?: return null
        return ArchiveInfo(t, null)
    }

    private fun typeOf(name: String): ArchiveType? = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> ArchiveType.ZIP
        "rar" -> ArchiveType.RAR
        "7z" -> ArchiveType.SEVEN_Z
        else -> null
    }

    /** Content sniffing, for when a download has a misleading name. */
    fun fromMagic(head: ByteArray): ArchiveType? = when {
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2].toInt() == 3 || head[2].toInt() == 5 || head[2].toInt() == 7) -> ArchiveType.ZIP
        head.size >= 7 && head.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) -> ArchiveType.RAR
        head.size >= 6 && head.copyOfRange(0, 6).contentEquals(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) -> ArchiveType.SEVEN_Z
        else -> null
    }

    /** Validates that a set of file names forms one complete, correctly ordered split archive. */
    fun checkSequence(names: List<String>): SequenceCheck {
        val parsed = names.map { it to fromName(it)?.split }
        val problems = mutableListOf<String>()
        if (parsed.any { it.second == null }) problems += "Not every file is a recognised archive part"
        val parts = parsed.mapNotNull { (n, s) -> s?.let { n to it } }
        if (parts.isEmpty()) return SequenceCheck(emptyList(), emptyList(), problems + "No archive parts found")
        if (parts.map { it.second.baseName.lowercase() }.distinct().size > 1) problems += "Parts belong to different archives"
        if (parts.map { it.second.style }.distinct().size > 1) problems += "Parts use different naming styles"
        val byIndex = parts.groupBy { it.second.index }
        byIndex.filter { it.value.size > 1 }.forEach { (i, _) -> problems += "Duplicate part number $i" }
        val first = if (parts.first().second.style == SplitStyle.RAR_R00) 1 else 1
        val max = byIndex.keys.max()
        val missing = (first..max).filter { it !in byIndex }
        val ordered = byIndex.toSortedMap().values.map { it.first().first }
        return SequenceCheck(ordered, missing, problems)
    }
}

enum class ArchiveStrategy { SEND_ARCHIVE, EXTRACT_ON_DEVICE, EXTRACT_ON_CONSOLE }
data class StrategyOption(val strategy: ArchiveStrategy, val available: Boolean, val reason: String)

object ArchiveStrategies {
    /**
     * Only SEND_ARCHIVE is implemented. The other two are reported as unavailable, with the reason,
     * unless the build includes an extractor / the user has configured a console-side service.
     */
    fun options(localExtractorIncluded: Boolean = false, consoleServiceConfigured: Boolean = false) = listOf(
        StrategyOption(ArchiveStrategy.SEND_ARCHIVE, true, "Uploads the archive file unchanged."),
        StrategyOption(
            ArchiveStrategy.EXTRACT_ON_DEVICE, localExtractorIncluded,
            if (localExtractorIncluded) "Extracts on this phone." else "This build does not include an archive extractor."
        ),
        StrategyOption(
            ArchiveStrategy.EXTRACT_ON_CONSOLE, consoleServiceConfigured,
            if (consoleServiceConfigured) "Uses the console-side service you configured."
            else "Needs a console-side extraction service that you set up yourself; none is configured."
        )
    )
}
