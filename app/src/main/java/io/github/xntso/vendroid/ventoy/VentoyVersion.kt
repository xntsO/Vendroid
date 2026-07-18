package io.github.xntso.vendroid.ventoy

enum class VentoyVersionRelation {
    Older,
    Same,
    Newer,
    Unknown,
}

object VentoyVersion {
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

    private fun String.toVersionParts(): List<Int>? {
        val normalized = trim().removePrefix("v")
        if (normalized.isEmpty()) return null
        return normalized.split('.').map { it.toIntOrNull() ?: return null }
    }
}
