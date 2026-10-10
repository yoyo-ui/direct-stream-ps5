package com.directstream.ps5.core.routing

import com.directstream.ps5.core.util.Names

/** Suggestion rules only: the paths are user-editable and never assumed to exist on the console. */
data class RoutingRule(val extensions: List<String>, val directory: String)

class DestinationRouter(
    val rules: List<RoutingRule> = DEFAULT_RULES,
    val defaultDirectory: String = DEFAULT_DIRECTORY
) {
    fun directoryFor(fileName: String): String {
        val lower = fileName.lowercase()
        val rule = rules.firstOrNull { r ->
            r.extensions.any { e -> lower.endsWith("." + e.lowercase().trimStart('.')) }
        }
        return Names.normalizeRemoteDir(rule?.directory ?: defaultDirectory)
    }

    fun fullPathFor(fileName: String): String =
        Names.joinRemote(directoryFor(fileName), fileName)

    companion object {
        const val DEFAULT_DIRECTORY = "/data/homebrew"
        val DEFAULT_RULES = listOf(
            RoutingRule(listOf("pkg"), "/data/pkg"),
            RoutingRule(listOf("ffpfsc", "exfat", "ufs", "iso"), "/data/ShadowMount")
        )
    }
}
