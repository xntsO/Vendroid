package io.github.xntso.vendroid.ventoy

import io.github.xntso.vendroid.VendroidApplication
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class VentoyOnlineUpdaterTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `parses official linux asset and its GitHub digest`() {
        val assetName = "ventoy-1.1.17-linux.tar.gz"
        val hash = "ab".repeat(32)
        val metadata =
            """
            {
              "tag_name": "v1.1.17",
              "draft": false,
              "prerelease": false,
              "body": "Release notes without checksums",
              "assets": [{
                "name": "$assetName",
                "size": 123456,
                "digest": "sha256:$hash",
                "browser_download_url": "https://github.com/ventoy/Ventoy/releases/download/v1.1.17/$assetName"
              }]
            }
            """.trimIndent()

        val release = VentoyOnlineUpdater(
            VentoyPayloadCache(temporaryDirectory.toFile()),
        ).parseReleaseMetadata(metadata)

        assertEquals("1.1.17", release.version)
        assertEquals(assetName, release.assetName)
        assertEquals(123456, release.sizeBytes)
        assertEquals(hash, release.sha256)
    }

    @Test
    fun `falls back to a checksum published in release notes`() {
        val assetName = "ventoy-1.1.17-linux.tar.gz"
        val hash = "cd".repeat(32)
        val metadata =
            """
            {
              "tag_name": "v1.1.17",
              "draft": false,
              "prerelease": false,
              "body": "$hash  $assetName",
              "assets": [{
                "name": "$assetName",
                "size": 123456,
                "browser_download_url": "https://github.com/ventoy/Ventoy/releases/download/v1.1.17/$assetName"
              }]
            }
            """.trimIndent()

        val release = VentoyOnlineUpdater(
            VentoyPayloadCache(temporaryDirectory.toFile()),
        ).parseReleaseMetadata(metadata)

        assertEquals(hash, release.sha256)
    }

    @Test
    fun `rejects release metadata without a published checksum`() {
        val metadata =
            """
            {
              "tag_name": "v1.1.17",
              "draft": false,
              "prerelease": false,
              "body": "No checksum",
              "assets": [{
                "name": "ventoy-1.1.17-linux.tar.gz",
                "size": 123456,
                "browser_download_url": "https://github.com/ventoy/Ventoy/releases/download/v1.1.17/ventoy-1.1.17-linux.tar.gz"
              }]
            }
            """.trimIndent()

        assertThrows<IllegalArgumentException> {
            VentoyOnlineUpdater(
                VentoyPayloadCache(temporaryDirectory.toFile()),
            ).parseReleaseMetadata(metadata)
        }
    }

    @Test
    fun `extracts only the required payload files from tar`() {
        val files = payloadFiles("1.1.17")
        val archive = tarArchive(
            mapOf("ventoy-1.1.17/README" to byteArrayOf(99)) +
                files.mapKeys { (path, _) -> "ventoy-1.1.17/$path" },
        )
        val destination = temporaryDirectory.resolve("payload").toFile().apply { mkdirs() }

        VentoyTarExtractor.extractRequiredPayload(ByteArrayInputStream(archive), destination)

        files.forEach { (path, expected) ->
            assertArrayEquals(expected, File(destination, path).readBytes())
        }
        assertTrue(!File(destination, "README").exists())
    }

    @Test
    fun `rejects tar missing a required payload file`() {
        val files = payloadFiles("1.1.17") - "boot/core.img.xz"
        val archive = tarArchive(
            files.mapKeys { (path, _) -> "ventoy-1.1.17/$path" },
        )

        assertThrows<IllegalArgumentException> {
            VentoyTarExtractor.extractRequiredPayload(
                ByteArrayInputStream(archive),
                temporaryDirectory.resolve("payload").toFile().apply { mkdirs() },
            )
        }
    }

    @Test
    fun `loads validated cached payload and chooses newest version`() {
        val cache = VentoyPayloadCache(temporaryDirectory.toFile())
        writeCachedPayload(cache, "1.1.16")
        writeCachedPayload(cache, "1.1.17")
        writeCachedPayload(cache, "1.2.0")

        assertEquals("1.2.0", cache.newest()?.version)
        assertEquals("1.1.17", cache.newestCompatible("1.1.16")?.version)
        assertEquals("1.1.17", cache.load("1.1.17").version)
        assertNull(cache.find("1.1.18"))
    }

    @Test
    fun `downloads verifies extracts and activates official payload`() {
        val version = "1.1.17"
        val tar = tarArchive(
            payloadFiles(version).mapKeys { (path, _) -> "ventoy-$version/$path" },
        )
        val archive = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(tar) }
        }.toByteArray()
        val assetName = "ventoy-$version-linux.tar.gz"
        val release = VentoyOnlineRelease(
            version = version,
            assetName = assetName,
            downloadUrl =
                "https://github.com/ventoy/Ventoy/releases/download/v$version/$assetName",
            sizeBytes = archive.size.toLong(),
            sha256 = archive.sha256(),
        )
        val cache = VentoyPayloadCache(temporaryDirectory.toFile())
        val progress = mutableListOf<VentoyDownloadProgress>()
        val updater = VentoyOnlineUpdater(cache) { url ->
            FakeHttpURLConnection(url, archive)
        }

        val downloaded = updater.downloadRelease(release, "1.1.16", progress::add)

        assertEquals(version, downloaded.version)
        assertEquals(version, cache.load(version).version)
        assertEquals(archive.size.toLong(), progress.last().downloadedBytes)
        assertEquals(archive.size.toLong(), progress.last().totalBytes)
    }

    private fun writeCachedPayload(cache: VentoyPayloadCache, version: String) {
        val directory = cache.directoryFor(version).apply { mkdirs() }
        val files = payloadFiles(version)
        files.forEach { (path, bytes) ->
            File(directory, path).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }
        val manifest = buildString {
            appendLine("version=$version")
            VentoyPayloadManifest.REQUIRED_PAYLOAD_PATHS.forEach { path ->
                val bytes = files.getValue(path)
                appendLine("file=$path|${bytes.size}|${bytes.sha256()}")
            }
        }
        File(directory, "payload.manifest").writeText(manifest)
    }

    private fun payloadFiles(version: String): Map<String, ByteArray> = mapOf(
        "boot/boot.img" to byteArrayOf(1, 2, 3),
        "boot/core.img.xz" to byteArrayOf(4, 5, 6),
        "ventoy/ventoy.disk.img.xz" to byteArrayOf(7, 8, 9),
        "ventoy/version" to version.encodeToByteArray(),
    )

    private fun tarArchive(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        files.forEach { (path, bytes) ->
            val header = ByteArray(512)
            val pathBytes = path.encodeToByteArray()
            pathBytes.copyInto(header, endIndex = pathBytes.size)
            val size = bytes.size.toString(8).padStart(11, '0') + '\u0000'
            size.encodeToByteArray().copyInto(header, destinationOffset = 124)
            header[156] = '0'.code.toByte()
            output.write(header)
            output.write(bytes)
            val padding = (512 - bytes.size % 512) % 512
            output.write(ByteArray(padding))
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private class FakeHttpURLConnection(
        url: URL,
        private val response: ByteArray,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getContentLength(): Int = response.size
        override fun getInputStream(): InputStream = ByteArrayInputStream(response)
    }
}
