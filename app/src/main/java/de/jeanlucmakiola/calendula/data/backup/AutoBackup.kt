package de.jeanlucmakiola.calendula.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.ics.IcsExporter
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.backup.BackupFilePlan
import de.jeanlucmakiola.calendula.domain.backup.backupTargets
import de.jeanlucmakiola.calendula.domain.backup.planBackupFiles
import de.jeanlucmakiola.calendula.domain.backup.updatedBackupBases
import de.jeanlucmakiola.calendula.domain.ics.IcsWriter
import de.jeanlucmakiola.floret.android.hasPermission
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Schedules and runs the automatic periodic `.ics` backup (issue #8) of the
 * calendars the user picked (#147). One-way export only — it mirrors the manual
 * "Back up" into a user-chosen folder on an interval; it is not a sync. Stays
 * INTERNET-free: everything is local provider reads + local file writes.
 */
object BackupScheduler {

    private const val WORK_NAME = "auto-backup"
    private const val WORK_NAME_NOW = "auto-backup-now"

    /**
     * Reconcile the scheduled work with the current settings: enqueue a unique
     * periodic request when backup is on and a folder is set, otherwise cancel
     * it. WorkManager persists the request across reboots on its own.
     *
     * The first run is delayed by one interval so it never overlaps an immediate
     * [runNow] (two simultaneous writes would race and leave a "(1)" duplicate).
     */
    fun apply(context: Context, enabled: Boolean, intervalMinutes: Long, hasFolder: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled || !hasFolder) {
            workManager.cancelUniqueWork(WORK_NAME)
            // Also cancel any pending immediate run — otherwise a failing run-now
            // keeps retrying after the user has turned backup off.
            workManager.cancelUniqueWork(WORK_NAME_NOW)
            return
        }
        // WorkManager's own floor is 15 min; the UI floors the user choice at 30.
        val interval = intervalMinutes.coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.MINUTES)
            .setInitialDelay(interval, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Run one export immediately (e.g. right after enabling or changing the
     * folder) for instant feedback. Unique + REPLACE so rapid taps coalesce into
     * a single run rather than racing each other.
     */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }
}

/**
 * Exports the selected calendars into the configured folder — one dated file
 * per calendar, or one combined file (#411) — then prunes each series down to
 * the retention count. Pulls its collaborators through a Hilt
 * [EntryPoint] so it works under WorkManager's default (no-arg) worker factory —
 * no custom factory / Application wiring needed. Records the outcome for the
 * settings status line, and notifies after repeated failures.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settingsPrefs(): SettingsPrefs
        fun repository(): CalendarRepository
        fun exporter(): IcsExporter
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val prefs = deps.settingsPrefs()
        // Respect the toggle even for already-queued work: if backup was turned
        // off, no-op (and don't retry) so a lingering run can't revive itself.
        if (!prefs.autoBackupEnabled.first()) return Result.success()
        val folder = prefs.autoBackupFolderUri.first()
            ?: return Result.failure() // nothing to write to — leave scheduling to settings
        val now = System.currentTimeMillis()
        val outcome = try {
            runBackup(deps, prefs, folder.toUri())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic backup failed", e)
            RunOutcome.Failed
        }
        if (outcome == RunOutcome.NothingSelected) return Result.success()
        val success = outcome == RunOutcome.Written
        prefs.recordAutoBackupRun(success = success, atMillis = now)
        if (!success && prefs.autoBackupStatus.first().consecutiveFailures >= FAILURE_NOTIFY_THRESHOLD) {
            notifyFailure(applicationContext)
        }
        // A retry rewrites every series, so only a run that wrote nothing is
        // retried; a partial one waits for the next interval rather than
        // rotating the healthy calendars' history out with near-identical copies.
        return if (outcome == RunOutcome.Failed) Result.retry() else Result.success()
    }

    private enum class RunOutcome { Written, PartlyFailed, Failed, NothingSelected }

    /**
     * Writes each series and prunes it only once its new file has landed, so
     * a failed write never costs an older backup.
     */
    private suspend fun runBackup(deps: Deps, prefs: SettingsPrefs, folder: Uri): RunOutcome {
        val repository = deps.repository()
        val exporter = deps.exporter()
        val all = repository.calendars().first()
        // Without the calendar permission the list comes back empty: a failure,
        // not an empty selection.
        if (all.isEmpty()) return RunOutcome.Failed
        val selection = prefs.autoBackupSelection.first()
        val previousBases = prefs.autoBackupSeriesNames.first()
        val targets = backupTargets(
            calendars = all.filter(selection::selects),
            combined = prefs.autoBackupCombined.first(),
            previousBases = previousBases,
        )
        if (targets.isEmpty()) return RunOutcome.NothingSelected
        val listing = exporter.listFolder(folder)
        val plans = planBackupFiles(
            targets = targets,
            existing = listing.keys,
            at = LocalDateTime.now(),
            keep = prefs.autoBackupKeep.first(),
        )
        val stamp = Clock.System.now()
        val written = mutableListOf<BackupFilePlan>()
        for (plan in plans) {
            try {
                val events = repository.exportEvents(plan.target.calendarIds)
                val content = IcsWriter().writeCalendar(events, stamp, plan.target.calendarName)
                exporter.writeToFolder(folder, listing, plan.fileName, content)
                written += plan
                exporter.deleteDocuments(plan.prune.mapNotNull(listing::get))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup of one series failed", e)
            }
        }
        prefs.setAutoBackupSeriesNames(updatedBackupBases(previousBases, written))
        return when (written.size) {
            plans.size -> RunOutcome.Written
            0 -> RunOutcome.Failed
            else -> RunOutcome.PartlyFailed
        }
    }

    private fun notifyFailure(context: Context) {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (!canPost) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.backup_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.backup_channel_description) },
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.backup_failed_title))
            .setContentText(context.getString(R.string.backup_failed_text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .apply { tap?.let(::setContentIntent) }
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post backup-failure notification", e)
        }
    }

    companion object {
        private const val FAILURE_NOTIFY_THRESHOLD = 2
        private const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "BackupWorker"
    }
}
