package io.github.xntso.vendroid.ventoy

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

data class IsoFileEntry(
    val name: String,
    val uri: Uri,
    val size: Long,
)

interface VentoyDocumentNode {
    val name: String
    val uri: Uri
    val isFile: Boolean
    val isDirectory: Boolean
    val length: Long

    fun listFiles(): List<VentoyDocumentNode>
    fun createFile(mimeType: String, displayName: String): VentoyDocumentNode?
    fun delete(): Boolean
}

class AndroidVentoyDocumentNode(
    private val documentFile: DocumentFile,
) : VentoyDocumentNode {
    override val name: String
        get() = documentFile.name.orEmpty()
    override val uri: Uri
        get() = documentFile.uri
    override val isFile: Boolean
        get() = documentFile.isFile
    override val isDirectory: Boolean
        get() = documentFile.isDirectory
    override val length: Long
        get() = documentFile.length()

    override fun listFiles(): List<VentoyDocumentNode> =
        documentFile.listFiles().map(::AndroidVentoyDocumentNode)

    override fun createFile(mimeType: String, displayName: String): VentoyDocumentNode? =
        documentFile.createFile(mimeType, displayName)?.let(::AndroidVentoyDocumentNode)

    override fun delete(): Boolean = documentFile.delete()
}

class VentoyVolumeManager(
    private val root: VentoyDocumentNode,
    private val openInputStream: (Uri) -> InputStream?,
    private val openOutputStream: (Uri) -> OutputStream?,
) {
    fun listImages(): List<IsoFileEntry> =
        root.walk()
            .filter { it.isFile && it.name.isSupportedImageName() }
            .map { IsoFileEntry(it.name, it.uri, it.length) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .toList()

    fun copyImage(
        sourceUri: Uri,
        displayName: String,
        onProgress: (processedBytes: Long) -> Unit = {},
    ): IsoFileEntry {
        require(displayName.isSupportedImageName()) {
            "Unsupported image type. Supported: .iso, .img, .wim, .vhd, .vhdx, .efi"
        }
        val target = root.createFile("application/octet-stream", displayName)
            ?: throw IllegalStateException("Could not create $displayName on the Ventoy volume.")

        val input = openInputStream(sourceUri)
            ?: throw IllegalStateException("Could not open source image.")
        val output = openOutputStream(target.uri)
            ?: throw IllegalStateException("Could not open target image for writing.")

        input.use { source ->
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var processed = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    destination.write(buffer, 0, read)
                    processed += read
                    onProgress(processed)
                }
                destination.flush()
            }
        }
        return IsoFileEntry(target.name, target.uri, target.length)
    }

    fun deleteImage(entry: IsoFileEntry): Boolean {
        val match = root.walk().firstOrNull { it.isFile && it.uri == entry.uri }
            ?: return false
        return match.delete()
    }

    companion object {
        fun fromTreeUri(context: Context, treeUri: Uri): VentoyVolumeManager? {
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory }
                ?: return null
            val resolver = context.contentResolver
            return VentoyVolumeManager(
                root = AndroidVentoyDocumentNode(root),
                openInputStream = resolver::openInputStream,
                openOutputStream = { uri -> resolver.openOutputStream(uri, "wt") },
            )
        }
    }
}

private fun VentoyDocumentNode.walk(): Sequence<VentoyDocumentNode> = sequence {
    yield(this@walk)
    if (isDirectory) {
        listFiles().forEach { child ->
            yieldAll(child.walk())
        }
    }
}

private fun String.isSupportedImageName(): Boolean {
    val lower = lowercase(Locale.US)
    return lower.endsWith(".iso") ||
        lower.endsWith(".img") ||
        lower.endsWith(".wim") ||
        lower.endsWith(".vhd") ||
        lower.endsWith(".vhdx") ||
        lower.endsWith(".efi")
}
