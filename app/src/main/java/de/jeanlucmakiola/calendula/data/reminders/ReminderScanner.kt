package de.jeanlucmakiola.calendula.data.reminders

import android.Manifest
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.calendar.withoutProviderDefaults
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefs
import de.jeanlucmakiola.calendula.data.prefs.ReminderStatePrefs
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.reminders.DailyDigestSettings
import de.jeanlucmakiola.calendula.domain.reminders.digestEntries
import de.jeanlucmakiola.calendula.domain.reminders.planReminders
import de.jeanlucmakiola.calendula.domain.reminders.scheduleDigest
import de.jeanlucmakiola.calendula.domain.reminders.reminderQueryHorizon
import de.jeanlucmakiola.calendula.domain.reminders.reminderWatermark
import de.jeanlucmakiola.calendula.domain.reminders.scheduleReminders
import de.jeanlucmakiola.floret.android.hasPermission
import de.jeanlucmakiola.floret.di.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of in-house reminder delivery: read what is planned, post what has
 * come due, and arm the next wake-up. Every trigger runs the same [scan], and
 * re-running is always safe — the watermark in [ReminderStatePrefs] decides what
 * is owed, not the trigger.
 */
@Singleton
class ReminderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: ReminderInstanceSource,
    private val calendarDataSource: CalendarDataSource,
    private val notifier: ReminderNotifier,
    private val digestNotifier: DailyDigestNotifier,
    private val channels: ReminderChannels,
    private val snoozes: ReminderSnoozeScheduler,
    private val alarms: ReminderAlarmScheduler,
    private val state: ReminderStatePrefs,
    private val calendarPrefs: CalendarPrefs,
    private val settingsPrefs: SettingsPrefs,
    @IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher,
) {

    // Triggers overlap freely (an alarm during a burst of edits); serialize so
    // two passes can't both read the same watermark and post the same reminder.
    private val scanLock = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val providerChanges = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var watching = false

    suspend fun scan() = withContext(io) {
        scanLock.withLock {
            try {
                runScan()
            } catch (e: SecurityException) {
                // Permission revoked mid-flight; the next grant re-scans.
                Log.w(TAG, "Reminder scan lacks the calendar permission", e)
            } catch (e: Exception) {
                Log.w(TAG, "Reminder scan failed", e)
            }
        }
    }

    private suspend fun runScan() {
        val now = System.currentTimeMillis()
        if (!hasReadCalendar()) return

        val lastFired = reminderWatermark(state.lastScanMillis(), now)
        val horizon = now + MAX_ALARM_INTERVAL_MILLIS
        val digestSettings = settingsPrefs.dailyDigest.first()
        val nextDigest = if (digestSettings.enabled) postDigest(digestSettings, lastFired, now) else null
        val nextReminder = try {
            if (settingsPrefs.remindersEnabled.first()) reminders(lastFired, now, horizon) else null
        } catch (e: Exception) {
            // The watermark stays, so the next pass owes these reminders again;
            // keep that pass, and the summary's next moment, armed.
            alarms.scheduleScan(minOf(nextDigest ?: horizon, now + RETRY_AFTER_FAILURE_MILLIS))
            throw e
        }

        // Advance even when nothing could be posted, so muting notifications
        // doesn't build a backlog — and while both are off, so switching one
        // back on doesn't replay it.
        state.setLastScanMillis(now)
        val next = listOfNotNull(nextReminder, nextDigest).minOrNull()
        if (next == null) alarms.cancelScan() else alarms.scheduleScan(minOf(next, horizon))
    }

    /** Post the event reminders that are due; returns the next wake-up they need. */
    private suspend fun reminders(lastFired: Long, now: Long, horizon: Long): Long {
        syncChannels()
        val lookahead = reminderQueryHorizon(LOOKAHEAD_MILLIS, source.longestReminderMinutes())
        // Reach into the past too: an all-day "at time of event" encodes to a
        // negative offset, and a catch-up pass needs the occurrences it missed.
        // Calendars muted in-app (#272) plan nothing, so they arm no wake-ups.
        val muted = settingsPrefs.mutedReminderCalendarIds.first()
        val occurrences = source.occurrences(now - PAST_WINDOW_MILLIS, now + lookahead)
            .filter { it.calendarId !in muted }
        val planned = planReminders(
            instances = occurrences,
            minutesByEvent = source.reminderMinutes(occurrences.map { it.eventId })
                .withoutProviderDefaults(),
            zone = ZoneId.systemDefault(),
            allDayTimeMinutes = settingsPrefs.allDayReminderTimeMinutes.first(),
        )

        val schedule = scheduleReminders(
            planned = planned,
            lastFiredMillis = lastFired,
            nowMillis = now,
            horizonMillis = horizon,
        )

        if (notifier.canPost()) {
            schedule.due.forEach {
                val alert = it.toAlert()
                snoozes.cancel(alert)
                notifier.post(alert)
            }
        }
        return schedule.nextAlarmMillis
    }

    /**
     * Post the daily summary if it is owed (#459); returns its next moment. It
     * leaves out calendars excluded from it, not those muted for reminders —
     * a calendar too noisy to ping per event can still belong in the overview.
     */
    private suspend fun postDigest(settings: DailyDigestSettings, lastFired: Long, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val listed = try {
            state.lastDigestDay()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the last daily summary", e)
            null
        }
        val schedule = scheduleDigest(
            timeMinutes = settings.timeMinutes,
            day = settings.day,
            zone = zone,
            // A moment that passed before the summary was last changed is not owed.
            lastFiredMillis = maxOf(lastFired, settings.armedAtMillis),
            nowMillis = now,
            lastListedDay = listed,
        )
        val day = schedule.dueDay
        if (day != null && notifier.canPost()) {
            // A failed summary must not cost the event reminders this pass owes.
            try {
                // A day either side: all-day occurrences sit at UTC midnight, which
                // can fall outside the local day they stand for.
                val from = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                val left = settings.excludedCalendarIds + calendarPrefs.pendingDisabledCalendarIds.first()
                digestNotifier.post(day, digestEntries(source.occurrences(from, to), day, zone, left))
                state.setLastDigestDay(day)
            } catch (e: Exception) {
                Log.w(TAG, "Daily summary failed", e)
            }
        }
        return schedule.nextAlarmMillis
    }

    /**
     * A provider change re-scans, so a calendar added, renamed or removed keeps
     * its channel in step here. Skipped while reminders are off, when the
     * channels have nothing to do. A failure never costs the reminders.
     */
    private fun syncChannels() {
        try {
            channels.sync()
        } catch (e: Exception) {
            Log.w(TAG, "Reminder channel sync failed", e)
        }
    }

    private fun hasReadCalendar(): Boolean = context.hasPermission(Manifest.permission.READ_CALENDAR)

    /**
     * Re-scan when the provider changes, so a saved or deleted event re-arms the
     * alarm at once. Debounced, since a single save lands as several
     * notifications. Process-lifetime only; other triggers cover the rest.
     */
    fun startWatchingProvider() {
        if (watching) return
        watching = true
        providerChanges
            .debounce(PROVIDER_CHANGE_DEBOUNCE_MILLIS)
            .onEach { scan() }
            .launchIn(scope)
        calendarDataSource.registerChangeListener { providerChanges.tryEmit(Unit) }
    }

    /** Fire-and-forget scan for callers that are not in a coroutine already. */
    fun scanInBackground() {
        scope.launch(start = CoroutineStart.DEFAULT) { scan() }
    }

    private companion object {
        const val TAG = "ReminderScanner"

        /**
         * How far ahead occurrences are read. Stretched further by the longest
         * reminder offset in the table, so this is only the floor.
         */
        const val LOOKAHEAD_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** How far back to look for occurrences that may still owe a reminder. */
        const val PAST_WINDOW_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Never wait longer than a day for the next pass: it rolls the lookahead
         * window forward and re-arms an alarm the system may have dropped.
         */
        const val MAX_ALARM_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        const val PROVIDER_CHANGE_DEBOUNCE_MILLIS = 2_000L

        /** How soon a pass whose reminders failed is tried again. */
        const val RETRY_AFTER_FAILURE_MILLIS = 15L * 60 * 1000
    }
}
