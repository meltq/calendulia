package de.jeanlucmakiola.calendula.domain.reminders

private val webLinkRegex = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

/**
 * What a reminder's "Open in maps" button looks up for [location], or null
 * when there is no place to find: blank, or nothing but a (meeting) link.
 */
fun reminderMapQuery(location: String?): String? =
    location?.trim()?.takeIf { it.isNotEmpty() && !webLinkRegex.matches(it) }
