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
import de.jeanlucmakiola.calendula.domain.backup.backupTargets
import de.jeanlucmakiola.calendula.domain.backup.planBackupFiles
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
        return try {
            runBackup(deps, prefs, folder.toUri())
            prefs.recordAutoBackupRun(success = true, atMillis = now)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.recordAutoBackupRun(success = false, atMillis = now)
            if (prefs.autoBackupStatus.first().consecutiveFailures >= FAILURE_NOTIFY_THRESHOLD) {
                notifyFailure(applicationContext)
            }
            Log.w(TAG, "Automatic backup failed", e)
            Result.retry()
        }
    }

    /**
     * Writes every file first and prunes afterwards, and only a series whose
     * new file landed, so a failed run never costs an older backup. Throws
     * after the rest are done if any series failed.
     */
    private suspend fun runBackup(deps: Deps, prefs: SettingsPrefs, folder: Uri) {
        val repository = deps.repository()
        val exporter = deps.exporter()
        val selection = prefs.autoBackupSelection.first()
        val calendars = repository.calendars().first().filter(selection::selects)
        val targets = backupTargets(calendars, combined = prefs.autoBackupCombined.first())
        val plans = planBackupFiles(
            targets = targets,
            existing = exporter.fileNames(folder),
            at = LocalDateTime.now(),
            keep = prefs.autoBackupKeep.first(),
        )
        val stamp = Clock.System.now()
        var failure: Exception? = null
        for (plan in plans) {
            try {
                val events = repository.exportEvents(plan.target.calendarIds)
                val content = IcsWriter().writeCalendar(events, stamp, plan.target.calendarName)
                exporter.writeToFolder(folder, plan.fileName, content)
                exporter.deleteFromFolder(folder, plan.prune)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup of one calendar failed", e)
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
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
