package de.jeanlucmakiola.calendula.ui.reminders

sealed interface ReminderAlertUiState {
    /** The calendar is still being read; the surface is already up. */
    data object Loading : ReminderAlertUiState

    data class Showing(
        /** Raw title; blank shows as "(no title)". */
        val title: String,
        val time: String,
        val location: String?,
        /** Null when the calendar could not be read. */
        val calendarName: String?,
        val calendarColor: Int?,
    ) : ReminderAlertUiState
}
