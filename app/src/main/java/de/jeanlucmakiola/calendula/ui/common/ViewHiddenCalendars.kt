package de.jeanlucmakiola.calendula.ui.common

import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.domain.EventInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Instant

/** The calendars hidden in [view] under a per-calendar hidden-views map (#270). */
fun Map<Long, Set<CalendarView>>.calendarsHiddenIn(view: CalendarView): Set<Long> =
    filterValues { view in it }.keys

/**
 * The calendars hidden in [view], shared across one view model's pages so the
 * setting is read once rather than per page.
 */
fun Flow<Map<Long, Set<CalendarView>>>.calendarsHiddenIn(
    view: CalendarView,
    scope: CoroutineScope,
): Flow<Set<Long>> =
    map { it.calendarsHiddenIn(view) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

/** [this] without the events of [hidden] calendars. */
fun List<EventInstance>.withoutCalendars(hidden: Set<Long>): List<EventInstance> =
    if (hidden.isEmpty()) this else filterNot { it.calendarId in hidden }

/**
 * [CalendarRepository.instances] as one view shows them: also without the
 * [hidden] calendars the user keeps out of it. Display only — reminders, search
 * and widgets read the repository directly.
 */
fun CalendarRepository.instancesWithout(
    range: ClosedRange<Instant>,
    hidden: Flow<Set<Long>>,
): Flow<List<EventInstance>> =
    combine(instances(range), hidden) { instances, ids -> instances.withoutCalendars(ids) }
        .distinctUntilChanged()
