package io.github.xntso.vendroid.ventoy

enum class VentoyVersionRelation {
    Older,
    Same,
    Newer,
    Unknown,
}

object VentoyVersion {
    private val numericVersionPattern = Regex("""[0-9]+(?:\.[0-9]+)+""")
    private val grubVersionPattern = Regex(
        """(?m)^\s*set\s+VENTOY_VERSION\s*=\s*["']?([0-9]+(?:\.[0-9]+)+)["']?\s*$""",
    )

    fun fromGrubConfig(config: String): String? =
        grubVersionPattern.find(config)?.groupValues?.get(1)

    fun compare(installed: String?, bundled: String): VentoyVersionRelation {
        val installedParts = installed?.toVersionParts() ?: return VentoyVersionRelation.Unknown
        val bundledParts = bundled.toVersionParts() ?: return VentoyVersionRelation.Unknown
        val partCount = maxOf(installedParts.size, bundledParts.size)

        for (index in 0 until partCount) {
            val installedPart = installedParts.getOrElse(index) { 0 }
            val bundledPart = bundledParts.getOrElse(index) { 0 }
            if (installedPart < bundledPart) return VentoyVersionRelation.Older
            if (installedPart > bundledPart) return VentoyVersionRelation.Newer
        }
        return VentoyVersionRelation.Same
    }

    fun isPayloadCompatible(candidate: String, bundled: String): Boolean {
        val candidateParts = candidate.toVersionParts() ?: return false
        val bundledParts = bundled.toVersionParts() ?: return false
        if (candidateParts.size < 2 || bundledParts.size < 2) return false
        return candidateParts.take(2) == bundledParts.take(2) &&
            compare(candidate, bundled) != VentoyVersionRelation.Older
    }

    private fun String.toVersionParts(): List<Int>? {
        val normalized = trim().removePrefix("v")
        // Integer parsing alone also accepts signs and non-ASCII digits.
        if (!numericVersionPattern.matches(normalized)) return null
        return normalized.split('.').map { it.toIntOrNull() ?: return null }
    }
}
