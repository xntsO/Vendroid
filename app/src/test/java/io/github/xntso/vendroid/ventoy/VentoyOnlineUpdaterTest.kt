package io.github.xntso.vendroid.ventoy

import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.online.OnlineAccessRequiredException
import io.github.xntso.vendroid.online.OnlineEligibility
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
import java.io.IOException
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
        val requestedUrls = mutableListOf<String>()
        val updater = VentoyOnlineUpdater(cache, eligibility = { OnlineEligibility.ELIGIBLE }) { url ->
            requestedUrls.add(url.toString())
            if (url.host == "github.com") {
                FakeHttpURLConnection(url, byteArrayOf(), 302, "https://release-assets.githubusercontent.com/payload")
            } else {
                FakeHttpURLConnection(url, archive)
            }
        }

        val downloaded = updater.downloadRelease(release, "1.1.16", progress::add)

        assertEquals(version, downloaded.version)
        assertEquals(version, cache.load(version).version)
        assertEquals(archive.size.toLong(), progress.last().downloadedBytes)
        assertEquals(archive.size.toLong(), progress.last().totalBytes)
        assertEquals(listOf(release.downloadUrl, "https://release-assets.githubusercontent.com/payload"), requestedUrls)
    }

    @Test
    fun `unknown and restricted checks direct downloads and retries open no connections`() {
        val cache = VentoyPayloadCache(temporaryDirectory.toFile())
        var connections = 0
        var eligibility = OnlineEligibility.UNKNOWN
        val updater = VentoyOnlineUpdater(cache, eligibility = { eligibility }) { url ->
            connections++
            FakeHttpURLConnection(url, byteArrayOf())
        }
        for (band in listOf(OnlineEligibility.UNKNOWN, OnlineEligibility.RESTRICTED)) {
            eligibility = band
            repeat(2) {
                assertThrows<OnlineAccessRequiredException> { updater.fetchLatestRelease() }
                assertThrows<OnlineAccessRequiredException> { updater.downloadLatest("1.1.16") }
                assertThrows<OnlineAccessRequiredException> { updater.downloadRelease(officialRelease(), "1.1.16") }
                assertEquals(0, connections)
            }
        }
    }

    @Test
    fun `default updater fails closed without any supplied eligibility`() {
        var connections = 0
        val updater = VentoyOnlineUpdater(VentoyPayloadCache(temporaryDirectory.toFile())) { url ->
            connections++
            FakeHttpURLConnection(url, byteArrayOf())
        }
        assertThrows<OnlineAccessRequiredException> { updater.fetchLatestRelease() }
        assertEquals(0, connections)
    }

    @Test
    fun `restricted users can reuse a validated cached payload entirely offline`() {
        val cache = VentoyPayloadCache(temporaryDirectory.toFile())
        writeCachedPayload(cache, "1.1.17")
        val updater = VentoyOnlineUpdater(cache, eligibility = { OnlineEligibility.RESTRICTED }) {
            throw AssertionError("Cached payload must not request a connection")
        }
        assertEquals("1.1.17", updater.downloadRelease(officialRelease(), "1.1.16").version)
        assertEquals("1.1.17", cache.newestCompatible("1.1.16")?.version)
    }

    @Test
    fun `a redirect rechecks eligibility before contacting the delivery provider`() {
        var eligibility = OnlineEligibility.ELIGIBLE
        val connections = mutableListOf<URL>()
        var disconnected = false
        val updater = VentoyOnlineUpdater(
            VentoyPayloadCache(temporaryDirectory.toFile()), eligibility = { eligibility },
        ) { url ->
            connections.add(url)
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() { disconnected = true }
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    eligibility = OnlineEligibility.RESTRICTED
                    return 302
                }
                override fun getHeaderField(name: String): String? =
                    if (name == "Location") "https://release-assets.githubusercontent.com/payload" else null
            }
        }
        assertThrows<OnlineAccessRequiredException> { updater.downloadRelease(officialRelease(), "1.1.16") }
        assertEquals(1, connections.size)
        assertTrue(disconnected)
    }

    @Test
    fun `a failed eligible request cannot be retried after eligibility is lost`() {
        var eligibility = OnlineEligibility.ELIGIBLE
        var connections = 0
        val updater = VentoyOnlineUpdater(
            VentoyPayloadCache(temporaryDirectory.toFile()), eligibility = { eligibility },
        ) {
            connections++
            throw IOException("Fixture connection failed")
        }
        assertThrows<IOException> { updater.fetchLatestRelease() }
        eligibility = OnlineEligibility.UNKNOWN
        assertThrows<OnlineAccessRequiredException> { updater.fetchLatestRelease() }
        assertEquals(1, connections)
    }

    private fun officialRelease() = VentoyOnlineRelease(
        version = "1.1.17", assetName = "ventoy-1.1.17-linux.tar.gz",
        downloadUrl = "https://github.com/ventoy/Ventoy/releases/download/v1.1.17/ventoy-1.1.17-linux.tar.gz",
        sizeBytes = 1, sha256 = "ab".repeat(32),
    )

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
        private val status: Int = 200,
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
        override fun getContentLength(): Int = response.size
        override fun getInputStream(): InputStream = ByteArrayInputStream(response)
    }
}
