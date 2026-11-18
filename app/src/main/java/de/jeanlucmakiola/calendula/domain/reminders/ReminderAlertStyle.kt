package de.jeanlucmakiola.calendula.domain.reminders

/** How a calendar's reminders get the user's attention (#171). */
enum class ReminderAlertStyle(val storageValue: String) {
    /** An ordinary notification on the calendar's channel. */
    Notification("notification"),

    /** A full-screen alert that sounds the calendar's notification sound once and stays until handled. */
    FullScreen("full_screen"),

    /** A full-screen alert that rings and vibrates like an alarm until handled. */
    FullScreenRing("full_screen_ring"),
    ;

    val isFullScreen: Boolean get() = this != Notification

    companion object {
        fun fromStorage(value: String): ReminderAlertStyle? = entries.firstOrNull { it.storageValue == value }
    }
}

/** [calendarId]'s alert style; a calendar without one uses plain notifications. */
fun Map<Long, ReminderAlertStyle>.alertStyleFor(calendarId: Long): ReminderAlertStyle =
    this[calendarId] ?: ReminderAlertStyle.Notification

/**
 * Parse `id=style` entries joined by `;`. Malformed entries and unknown styles
 * are dropped, so a garbled value degrades to plain notifications.
 */
fun parseAlertStyles(stored: String?): Map<Long, ReminderAlertStyle> =
    stored?.split(";").orEmpty().mapNotNull { entry ->
        val parts = entry.split("=")
        if (parts.size != 2) return@mapNotNull null
        val calendarId = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
        val style = ReminderAlertStyle.fromStorage(parts[1].trim()) ?: return@mapNotNull null
        calendarId to style
    }.toMap()

/** The stored form of [parseAlertStyles]; plain-notification entries are left out. */
fun Map<Long, ReminderAlertStyle>.toStoredAlertStyles(): String =
    entries.filter { it.value != ReminderAlertStyle.Notification }
        .sortedBy { it.key }
        .joinToString(";") { (id, style) -> "$id=${style.storageValue}" }
