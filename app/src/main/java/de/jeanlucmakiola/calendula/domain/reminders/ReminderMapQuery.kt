package de.jeanlucmakiola.calendula.domain.reminders

private val linkRegex = Regex(
    """(?:https?://|www\.)\S+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}/\S*""",
    RegexOption.IGNORE_CASE,
)

/**
 * What a reminder's "Open in maps" button looks up for [location], or null
 * when there is no place to find: blank, or nothing but (meeting) links.
 */
fun reminderMapQuery(location: String?): String? {
    val trimmed = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { linkRegex.replace(it, "").any(Char::isLetterOrDigit) }
}
