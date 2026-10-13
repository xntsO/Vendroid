package io.github.xntso.vendroid.ventoy

import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.BuildConfig
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class VentoyOnlineUpdaterTest {
    @TempDir
    lateinit var temporaryDirectory: Path
    private val origin = VentoyOnlineUpdater.MIRROR_ORIGIN

    @Test
    fun `parses the exact first-party mirror schema and checksum`() {
        val release = updater().parseReleaseMetadata(metadata())
        assertEquals("1.1.17", release.version)
        assertEquals("ventoy-1.1.17-linux.tar.gz", release.assetName)
        assertEquals(123456L, release.sizeBytes)
        assertEquals("ab".repeat(32), release.sha256)
        assertEquals("$origin/v1/assets/${release.sha256}/${release.assetName}", release.downloadUrl)
    }

    @Test
    fun `rejects missing extra unsupported and coerced schema fields`() {
        val changes = listOf<(JSONObject) -> Unit>(
            { it.remove("sha256") }, { it.put("upstreamUrl", "https://github.com/ventoy/Ventoy") },
            { it.put("schemaVersion", 2) }, { it.put("schemaVersion", "1") },
            { it.put("sizeBytes", "12") }, { it.put("sizeBytes", 1.25) },
            { it.put("version", 117) }, { it.put("assetName", JSONObject.NULL) },
            { it.put("sha256", "AB".repeat(32)) }, { it.put("sha256", "invalid") },
        )
        changes.forEach { change ->
            val json = JSONObject(metadata()).also(change).toString()
            assertThrows<IllegalArgumentException> { updater().parseReleaseMetadata(json) }
        }
        assertThrows<IllegalArgumentException> {
            updater().parseReleaseMetadata(metadata().replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"))
        }
    }

    @Test
    fun `version components must be canonical and bounded`() {
        for (version in listOf("1", "v1.1.17", "1.01.17", "01.1", "1.1.1000000", "1.1.17.0.1", "1.1.-1", "1.1.17 ")) {
            assertThrows<IllegalArgumentException> { updater().parseReleaseMetadata(metadata(version = version)) }
        }
        for (version in listOf("0.0", "1.1.18", "1.1.18.0", "999999.999999")) {
            assertEquals(version, updater().parseReleaseMetadata(metadata(version = version)).version)
        }
    }

    @Test
    fun `size metadata must be positive and at most 256 MiB`() {
        for (size in listOf(-1L, 0L, 256L * 1024 * 1024 + 1)) {
            assertThrows<IllegalArgumentException> { updater().parseReleaseMetadata(metadata(size = size)) }
        }
        assertEquals(256L * 1024 * 1024, updater().parseReleaseMetadata(metadata(size = 256L * 1024 * 1024)).sizeBytes)
    }

    @Test
    fun `asset origin path and digest must match exactly before any request`() {
        val valid = release()
        val invalidUrls = listOf(
            "https://github.com/ventoy/Ventoy/releases/download/v1.1.17/${valid.assetName}",
            valid.downloadUrl.replace("updates.vendroid.fhas.sa", "updates.vendroid.fhas.sa.evil.example"),
            valid.downloadUrl.replace("https://", "http://"),
            valid.downloadUrl.replace("https://", "https://user@"),
            valid.downloadUrl.replace(origin, "$origin:443"),
            valid.downloadUrl + "?download=1", valid.downloadUrl + "#asset",
            valid.downloadUrl.replace(valid.sha256, "cd".repeat(32)),
            valid.downloadUrl.replace("ventoy-1.1.17", "ventoy-1.1.18"),
            valid.downloadUrl.replace("/v1/assets/", "/v1/assets/../assets/"),
            valid.downloadUrl.replace("updates.vendroid", "UPDATES.vendroid"),
        )
        val client = updater { throw AssertionError("Invalid metadata must never open a connection") }
        for (url in invalidUrls) {
            assertThrows<IllegalArgumentException> { client.downloadRelease(valid.copy(downloadUrl = url), "1.1.16") }
            assertThrows<IllegalArgumentException> { client.parseReleaseMetadata(metadata(url = url)) }
        }
        assertThrows<IllegalArgumentException> { client.parseReleaseMetadata(metadata().replace("ventoy-1.1.17-linux", "ventoy-1.1.17-windows")) }
    }

    @Test
    fun `rejects every redirect for metadata and assets without following it`() {
        for (status in listOf(300, 301, 302, 303, 304, 307, 308)) {
            for (location in listOf("https://github.com/ventoy/Ventoy", "$origin/other")) {
                for (metadataRequest in listOf(true, false)) {
                    val requests = mutableListOf<FakeHttpURLConnection>()
                    val client = updater { url ->
                        FakeHttpURLConnection(url, byteArrayOf(), status, location).also(requests::add)
                    }
                    assertThrows<IOException> {
                        if (metadataRequest) client.fetchLatestRelease() else client.downloadRelease(release(), "1.1.16")
                    }
                    assertEquals(1, requests.size)
                    assertFalse(requests.single().instanceFollowRedirects)
                    assertTrue(requests.single().disconnected)
                    assertEquals(0, requests.single().bodyReads)
                }
            }
        }
    }

    @Test
    fun `a newer compatible mirrored payload downloads immediately without eligibility state`() {
        val version = "1.1.18"
        val archive = compressedArchive(version)
        val release = release(version, archive.size.toLong(), archive.sha256())
        val requests = mutableListOf<FakeHttpURLConnection>()
        val previousCookieHandler = CookieHandler.getDefault()
        val client = updater { url ->
            val body = if (url.toString() == VentoyOnlineUpdater.LATEST_RELEASE_API) {
                metadata(version, archive.size.toLong(), archive.sha256()).encodeToByteArray()
            } else archive
            FakeHttpURLConnection(url, body, responseCookie = "security=fixture").also(requests::add)
        }
        val progress = mutableListOf<VentoyDownloadProgress>()
        val downloaded = client.downloadLatest("1.1.16", progress::add)
        assertEquals(version, downloaded.version)
        assertEquals(version, cache().load(version).version)
        assertEquals(archive.size.toLong(), progress.last().downloadedBytes)
        assertEquals(listOf(VentoyOnlineUpdater.LATEST_RELEASE_API, release.downloadUrl), requests.map { it.url.toString() })
        assertEquals("application/json", requests.first().getRequestProperty("Accept"))
        requests.forEach {
            assertEquals("Vendroid/${BuildConfig.VERSION_NAME}", it.getRequestProperty("User-Agent"))
            assertNull(it.getRequestProperty("Cookie"))
            assertNull(it.getRequestProperty("Authorization"))
            assertTrue(it.disconnected)
        }
        assertSame(previousCookieHandler, CookieHandler.getDefault())
    }

    @Test
    fun `HTTP errors never consume unbounded server detail streams`() {
        for (metadataRequest in listOf(true, false)) {
            val requests = mutableListOf<FakeHttpURLConnection>()
            val client = updater { url ->
                FakeHttpURLConnection(url, byteArrayOf(), status = 503).also(requests::add)
            }
            assertThrows<IOException> {
                if (metadataRequest) client.fetchLatestRelease() else client.downloadRelease(release(), "1.1.16")
            }
            assertEquals(0, requests.single().errorReads)
            assertTrue(requests.single().disconnected)
        }
    }

    @Test
    fun `checksum failure never activates a payload or leaves staging files`() {
        val archive = compressedArchive("1.1.17")
        val client = updater { url -> FakeHttpURLConnection(url, archive) }
        assertThrows<IllegalArgumentException> { client.downloadRelease(release(size = archive.size.toLong()), "1.1.16") }
        assertNull(cache().find("1.1.17"))
        assertTrue(cache().rootDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `short oversized and changed-size downloads cannot activate a payload`() {
        val archive = compressedArchive("1.1.17")
        val expected = release(size = archive.size.toLong(), hash = archive.sha256())
        val responses = listOf(
            archive.copyOf(archive.size - 1) to expected.sizeBytes.toInt(),
            (archive + byteArrayOf(1)) to expected.sizeBytes.toInt(),
            archive to archive.size + 1,
        )
        for ((body, declaredLength) in responses) {
            val client = updater { url -> FakeHttpURLConnection(url, body, length = declaredLength) }
            assertThrows<IllegalArgumentException> { client.downloadRelease(expected, "1.1.16") }
            assertNull(cache().find("1.1.17"))
            assertTrue(cache().rootDirectory().listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `verified archive with mismatched payload version is still rejected`() {
        val archive = compressedArchive("1.1.18")
        val client = updater { url -> FakeHttpURLConnection(url, archive) }
        assertThrows<IllegalArgumentException> {
            client.downloadRelease(release(size = archive.size.toLong(), hash = archive.sha256()), "1.1.16")
        }
        assertNull(cache().find("1.1.17"))
    }

    @Test
    fun `incompatible payload formats are rejected before the asset request`() {
        val client = updater { throw AssertionError("Incompatible payload must not be downloaded") }
        assertThrows<IllegalArgumentException> { client.downloadRelease(release(version = "1.2.0"), "1.1.16") }
    }

    @Test
    fun `validated cached payload remains usable without a request`() {
        writeCachedPayload(cache(), "1.1.17")
        val client = updater { throw AssertionError("Cache reuse must be offline") }
        assertEquals("1.1.17", client.downloadRelease(release(), "1.1.16").version)
    }

    @Test
    fun `loads validated cached payload and chooses newest compatible version`() {
        val cache = cache()
        writeCachedPayload(cache, "1.1.16")
        writeCachedPayload(cache, "1.1.17")
        writeCachedPayload(cache, "1.2.0")
        assertEquals("1.2.0", cache.newest()?.version)
        assertEquals("1.1.17", cache.newestCompatible("1.1.16")?.version)
        assertNull(cache.find("1.1.18"))
    }

    @Test
    fun `extracts only required files and rejects a missing payload file`() {
        val files = payloadFiles("1.1.17")
        val destination = temporaryDirectory.resolve("payload").toFile().apply { mkdirs() }
        VentoyTarExtractor.extractRequiredPayload(
            ByteArrayInputStream(tarArchive(mapOf("ventoy-1.1.17/README" to byteArrayOf(99)) + files)), destination,
        )
        files.forEach { (path, expected) -> assertArrayEquals(expected, File(destination, path).readBytes()) }
        assertFalse(File(destination, "README").exists())
        assertThrows<IllegalArgumentException> {
            VentoyTarExtractor.extractRequiredPayload(
                ByteArrayInputStream(tarArchive(files - "boot/core.img.xz")),
                temporaryDirectory.resolve("incomplete").toFile().apply { mkdirs() },
            )
        }
    }

    private fun cache() = VentoyPayloadCache(temporaryDirectory.toFile())
    private fun updater(factory: (URL) -> HttpURLConnection = { throw AssertionError("No network expected") }) =
        VentoyOnlineUpdater(cache(), factory)

    private fun release(version: String = "1.1.17", size: Long = 123456, hash: String = "ab".repeat(32)) =
        VentoyOnlineRelease(version, "ventoy-$version-linux.tar.gz", "$origin/v1/assets/$hash/ventoy-$version-linux.tar.gz", size, hash)

    private fun metadata(version: String = "1.1.17", size: Long = 123456, hash: String = "ab".repeat(32), url: String? = null): String =
        JSONObject().put("schemaVersion", 1).put("version", version).put("assetName", "ventoy-$version-linux.tar.gz")
            .put("sizeBytes", size).put("sha256", hash)
            .put("downloadUrl", url ?: release(version, size, hash).downloadUrl).toString()

    private fun compressedArchive(version: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(tarArchive(payloadFiles(version))) }
    }.toByteArray()

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
        private val length: Int = response.size,
        private val responseCookie: String? = null,
    ) : HttpURLConnection(url) {
        var disconnected = false
        var bodyReads = 0
        var errorReads = 0
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = when (name) {
            "Location" -> location
            "Set-Cookie" -> responseCookie
            else -> null
        }
        override fun getErrorStream(): InputStream {
            errorReads++
            throw AssertionError("Server error bodies must not be consumed")
        }
        override fun getContentLength() = length
        override fun getInputStream(): InputStream {
            bodyReads++
            return ByteArrayInputStream(response)
        }
    }
}
