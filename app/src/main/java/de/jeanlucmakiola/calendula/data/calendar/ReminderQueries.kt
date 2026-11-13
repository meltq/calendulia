package de.jeanlucmakiola.calendula.data.calendar

import android.content.ContentResolver
import android.provider.CalendarContract

/** Ids per `IN (…)` list: they go into the SQL literally, which SQLite bounds. */
private const val EVENT_ID_BATCH = 500

/**
 * Reminder minutes of [eventIds], keyed by event, a batched query at a time
 * rather than one per event. [alertsOnly] keeps just the notification ones.
 */
internal fun ContentResolver.reminderMinutesByEvent(
    eventIds: Collection<Long>,
    alertsOnly: Boolean,
): Map<Long, List<Int>> {
    if (eventIds.isEmpty()) return emptyMap()
    val out = mutableMapOf<Long, MutableList<Int>>()
    val method = if (alertsOnly) {
        "${CalendarContract.Reminders.METHOD} = ${CalendarContract.Reminders.METHOD_ALERT} AND "
    } else {
        ""
    }
    eventIds.distinct().chunked(EVENT_ID_BATCH).forEach { batch ->
        query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
            "$method${CalendarContract.Reminders.EVENT_ID} IN (${batch.joinToString(",")})",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1)
        }
    }
    return out
}
