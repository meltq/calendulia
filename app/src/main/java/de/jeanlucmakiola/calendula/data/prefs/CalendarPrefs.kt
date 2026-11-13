package de.jeanlucmakiola.calendula.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.jeanlucmakiola.floret.prefs.Pref
import de.jeanlucmakiola.floret.prefs.PrefStore
import de.jeanlucmakiola.floret.prefs.nullableBooleanPref
import de.jeanlucmakiola.floret.prefs.nullableLongPref
import de.jeanlucmakiola.floret.prefs.nullableStringPref
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side calendar preferences. [hiddenCalendarIds] is the drawer's filter
 * sheet — a purely in-app declutter that deliberately does *not* suppress
 * reminders. Switching a calendar off entirely is the system's
 * `Calendars.VISIBLE` flag, written straight to the provider (#75);
 * [pendingDisabledCalendarIds] only holds those switch-offs the app has not been
 * allowed to write yet.
 *
 * Persisted as a comma-separated string of Long ids; non-numeric tokens are
 * silently dropped (defensive — see CalendarPrefsTest).
 */
@Singleton
class CalendarPrefs @Inject constructor(
    store: DataStore<Preferences>,
) {
    private val prefs = PrefStore(store)

    val hiddenCalendarIds: Flow<Set<Long>> = prefs.flow(CalendarPrefKeys.hiddenCalendarIds)

    suspend fun setHiddenCalendarIds(ids: Set<Long>) = prefs.set(CalendarPrefKeys.hiddenCalendarIds, ids)

    /**
     * Switch-offs the provider does not know about yet, because writing
     * `Calendars.VISIBLE` needs `WRITE_CALENDAR` (#75). Also inherits the
     * retired app-local model's set, from the same key.
     *
     * Honoured as a display and reminder filter while non-empty, but not a
     * second visibility model: `CalendarVisibilityReconciler` drains it entry by
     * entry as soon as the app may write, and nothing adds to it while it may.
     */
    val pendingDisabledCalendarIds: Flow<Set<Long>> = prefs.flow(CalendarPrefKeys.pendingDisabledCalendarIds)

    suspend fun addPendingDisabledCalendarIds(ids: Collection<Long>) {
        prefs.update(CalendarPrefKeys.pendingDisabledCalendarIds) { it + ids }
    }

    /**
     * Drop [ids] from the pending set, one at a time as the reconciler flushes
     * them, so a run that fails part-way never re-applies what already landed.
     */
    suspend fun removePendingDisabledCalendarIds(ids: Collection<Long>) {
        prefs.update(CalendarPrefKeys.pendingDisabledCalendarIds) { it - ids.toSet() }
    }

    /**
     * Whether the one-time "visibility follows this device" notice is still
     * owed. Null until the reconciler has evaluated it (which needs the calendar
     * permission), false once it has been shown or was never needed.
     */
    val visibilityNoticePending: Flow<Boolean?> = prefs.flow(CalendarPrefKeys.visibilityNoticePending)

    suspend fun setVisibilityNoticePending(pending: Boolean) = prefs.set(CalendarPrefKeys.visibilityNoticePending, pending)

    /**
     * The calendar the user last created an event in; preselected in the
     * event form. Null until the first event is created.
     */
    val lastUsedCalendarId: Flow<Long?> = prefs.flow(CalendarPrefKeys.lastUsedCalendarId)

    suspend fun setLastUsedCalendarId(id: Long) = prefs.set(CalendarPrefKeys.lastUsedCalendarId, id)

}

/** Every key [CalendarPrefs] stores; the names are on-disk data. */
internal object CalendarPrefKeys {
    val hiddenCalendarIds: Pref<Set<Long>> = idSetPref("hidden_calendar_ids")
    val pendingDisabledCalendarIds: Pref<Set<Long>> = idSetPref("disabled_calendar_ids")
    val visibilityNoticePending: Pref<Boolean?> = nullableBooleanPref("visibility_notice_pending")
    val lastUsedCalendarId: Pref<Long?> = nullableLongPref("last_used_calendar_id")
}

/** A comma-joined id set; non-numeric tokens are dropped, and an empty set removes the key. */
private fun idSetPref(name: String): Pref<Set<Long>> = nullableStringPref(name).map(
    { stored -> stored.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet() },
    { ids -> if (ids.isEmpty()) null else ids.sorted().joinToString(",") },
)
