package de.jeanlucmakiola.calendula.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.jeanlucmakiola.floret.prefs.PrefStore
import de.jeanlucmakiola.floret.prefs.nullableLongPref
import de.jeanlucmakiola.floret.prefs.nullableStringPref
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far reminder delivery has got — the one number replacing the provider's
 * `CalendarAlerts.STATE` (#75). A scan posts the reminders falling after this
 * watermark and up to now, then moves it to now, so a scan running twice cannot
 * post twice while a late one still catches up.
 *
 * Unset means "never scanned", not zero: the first scan after an install would
 * otherwise treat every reminder since the epoch as overdue.
 */
@Singleton
class ReminderStatePrefs @Inject constructor(
    store: DataStore<Preferences>,
) {
    private val prefs = PrefStore(store)

    /** The watermark, or `null` before the first scan has ever run. */
    suspend fun lastScanMillis(): Long? = prefs.get(LAST_SCAN)

    suspend fun setLastScanMillis(millis: Long) = prefs.set(LAST_SCAN, millis)

    /**
     * The day the last daily summary listed (#459): recorded at once, so neither
     * a pass failing after posting nor a later time picked the same evening
     * lists it twice.
     */
    suspend fun lastDigestDay(): LocalDate? =
        prefs.get(LAST_DIGEST_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    suspend fun setLastDigestDay(day: LocalDate) = prefs.set(LAST_DIGEST_DAY, day.toString())

    private companion object {
        val LAST_SCAN = nullableLongPref("reminder_last_scan_millis")
        val LAST_DIGEST_DAY = nullableStringPref("reminder_last_digest_day")
    }
}
