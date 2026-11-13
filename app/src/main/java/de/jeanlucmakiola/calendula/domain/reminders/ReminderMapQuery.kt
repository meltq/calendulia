package de.jeanlucmakiola.calendula.domain.reminders

// Same link shape the detail screen linkifies (ui/detail/WebLinks.kt).
private val webLinkRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

/**
 * What a reminder's "Open in maps" button looks up for [location] — the same
 * text the detail screen's location card opens in maps — or null when there is
 * no place to find: blank, or nothing but web (meeting) links.
 */
fun reminderMapQuery(location: String?): String? {
    val trimmed = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { webLinkRegex.replace(it, "").any(Char::isLetterOrDigit) }
}
