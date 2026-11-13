package de.jeanlucmakiola.calendula.data.ics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android IO edge of `.ics` export: writes a serialised calendar to a
 * SAF document (whole-calendar backup) or stages it in a cache file behind a
 * `FileProvider` content Uri (single-event share). The serialisation itself is
 * the pure `domain.ics.IcsWriter`; this class only moves bytes.
 */
@Singleton
class IcsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Write [content] to the SAF document at [uri] as UTF-8. Throws on failure. */
    fun writeDocument(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            // Only the scheme — the full Uri can embed the user's chosen filename.
        } ?: throw IOException("Could not open output stream for export (scheme=${uri.scheme})")
    }

    /**
     * The files directly inside the persisted SAF tree [folder] (the
     * automatic-backup destination), by display name — one provider query, so
     * a run over many calendars doesn't pay a lookup per file. Throws when the
     * folder is gone or not writable.
     */
    fun listFolder(folder: Uri): Map<String, Uri> {
        writableFolder(folder)
        val treeId = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = context.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("Backup folder can't be listed")
        return cursor.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    put(name, DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0)))
                }
            }
        }
    }

    /**
     * Write [content] to [fileName] in [folder], overwriting the file of that
     * name if [listing] (from [listFolder]) has one. Returns the name the file
     * ended up with — a provider may de-duplicate it. Throws on failure.
     */
    fun writeToFolder(folder: Uri, listing: Map<String, Uri>, fileName: String, content: String): String {
        listing[fileName]?.let { uri ->
            writeDocument(uri, content)
            return fileName
        }
        val uri = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder)),
            MIME_CALENDAR,
            fileName,
        ) ?: throw IOException("Could not create backup file in the chosen folder")
        writeDocument(uri, content)
        return DocumentFile.fromSingleUri(context, uri)?.name ?: fileName
    }

    /** Delete the [names] files of [listing]; returns the ones that wouldn't go. */
    fun deleteFromFolder(listing: Map<String, Uri>, names: Collection<String>): Set<String> =
        names.filterTo(mutableSetOf()) { name ->
            val uri = listing[name] ?: return@filterTo false
            !runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        }

    private fun writableFolder(folder: Uri): DocumentFile {
        val dir = DocumentFile.fromTreeUri(context, folder)
            ?: throw IOException("Backup folder is not accessible")
        if (!dir.exists() || !dir.canWrite()) {
            throw IOException("Backup folder is missing or not writable")
        }
        return dir
    }

    /**
     * Stage [content] in a private cache file and return a shareable content
     * Uri for an `ACTION_SEND`. [fileName] is the suggested `.ics` name shown to
     * the receiving app. The authority mirrors the manifest's `FileProvider`.
     */
    fun stageShareFile(fileName: String, content: String): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val SHARE_DIR = "shared_ics"
        const val MIME_CALENDAR = "text/calendar"
    }
}
