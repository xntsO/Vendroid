package io.github.xntso.vendroid.ventoy

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class VentoyPayloadFile(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class VentoyPayloadManifest(
    val version: String,
    val files: Map<String, VentoyPayloadFile>,
) {
    fun requireComplete() {
        REQUIRED_PAYLOAD_PATHS.forEach { path ->
            require(files.containsKey(path)) { "Payload manifest is missing $path" }
        }
    }

    fun allows(candidate: VentoyPayloadManifest): Boolean =
        version == candidate.version && files == candidate.files

    companion object {
        val REQUIRED_PAYLOAD_PATHS = listOf(
            "boot/boot.img",
            "boot/core.img.xz",
            "ventoy/ventoy.disk.img.xz",
            "ventoy/version",
        )

        fun parse(text: String): VentoyPayloadManifest {
            var version: String? = null
            val files = linkedMapOf<String, VentoyPayloadFile>()

            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    when {
                        line.startsWith("version=") -> version = line.substringAfter('=').trim()
                        line.startsWith("file=") -> {
                            val parts = line.substringAfter('=').split('|')
                            require(parts.size == 3) { "Invalid payload manifest file line: $line" }
                            val path = parts[0]
                            val size = parts[1].toLong()
                            val sha256 = parts[2].lowercase(Locale.US)
                            require(path.isNotBlank()) { "Payload file path must not be blank" }
                            require(size >= 0) { "Payload file size must be non-negative" }
                            require(sha256.matches(Regex("[0-9a-f]{64}"))) {
                                "Invalid SHA-256 for $path"
                            }
                            files[path] = VentoyPayloadFile(path, size, sha256)
                        }
                        else -> throw IllegalArgumentException("Invalid payload manifest line: $line")
                    }
                }

            return VentoyPayloadManifest(
                version = requireNotNull(version) { "Payload manifest is missing version" },
                files = files,
            ).also { it.requireComplete() }
        }
    }
}

class VentoyPayload(
    val manifest: VentoyPayloadManifest,
    private val openStream: (String) -> InputStream,
) {
    val version: String
        get() = manifest.version

    fun open(path: String): InputStream {
        require(manifest.files.containsKey(path)) { "Payload manifest does not include $path" }
        return openStream(path)
    }

    fun bootImage(): ByteArray =
        open("boot/boot.img").use { it.readBytes() }

    fun openCoreImage(): InputStream = open("boot/core.img.xz")

    fun openVentoyDiskImage(): InputStream = open("ventoy/ventoy.disk.img.xz")

    fun validate() {
        manifest.requireComplete()
        manifest.files.values.forEach { file ->
            val actual = open(file.path).use { it.sha256AndSize() }
            require(actual.size == file.size) {
                "Payload ${file.path} size mismatch: expected ${file.size}, found ${actual.size}"
            }
            require(actual.sha256 == file.sha256) {
                "Payload ${file.path} SHA-256 mismatch"
            }
        }
        val versionFile = open("ventoy/version").use { it.readBytes().decodeToString().trim() }
        require(versionFile == manifest.version) {
            "Payload version file mismatch: expected ${manifest.version}, found $versionFile"
        }
    }

    companion object {
        private const val ASSET_ROOT = "ventoy_payload"

        fun fromAssets(assetManager: AssetManager): VentoyPayload {
            val manifestText = assetManager.open("$ASSET_ROOT/payload.manifest").use {
                it.readBytes().decodeToString()
            }
            return VentoyPayload(VentoyPayloadManifest.parse(manifestText)) { path ->
                assetManager.open("$ASSET_ROOT/$path")
            }
        }

        fun fromBytes(version: String, files: Map<String, ByteArray>): VentoyPayload {
            val entries = files.mapValues { (path, bytes) ->
                VentoyPayloadFile(path, bytes.size.toLong(), bytes.sha256Hex())
            }
            val manifest = VentoyPayloadManifest(version, entries).also { it.requireComplete() }
            return VentoyPayload(manifest) { path ->
                ByteArrayInputStream(requireNotNull(files[path]) { "Missing $path" })
            }
        }
    }
}

private data class DigestResult(
    val size: Long,
    val sha256: String,
)

private fun InputStream.sha256AndSize(): DigestResult {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        size += read
        digest.update(buffer, 0, read)
    }
    return DigestResult(size, digest.hex())
}

internal fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(this)
    return digest.hex()
}

private fun MessageDigest.hex(): String =
    digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
