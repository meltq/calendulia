package de.jeanlucmakiola.calendula.ui.common

import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.domain.EventInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** The calendars hidden in [view] under a per-calendar hidden-views map (#270). */
fun Map<Long, Set<CalendarView>>.calendarsHiddenIn(view: CalendarView): Set<Long> =
    filterValues { view in it }.keys

/** [this] without the events of [hidden] calendars. */
fun List<EventInstance>.withoutCalendars(hidden: Set<Long>): List<EventInstance> =
    if (hidden.isEmpty()) this else filterNot { it.calendarId in hidden }

/**
 * [CalendarRepository.instances] as [view] shows them: also without the
 * calendars the user hid in that view. Display only — reminders and search
 * read the repository directly.
 */
fun CalendarRepository.instancesIn(
    view: CalendarView,
    range: ClosedRange<Instant>,
    hiddenViews: Flow<Map<Long, Set<CalendarView>>>,
): Flow<List<EventInstance>> =
    combine(
        instances(range),
        hiddenViews.map { it.calendarsHiddenIn(view) }.distinctUntilChanged(),
    ) { instances, hidden -> instances.withoutCalendars(hidden) }
        .distinctUntilChanged()
