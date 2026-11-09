package com.example.vibereader.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.vibereader.util.FilenameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class ScannedBook(
    val uri: Uri,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val source: String,
    val filePath: String? = null,
)

data class DeviceScanResult(
    val books: List<ScannedBook>,
    val completedSources: Set<String>,
    val warnings: List<String>,
)

class DeviceBookScanner(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    suspend fun scan(treeUri: Uri? = null): DeviceScanResult = withContext(Dispatchers.IO) {
        val books = linkedMapOf<String, ScannedBook>()
        val completedSources = mutableSetOf<String>()
        val warnings = mutableListOf<String>()

        try {
            books.putAll(scanMediaStore())
            completedSources += MEDIA_STORE_SOURCE
        } catch (error: SecurityException) {
            warnings += "MediaStore access was denied: ${error.localizedMessage ?: "permission denied"}"
        } catch (error: Exception) {
            warnings += "MediaStore scan failed: ${error.localizedMessage ?: "unknown error"}"
        }

        if (treeUri != null) {
            val source = treeSource(treeUri)
            try {
                scanTree(treeUri, source).forEach { scanned ->
                    books.putIfAbsent(scanned.uri.toString(), scanned)
                }
                completedSources += source
            } catch (error: SecurityException) {
                warnings += "Folder access was revoked. Choose the folder again to scan it."
            } catch (error: Exception) {
                warnings += "Folder scan failed: ${error.localizedMessage ?: "unknown error"}"
            }
        }

        DeviceScanResult(
            books = books.values.toList(),
            completedSources = completedSources,
            warnings = warnings,
        )
    }

    private fun scanMediaStore(): Map<String, ScannedBook> {
        val collection = MediaStore.Files.getContentUri("external")
        val dataPath = MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            dataPath,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = "$dataPath LIKE ? OR $dataPath LIKE ?"
        val selectionArgs = arrayOf("%.pdf", "%.epub")
        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
        val cursor = try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
        } catch (_: IllegalArgumentException) {
            null
        } ?: queryByDisplayName(collection, projection, selectionArgs, sortOrder)
            ?: throw IOException("MediaStore did not return a file cursor.")

        val result = linkedMapOf<String, ScannedBook>()
        cursor.use {
            Log.d(TAG, "Found ${it.count} books")
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathColumn = it.getColumnIndex(dataPath)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn)?.takeIf(String::isNotBlank) ?: continue
                val format = resolveFormat(name, null) ?: continue
                val uri = ContentUris.withAppendedId(collection, it.getLong(idColumn))
                result[uri.toString()] = ScannedBook(
                    uri = uri,
                    title = FilenameSanitizer.cleanBookTitle(name).ifBlank { "Untitled Book" },
                    format = format,
                    sizeBytes = it.getLong(sizeColumn).coerceAtLeast(0),
                    modifiedAt = it.getLong(modifiedColumn).coerceAtLeast(0) * 1_000,
                    source = MEDIA_STORE_SOURCE,
                    filePath = pathColumn.takeIf { index -> index >= 0 }
                        ?.let(it::getString)
                        ?.takeIf(String::isNotBlank),
                )
            }
        }
        return result
    }

    private fun queryByDisplayName(
        collection: Uri,
        projection: Array<String>,
        selectionArgs: Array<String>,
        sortOrder: String,
    ) = contentResolver.query(
        collection,
        projection.filterNot { it == MediaStore.Files.FileColumns.DATA }.toTypedArray(),
        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ? OR " +
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?",
        selectionArgs,
        sortOrder,
    )

    private fun scanTree(treeUri: Uri, source: String): List<ScannedBook> {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("The selected folder is no longer available.")
        if (!root.canRead()) throw SecurityException("The selected folder is not readable.")
        val results = mutableListOf<ScannedBook>()
        val pending = ArrayDeque<Pair<DocumentFile, Int>>()
        pending.add(root to 0)
        var visited = 0

        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            if (depth > MAX_TREE_DEPTH) {
                throw IOException("Folder nesting is too deep; scan stopped to protect the device.")
            }
            for (file in directory.listFiles()) {
                visited += 1
                if (visited > MAX_TREE_ENTRIES) {
                    throw IOException("The selected folder has too many items to scan at once.")
                }
                if (file.isDirectory) {
                    pending.add(file to depth + 1)
                    continue
                }
                val name = file.name?.takeIf(String::isNotBlank) ?: continue
                val format = resolveFormat(name, file.type) ?: continue
                results += ScannedBook(
                    uri = file.uri,
                    title = FilenameSanitizer.cleanBookTitle(name).ifBlank { "Untitled Book" },
                    format = format,
                    sizeBytes = file.length().coerceAtLeast(0),
                    modifiedAt = file.lastModified().coerceAtLeast(0),
                    source = source,
                )
            }
        }
        return results
    }

    private fun resolveFormat(name: String, mimeType: String?): String? = when {
        name.endsWith(".pdf", ignoreCase = true) || mimeType == PDF_MIME_TYPE -> "PDF"
        name.endsWith(".epub", ignoreCase = true) || mimeType == EPUB_MIME_TYPE -> "EPUB"
        else -> null
    }

    companion object {
        private const val TAG = "VibeReaderScan"
        const val MEDIA_STORE_SOURCE = "media_store"
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val EPUB_MIME_TYPE = "application/epub+zip"
        private const val MAX_TREE_ENTRIES = 20_000
        private const val MAX_TREE_DEPTH = 32

        fun treeSource(uri: Uri): String = "tree:$uri"
    }
}
