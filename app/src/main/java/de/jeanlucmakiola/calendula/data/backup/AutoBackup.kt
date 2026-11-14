package de.jeanlucmakiola.calendula.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.ics.IcsExporter
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.backup.BackupFilePlan
import de.jeanlucmakiola.calendula.domain.backup.backupFingerprint
import de.jeanlucmakiola.calendula.domain.backup.backupTargets
import de.jeanlucmakiola.calendula.domain.backup.inBackupOrder
import de.jeanlucmakiola.calendula.domain.backup.needsRefresh
import de.jeanlucmakiola.calendula.domain.backup.withFingerprint
import de.jeanlucmakiola.calendula.domain.backup.planBackupFiles
import de.jeanlucmakiola.calendula.domain.backup.latestHeldElsewhere
import de.jeanlucmakiola.calendula.domain.backup.latestLedgerKey
import de.jeanlucmakiola.calendula.domain.backup.replacedLatestCopies
import de.jeanlucmakiola.calendula.domain.backup.withLedgerEntry
import de.jeanlucmakiola.calendula.domain.ics.IcsWriter
import de.jeanlucmakiola.floret.android.hasPermission
import kotlinx.coroutines.NonCancellable
import androidx.work.await
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
    private val CHANGE_SLOTS = listOf("auto-backup-changes-a", "auto-backup-changes-b")
    internal const val KEY_CHANGE_SLOT = "change_slot"

    /** Quiet time after the last provider change before a change run starts, so a burst makes one run. */
    private const val CHANGE_SETTLE_MINUTES = 2L

    /** Longest a change run waits on a provider that never goes quiet (a long sync). */
    private const val CHANGE_MAX_WAIT_MINUTES = 15L

    private val reconcileLock = Mutex()

    /**
     * Reconcile the scheduled work with the saved settings: enqueue a unique
     * periodic request when backup is on and a folder is set, otherwise cancel
     * it. WorkManager persists the request across reboots on its own.
     *
     * The first run is delayed by one interval so it doesn't follow an immediate
     * [runNow] with a near-identical copy. With backing up after changes on
     * (#516), the change trigger is armed unless a slot is already waiting.
     * Serialised and not cancellable, so quick toggles settle on the last one.
     */
    suspend fun reconcile(context: Context, prefs: SettingsPrefs) = withContext(NonCancellable) {
        reconcileLock.withLock {
            val workManager = WorkManager.getInstance(context)
            suspend fun cancelWatch() = CHANGE_SLOTS.forEach { workManager.cancelUniqueWork(it).await() }
            if (!prefs.autoBackupEnabled.first() || prefs.autoBackupFolderUri.first() == null) {
                workManager.cancelUniqueWork(WORK_NAME)
                // Also cancel any pending immediate run — otherwise a failing run-now
                // keeps retrying after the user has turned backup off.
                workManager.cancelUniqueWork(WORK_NAME_NOW)
                cancelWatch()
                return@withLock
            }
            // WorkManager's own floor is 15 min; the UI floors the user choice at 30.
            val interval = prefs.autoBackupIntervalMinutes.first().coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.MINUTES)
                .setInitialDelay(interval, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            if (!prefs.autoBackupOnChange.first()) {
                cancelWatch()
                return@withLock
            }
            val watching = CHANGE_SLOTS.any { name ->
                workManager.getWorkInfosForUniqueWorkFlow(name).first().any { !it.state.isFinished }
            }
            if (!watching) watchChanges(context, slot = 0)
        }
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

    /**
     * Arm a one-off run on the next change in the calendar provider (#516) in
     * one of two [slot]s. The provider reports every change on its root URI,
     * whoever made it (this app, a sync adapter or another app), so that is the
     * URI watched. A content trigger fires once and observes only while its
     * job waits, so a change run arms the other slot as it starts, catching
     * changes made during the run, and again as it ends in case that slot was
     * still busy. A slot already waiting or running is kept, so while two
     * change runs overlap nothing watches until the second one ends.
     */
    internal fun watchChanges(context: Context, slot: Int) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(CHANGE_SETTLE_MINUTES, TimeUnit.MINUTES)
            .setTriggerContentMaxDelay(CHANGE_MAX_WAIT_MINUTES, TimeUnit.MINUTES)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_CHANGE_SLOT to slot))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGE_SLOTS[slot], ExistingWorkPolicy.KEEP, request)
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
        val slot = inputData.getInt(BackupScheduler.KEY_CHANGE_SLOT, -1)
        if (slot < 0) return backUp(deps, prefs, changeRun = false)
        suspend fun watchNext() {
            val wanted = prefs.autoBackupEnabled.first() && prefs.autoBackupOnChange.first() &&
                prefs.autoBackupFolderUri.first() != null
            if (wanted) BackupScheduler.watchChanges(applicationContext, slot = 1 - slot)
        }
        runCatching { watchNext() }.onFailure { Log.w(TAG, "Arming the next change run failed", it) }
        // Never fails or retries: a retry would only wait for another change.
        try {
            backUp(deps, prefs, changeRun = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backup after a change failed", e)
        } finally {
            withContext(NonCancellable) { runCatching { watchNext() } }
        }
        return Result.success()
    }

    /**
     * One run under the shared lock. A change run (#516) refreshes only the
     * stable copies and stays off the status line and the failure count, which
     * report on the scheduled backup; a broken folder fails that one too.
     */
    private suspend fun backUp(deps: Deps, prefs: SettingsPrefs, changeRun: Boolean): Result {
        val now = System.currentTimeMillis()
        // The periodic, the immediate and the change runs are separate unique
        // works; one at a time, or each would overwrite the other's ledger.
        val outcome = runLock.withLock {
            // Respect the toggles even for already-queued work, so a lingering
            // run can't revive itself.
            if (!prefs.autoBackupEnabled.first()) return Result.success()
            if (changeRun && !prefs.autoBackupOnChange.first()) return Result.success()
            val folder = prefs.autoBackupFolderUri.first() ?: return Result.failure()
            try {
                runBackup(deps, prefs, folder.toUri(), latestOnly = changeRun)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Automatic backup failed", e)
                RunOutcome.Failed
            }
        }
        if (changeRun || outcome == RunOutcome.NothingToDo) return Result.success()
        val success = outcome == RunOutcome.Written
        prefs.recordAutoBackupRun(success = success, atMillis = now)
        if (!success && prefs.autoBackupStatus.first().consecutiveFailures >= FAILURE_NOTIFY_THRESHOLD) {
            notifyFailure(applicationContext)
        }
        // Only a run that wrote nothing retries: a retry rewrites every series.
        return if (outcome == RunOutcome.Failed) Result.retry() else Result.success()
    }

    private enum class RunOutcome { Written, PartlyFailed, Failed, NothingToDo }

    /**
     * Writes each series and prunes it only once its new file has landed. A
     * [latestOnly] run (#516) leaves the dated files and their retention to the
     * schedule and rewrites only the stable copies whose calendars changed;
     * its outcome isn't reported.
     */
    private suspend fun runBackup(deps: Deps, prefs: SettingsPrefs, folder: Uri, latestOnly: Boolean): RunOutcome {
        val repository = deps.repository()
        val exporter = deps.exporter()
        if (!applicationContext.hasPermission(Manifest.permission.READ_CALENDAR)) return RunOutcome.Failed
        val all = repository.calendars().first()
        val selection = prefs.autoBackupSelection.first()
        val targets = backupTargets(all.filter(selection::selects), combined = prefs.autoBackupCombined.first())
        if (targets.isEmpty()) return RunOutcome.NothingToDo
        prefs.adoptLegacyBackupFile()
        val listing = exporter.listFolder(folder)
        val ledger = prefs.autoBackupLedger.first()
        val fingerprints = prefs.autoBackupFingerprints.first()
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

        suspend fun writeDated(plan: BackupFilePlan, bytes: ByteArray): Boolean = try {
            val name = exporter.writeToFolder(folder, listing, plan.fileName, bytes)
            record(plan.target.key, plan.prune + plan.keep + name)
            val undeleted = exporter.deleteFromFolder(listing, plan.prune)
            record(plan.target.key, plan.prune.filter { it in undeleted } + plan.keep + name)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Backup of one series failed", e)
            false
        }

        // Whether the stable copy landed under its own name. An old copy goes
        // only once the new one has landed, so a series is never left without one.
        suspend fun writeLatest(plan: BackupFilePlan, bytes: ByteArray, fingerprint: String): Boolean = try {
            val target = plan.target
            val latest = checkNotNull(plan.latestName)
            val latestKey = latestLedgerKey(target.key)
            val landed = exporter.writeToFolder(folder, listing, latest, bytes)
            // A provider that renamed it leaves a copy to clean up next
            // run; the intended name stays ours only if it already was.
            val ownsLatest = landed == latest || latest in ledger[latestKey].orEmpty()
            val current = listOfNotNull(landed, latest.takeIf { ownsLatest })
            // A hand-off stays on record only until its new owner claimed it.
            suspend fun recordLatest(stale: List<String>, landedFingerprint: String? = null) =
                prefs.updateAutoBackupLedger(
                    folder.toString(),
                    fingerprints = { old ->
                        landedFingerprint?.let { withFingerprint(old, target.key, it, liveKeys) } ?: old
                    },
                ) {
                    val pending = plan.handOff - it.latestHeldElsewhere(target.key)
                    withLedgerEntry(it, latestKey, stale + pending + current, liveKeys)
                }
            recordLatest(plan.staleLatest, fingerprint.takeIf { landed == latest })
            val staleLeft = exporter.deleteFromFolder(listing, plan.staleLatest)
            recordLatest(plan.staleLatest.filter { it in staleLeft })
            landed == latest
        } catch (e: Exception) {
            Log.w(TAG, "Stable copy of one series failed", e)
            // The copy may be half-written now: the next change run must not take it as current.
            runCatching { prefs.dropAutoBackupFingerprint(folder.toString(), plan.target.key) }
            false
        }

        var written = 0
        var failed = 0
        val landedLatest = mutableSetOf<String>()
        for (plan in plans) {
            val target = plan.target
            if (latestOnly && plan.latestName == null) continue
            val text = try {
                val events = repository.exportEvents(target.calendarIds).inBackupOrder()
                IcsWriter().writeCalendar(events, stamp, target.calendarName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading one series failed", e)
                failed++
                continue
            }
            val fingerprint = backupFingerprint(text)
            if (latestOnly && !plan.needsRefresh(fingerprint, fingerprints, listing.files.keys, listing.complete)) {
                if (plan.latestName in listing.files) landedLatest += target.key
                continue
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            // Not cancellable once a file exists: it must reach the ledger, or
            // nothing would ever prune it.
            withContext(NonCancellable) {
                if (!latestOnly) {
                    if (writeDated(plan, bytes)) written++ else failed++
                }
                // Best-effort in a full run: it never holds up the run's status.
                if (plan.latestName != null && writeLatest(plan, bytes, fingerprint)) landedLatest += target.key
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
        return when {
            failed == 0 && written == 0 -> RunOutcome.NothingToDo
            failed == 0 -> RunOutcome.Written
            written == 0 -> RunOutcome.Failed
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
