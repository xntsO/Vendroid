package io.github.xntso.vendroid.ventoy

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

data class VentoyOnlineRelease(
    val version: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class VentoyOnlinePayload(
    val version: String,
    val directory: File,
)

data class VentoyDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

class VentoyPayloadCache(
    appFilesDirectory: File,
) {
    private val root = File(appFilesDirectory, CACHE_DIRECTORY)

    fun load(version: String): VentoyPayload {
        val directory = directoryFor(version)
        require(directory.isDirectory) { "Online Ventoy payload $version is not downloaded" }
        return VentoyPayload.fromDirectory(directory).also { payload ->
            require(payload.version == version) {
                "Cached Ventoy payload version mismatch: expected $version, found ${payload.version}"
            }
        }
    }

    fun find(version: String): VentoyOnlinePayload? {
        val directory = directoryFor(version)
        if (!directory.isDirectory) return null
        return runCatching {
            load(version).validate()
            VentoyOnlinePayload(version, directory)
        }.getOrNull()
    }

    fun newest(): VentoyOnlinePayload? {
        return newestMatching { true }
    }

    fun newestCompatible(bundledVersion: String): VentoyOnlinePayload? {
        return newestMatching { payload ->
            VentoyVersion.isPayloadCompatible(payload.version, bundledVersion)
        }
    }

    private fun newestMatching(
        predicate: (VentoyOnlinePayload) -> Boolean,
    ): VentoyOnlinePayload? {
        val versions = root.listFiles()
            ?.filter { it.isDirectory && VERSION_PATTERN.matches(it.name) }
            ?.mapNotNull { find(it.name) }
            ?.filter(predicate)
            .orEmpty()
        return versions.maxWithOrNull(
            Comparator { left, right ->
                when (VentoyVersion.compare(left.version, right.version)) {
                    VentoyVersionRelation.Older -> -1
                    VentoyVersionRelation.Same, VentoyVersionRelation.Unknown -> 0
                    VentoyVersionRelation.Newer -> 1
                }
            },
        )
    }

    internal fun rootDirectory(): File = root

    internal fun directoryFor(version: String): File {
        require(VERSION_PATTERN.matches(version)) { "Invalid Ventoy version: $version" }
        return File(root, version)
    }

    companion object {
        private const val CACHE_DIRECTORY = "ventoy_payloads"
        internal val VERSION_PATTERN = Regex("""\d+(?:\.\d+)+""")
    }
}

class VentoyOnlineUpdater(
    private val payloadCache: VentoyPayloadCache,
    private val releaseApiUrl: String = LATEST_RELEASE_API,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    fun downloadLatest(
        bundledVersion: String,
        onProgress: (VentoyDownloadProgress) -> Unit = {},
    ): VentoyOnlinePayload {
        val release = fetchLatestRelease()
        return downloadRelease(release, bundledVersion, onProgress)
    }

    fun downloadRelease(
        release: VentoyOnlineRelease,
        bundledVersion: String,
        onProgress: (VentoyDownloadProgress) -> Unit = {},
    ): VentoyOnlinePayload {
        requireOfficialRelease(release)
        require(VentoyVersion.isPayloadCompatible(release.version, bundledVersion)) {
            "Ventoy ${release.version} requires a Vendroid update before it can be used safely."
        }

        payloadCache.find(release.version)?.let { return it }

        val root = payloadCache.rootDirectory().apply {
            require(mkdirs() || isDirectory) { "Could not create the online payload directory" }
        }
        val archive = File.createTempFile("ventoy-${release.version}-", ".tar.gz", root)
        val staging = File(root, ".staging-${release.version}-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Could not create the online payload staging directory" }

        try {
            downloadArchive(release, archive, onProgress)
            val actualHash = archive.sha256()
            require(actualHash.equals(release.sha256, ignoreCase = true)) {
                "Downloaded Ventoy archive checksum mismatch."
            }

            archive.inputStream().buffered().use { compressed ->
                GZIPInputStream(compressed).use { tar ->
                    VentoyTarExtractor.extractRequiredPayload(tar, staging)
                }
            }
            createPayloadManifest(staging, release.version)
            val payload = VentoyPayload.fromDirectory(staging)
            require(payload.version == release.version) {
                "Downloaded payload version mismatch: expected ${release.version}, found ${payload.version}"
            }
            payload.validate()

            val destination = payloadCache.directoryFor(release.version)
            if (destination.exists()) {
                require(destination.deleteRecursively()) {
                    "Could not replace the existing Ventoy ${release.version} payload"
                }
            }
            require(staging.renameTo(destination)) {
                "Could not activate the downloaded Ventoy payload"
            }
            return VentoyOnlinePayload(release.version, destination)
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun fetchLatestRelease(): VentoyOnlineRelease {
        val connection = openConnection(releaseApiUrl, acceptJson = true)
        val body = connection.readSuccessfulResponse(MAX_RELEASE_METADATA_BYTES).decodeToString()
        val release = parseReleaseMetadata(body)
        requireOfficialRelease(release)
        return release
    }

    internal fun parseReleaseMetadata(json: String): VentoyOnlineRelease {
        val release = JSONObject(json)
        require(!release.optBoolean("draft", false)) { "Latest Ventoy release is a draft" }
        require(!release.optBoolean("prerelease", false)) { "Latest Ventoy release is a prerelease" }

        val version = release.getString("tag_name").trim().removePrefix("v")
        require(VentoyPayloadCache.VERSION_PATTERN.matches(version)) {
            "Invalid Ventoy release version: $version"
        }
        val assetName = "ventoy-$version-linux.tar.gz"
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .singleOrNull { it.getString("name") == assetName }
            ?: throw IllegalArgumentException("Ventoy release is missing $assetName")
        val size = asset.getLong("size")
        require(size in 1..MAX_ARCHIVE_BYTES) {
            "Ventoy archive size $size is outside the supported range"
        }
        val sha256 = parseAssetSha256(asset.optString("digest"))
            ?: parsePublishedSha256(release.optString("body"), assetName)
            ?: throw IllegalArgumentException("Ventoy release does not publish a SHA-256 for $assetName")

        return VentoyOnlineRelease(
            version = version,
            assetName = assetName,
            downloadUrl = asset.getString("browser_download_url"),
            sizeBytes = size,
            sha256 = sha256,
        )
    }

    private fun downloadArchive(
        release: VentoyOnlineRelease,
        destination: File,
        onProgress: (VentoyDownloadProgress) -> Unit,
    ) {
        val connection = openConnection(release.downloadUrl, acceptJson = false)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText().take(512) }
                    .orEmpty()
                throw IOException(
                    "Ventoy download returned HTTP $status" +
                        if (detail.isBlank()) "" else ": $detail",
                )
            }
            val reportedLength = connection.contentLength.toLong()
            if (reportedLength > 0) {
                require(reportedLength == release.sizeBytes) {
                    "Ventoy download size changed: expected ${release.sizeBytes}, found $reportedLength"
                }
            }

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) throw IOException("Ventoy download made no progress")
                        downloaded += read
                        require(downloaded <= release.sizeBytes && downloaded <= MAX_ARCHIVE_BYTES) {
                            "Ventoy download exceeded its declared size"
                        }
                        output.write(buffer, 0, read)
                        onProgress(VentoyDownloadProgress(downloaded, release.sizeBytes))
                    }
                    output.fd.sync()
                    require(downloaded == release.sizeBytes) {
                        "Ventoy download ended at $downloaded bytes; expected ${release.sizeBytes}"
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, acceptJson: Boolean): HttpURLConnection {
        val connection = connectionFactory(URL(url))
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Vendroid/${javaClass.`package`?.implementationVersion ?: "dev"}")
        if (acceptJson) connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection
    }

    private fun HttpURLConnection.readSuccessfulResponse(maxBytes: Int): ByteArray {
        try {
            val status = responseCode
            if (status !in 200..299) {
                val detail = errorStream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
                throw IOException("GitHub returned HTTP $status${if (detail.isBlank()) "" else ": $detail"}")
            }
            return inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) throw IOException("GitHub response made no progress")
                    require(output.size() + read <= maxBytes) {
                        "GitHub response is unexpectedly large"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            disconnect()
        }
    }

    private fun requireOfficialRelease(release: VentoyOnlineRelease) {
        require(release.assetName == "ventoy-${release.version}-linux.tar.gz") {
            "Unexpected Ventoy release asset name"
        }
        require(release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Invalid Ventoy release checksum"
        }
        require(release.sizeBytes in 1..MAX_ARCHIVE_BYTES) {
            "Ventoy archive size is outside the supported range"
        }
        val uri = URI(release.downloadUrl)
        val expectedPath =
            "/ventoy/Ventoy/releases/download/v${release.version}/${release.assetName}"
        require(
            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.path.equals(expectedPath, ignoreCase = true)
        ) {
            "Ventoy release asset URL is not an official HTTPS GitHub URL"
        }
    }

    companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_RELEASE_METADATA_BYTES = 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L

        internal fun parseAssetSha256(digest: String): String? {
            val match = Regex("""(?i)^sha256:([0-9a-f]{64})$""").matchEntire(digest.trim())
            return match?.groupValues?.get(1)?.lowercase(Locale.US)
        }

        internal fun parsePublishedSha256(body: String, assetName: String): String? {
            val pattern = Regex(
                """(?i)([0-9a-f]{64})\s+\*?${Regex.escape(assetName)}""",
            )
            return pattern.findAll(body)
                .map { it.groupValues[1].lowercase(Locale.US) }
                .distinct()
                .singleOrNull()
        }
    }
}

internal object VentoyTarExtractor {
    private const val TAR_BLOCK_BYTES = 512
    private const val MAX_TAR_ENTRY_BYTES = 256L * 1024L * 1024L

    fun extractRequiredPayload(input: InputStream, destination: File) {
        val tar = BufferedInputStream(input)
        val remainingPaths = VentoyPayloadManifest.REQUIRED_PAYLOAD_PATHS.toMutableSet()

        while (remainingPaths.isNotEmpty()) {
            val header = tar.readExactOrNull(TAR_BLOCK_BYTES) ?: break
            if (header.all { it == 0.toByte() }) break

            val name = header.tarString(0, 100)
            val prefix = header.tarString(345, 155)
            val fullName = if (prefix.isBlank()) name else "$prefix/$name"
            val size = header.tarOctal(124, 12)
            require(size in 0..MAX_TAR_ENTRY_BYTES) {
                "Ventoy archive entry is too large: $fullName"
            }
            val type = header[156].toInt().toChar()
            val requiredPath = remainingPaths.singleOrNull { path ->
                type in listOf('\u0000', '0') &&
                    (fullName == path || fullName.endsWith("/$path"))
            }

            if (requiredPath == null) {
                tar.skipExactly(size)
            } else {
                val output = File(destination, requiredPath)
                require(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    "Could not create the payload extraction directory"
                }
                FileOutputStream(output).use { file ->
                    tar.copyExactly(file, size)
                    file.fd.sync()
                }
                remainingPaths.remove(requiredPath)
            }

            val padding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
            tar.skipExactly(padding)
        }

        require(remainingPaths.isEmpty()) {
            "Ventoy archive is missing required files: $remainingPaths"
        }
    }

    private fun ByteArray.tarString(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() }
            ?: offset + length
        return copyOfRange(offset, end).decodeToString().trim()
    }

    private fun ByteArray.tarOctal(offset: Int, length: Int): Long {
        val value = tarString(offset, length).trim()
        if (value.isEmpty()) return 0
        return value.toLongOrNull(8)
            ?: throw IllegalArgumentException("Invalid TAR entry size")
    }

    private fun InputStream.readExactOrNull(length: Int): ByteArray? {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("Truncated TAR header")
            }
            if (read == 0) throw IOException("TAR stream made no progress")
            offset += read
        }
        return result
    }

    private fun InputStream.skipExactly(byteCount: Long) {
        var remaining = byteCount
        val discard = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (read < 0) throw EOFException("Truncated TAR entry")
                if (read == 0) throw IOException("TAR stream made no progress")
                remaining -= read
            }
        }
    }

    private fun InputStream.copyExactly(output: FileOutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("Truncated TAR payload entry")
            if (read == 0) throw IOException("TAR stream made no progress")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}

private fun createPayloadManifest(directory: File, expectedVersion: String) {
    val versionFile = File(directory, "ventoy/version")
    val actualVersion = versionFile.readText().trim()
    require(actualVersion == expectedVersion) {
        "Ventoy archive version mismatch: expected $expectedVersion, found $actualVersion"
    }
    val manifest = buildString {
        appendLine("version=$actualVersion")
        VentoyPayloadManifest.REQUIRED_PAYLOAD_PATHS.forEach { path ->
            val file = File(directory, path)
            require(file.isFile) { "Downloaded payload is missing $path" }
            appendLine("file=$path|${file.length()}|${file.sha256()}")
        }
    }
    File(directory, "payload.manifest").writeText(manifest)
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) throw IOException("File hashing made no progress")
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") {
        "%02x".format(Locale.US, it.toInt() and 0xff)
    }
}
