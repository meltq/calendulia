package de.jeanlucmakiola.calendula.ui.calendars

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.data.backup.SettingsBackupEngine
import de.jeanlucmakiola.calendula.data.backup.SettingsImportSummary
import de.jeanlucmakiola.floret.android.DocumentTooLargeException
import de.jeanlucmakiola.floret.android.readDocumentBytes
import de.jeanlucmakiola.floret.android.writeDocumentBytes
import de.jeanlucmakiola.floret.backup.BackupFormat
import de.jeanlucmakiola.floret.backup.BackupProblem
import de.jeanlucmakiola.floret.backup.BackupResult
import de.jeanlucmakiola.floret.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The outcome of a settings export or import, held until the screen has shown it. */
sealed interface SettingsBackupResult {
    data object Exported : SettingsBackupResult
    data object ExportFailed : SettingsBackupResult
    data class Imported(val summary: SettingsImportSummary) : SettingsBackupResult
    data class ImportFailed(val problem: BackupProblem) : SettingsBackupResult
}

/**
 * Settings export/import on the Backup & restore screen (#481). The file is a
 * single SAF document the user picked; the engine knows nothing about it.
 * The result is state rather than an event, so an import that changes the
 * app language — and recreates the activity — still shows its summary.
 */
@HiltViewModel
class SettingsBackupViewModel @Inject constructor(
    private val engine: SettingsBackupEngine,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _result = MutableStateFlow<SettingsBackupResult?>(null)
    val result: StateFlow<SettingsBackupResult?> = _result.asStateFlow()

    fun export(uri: Uri) = launchWork {
        try {
            val text = engine.export()
            withContext(io) { context.contentResolver.writeDocumentBytes(uri, text.toByteArray(Charsets.UTF_8)) }
            SettingsBackupResult.Exported
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsBackupResult.ExportFailed
        }
    }

    /** Call only once the user has confirmed replacing their settings. */
    fun import(uri: Uri) = launchWork {
        val text = try {
            withContext(io) {
                context.contentResolver.readDocumentBytes(uri, BackupFormat.DEFAULT_MAX_CHARS).toString(Charsets.UTF_8)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentTooLargeException) {
            return@launchWork SettingsBackupResult.ImportFailed(BackupProblem.TOO_LARGE)
        } catch (e: Exception) {
            return@launchWork SettingsBackupResult.ImportFailed(BackupProblem.UNREADABLE)
        }
        try {
            when (val imported = engine.import(text)) {
                is BackupResult.Success -> SettingsBackupResult.Imported(imported.value)
                is BackupResult.Failure -> SettingsBackupResult.ImportFailed(imported.problem)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsBackupResult.ImportFailed(BackupProblem.NOT_APPLIED)
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    private fun launchWork(work: suspend () -> SettingsBackupResult) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _result.value = work()
            } finally {
                _busy.value = false
            }
        }
    }
}
