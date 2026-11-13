package de.jeanlucmakiola.calendula.data.ics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.floret.android.TreeListing
import de.jeanlucmakiola.floret.android.deleteTreeFiles
import de.jeanlucmakiola.floret.android.listTreeFiles
import de.jeanlucmakiola.floret.android.writeTreeFile
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

    /** The files in the persisted SAF tree [folder], the automatic-backup destination. Throws when unusable. */
    fun listFolder(folder: Uri): TreeListing {
        writableFolder(folder)
        return context.contentResolver.listTreeFiles(folder)
    }

    /** Write [content] as [fileName] into [folder]; returns the name the file ended up with. */
    fun writeToFolder(folder: Uri, listing: TreeListing, fileName: String, content: String): String =
        context.contentResolver.writeTreeFile(
            tree = folder,
            listing = listing,
            name = fileName,
            mimeType = MIME_CALENDAR,
            bytes = content.toByteArray(Charsets.UTF_8),
        )

    /** Delete [names] from the listed folder; returns the ones that didn't go. */
    fun deleteFromFolder(listing: TreeListing, names: Collection<String>): Set<String> =
        context.contentResolver.deleteTreeFiles(listing, names)

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
