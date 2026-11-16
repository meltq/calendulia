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
import de.jeanlucmakiola.calendula.domain.backup.latestHeldElsewhere
import de.jeanlucmakiola.calendula.domain.backup.latestLedgerKey
import de.jeanlucmakiola.calendula.domain.backup.replacedLatestCopies
import de.jeanlucmakiola.calendula.domain.backup.withLedgerEntry
import de.jeanlucmakiola.calendula.domain.ics.IcsWriter
import de.jeanlucmakiola.floret.android.hasPermission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
     * The first run is delayed by one interval so it doesn't follow an immediate
     * [runNow] with a near-identical copy.
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
        val now = System.currentTimeMillis()
        // The periodic and the immediate run are separate unique works; one at
        // a time, or each would overwrite the other's ledger.
        val outcome = runLock.withLock {
            // Respect the toggle even for already-queued work, so a lingering
            // run can't revive itself.
            if (!prefs.autoBackupEnabled.first()) return Result.success()
            val folder = prefs.autoBackupFolderUri.first() ?: return Result.failure()
            try {
                runBackup(deps, prefs, folder.toUri())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Automatic backup failed", e)
                RunOutcome.Failed
            }
        }
        if (outcome == RunOutcome.NothingSelected) return Result.success()
        val success = outcome == RunOutcome.Written
        prefs.recordAutoBackupRun(success = success, atMillis = now)
        if (!success && prefs.autoBackupStatus.first().consecutiveFailures >= FAILURE_NOTIFY_THRESHOLD) {
            notifyFailure(applicationContext)
        }
        // Only a run that wrote nothing retries: a retry rewrites every series.
        return if (outcome == RunOutcome.Failed) Result.retry() else Result.success()
    }

    private enum class RunOutcome { Written, PartlyFailed, Failed, NothingSelected }

    /** Writes each series and prunes it only once its new file has landed. */
    private suspend fun runBackup(deps: Deps, prefs: SettingsPrefs, folder: Uri): RunOutcome {
        val repository = deps.repository()
        val exporter = deps.exporter()
        if (!applicationContext.hasPermission(Manifest.permission.READ_CALENDAR)) return RunOutcome.Failed
        val all = repository.calendars().first()
        val selection = prefs.autoBackupSelection.first()
        val combined = prefs.autoBackupCombined.first()
        if (all.none(selection::selects)) return RunOutcome.NothingSelected
        prefs.adoptLegacyBackupFile()
        val listing = exporter.listFolder(folder)
        val ledger = prefs.autoBackupLedger.first()
        val targets = backupTargets(all.filter(selection::selects), combined, ledger)
        // A listing still loading can't tell a gone file from a late one.
        val existing = if (listing.complete) listing.files.keys else listing.files.keys + ledger.values.flatten()
        val plans = planBackupFiles(
            targets = targets,
            ledger = ledger,
            existing = existing,
            at = LocalDateTime.now(),
            keep = prefs.autoBackupKeep.first(),
        )
        val stamp = Clock.System.now()
        val liveKeys = all.map { it.id.toString() }.toSet()
        suspend fun record(key: String, files: List<String>) =
            prefs.updateAutoBackupLedger(folder.toString()) { withLedgerEntry(it, key, files, liveKeys) }
        var written = 0
        val landedLatest = mutableSetOf<String>()
        for (plan in plans) {
            val target = plan.target
            val bytes = try {
                IcsWriter()
                    .writeCalendar(repository.exportEvents(target.calendarIds), stamp, target.calendarName)
                    .toByteArray(Charsets.UTF_8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading one series failed", e)
                continue
            }
            // Not cancellable once a file exists: it must reach the ledger, or
            // nothing would ever prune it.
            withContext(NonCancellable) {
                try {
                    val name = exporter.writeToFolder(folder, listing, plan.fileName, bytes)
                    record(target.key, plan.prune + plan.keep + name)
                    val undeleted = exporter.deleteFromFolder(listing, plan.prune)
                    record(target.key, plan.prune.filter { it in undeleted } + plan.keep + name)
                    written++
                } catch (e: Exception) {
                    Log.w(TAG, "Backup of one series failed", e)
                }
                // Best-effort: the stable copy never holds up the dated backup,
                // its pruning or the run's status. An old copy goes only once the
                // new one has landed, so a series is never left without one.
                try {
                    val latest = plan.latestName ?: return@withContext
                    val latestKey = latestLedgerKey(target.key)
                    val landed = exporter.writeToFolder(folder, listing, latest, bytes)
                    // A provider that renamed it leaves a copy to clean up next
                    // run; the intended name stays ours only if it already was.
                    val ownsLatest = landed == latest || latest in ledger[latestKey].orEmpty()
                    val current = listOfNotNull(landed, latest.takeIf { ownsLatest })
                    // A hand-off stays on record only until its new owner claimed it.
                    suspend fun recordLatest(stale: List<String>) = prefs.updateAutoBackupLedger(folder.toString()) {
                        val pending = plan.handOff - it.latestHeldElsewhere(target.key)
                        withLedgerEntry(it, latestKey, stale + pending + current, liveKeys)
                    }
                    recordLatest(plan.staleLatest)
                    if (landed == latest) landedLatest += target.key
                    val staleLeft = exporter.deleteFromFolder(listing, plan.staleLatest)
                    recordLatest(plan.staleLatest.filter { it in staleLeft })
                } catch (e: Exception) {
                    Log.w(TAG, "Stable copy of one series failed", e)
                }
            }
        }
        // A layout switch retires the other layout's copies once every new one is there.
        if (landedLatest.size == plans.size) {
            replacedLatestCopies(targets, prefs.autoBackupLedger.first(), existing).forEach { (key, names) ->
                withContext(NonCancellable) {
                    val left = exporter.deleteFromFolder(listing, names)
                    record(latestLedgerKey(key), names.filter { it in left })
                }
            }
        }
        return when (written) {
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
        private val runLock = Mutex()
    }
}
