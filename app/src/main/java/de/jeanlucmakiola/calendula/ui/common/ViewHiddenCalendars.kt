package de.jeanlucmakiola.calendula.ui.common

import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.domain.withoutCalendars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import java.io.IOException
import kotlin.time.Instant

/** The calendars hidden in [view] under a per-calendar hidden-views map (#270). */
fun Map<Long, Set<CalendarView>>.calendarsHiddenIn(view: CalendarView): Set<Long> =
    filterValues { view in it }.keys

/**
 * The calendars hidden in [view], shared by everything one view model shows so
 * the setting is read once rather than per page. A failed read hides nothing
 * rather than failing the view; the replay expires with the last subscriber, so
 * a returning view filters against a fresh read.
 */
fun Flow<Map<Long, Set<CalendarView>>>.calendarsHiddenIn(
    view: CalendarView,
    scope: CoroutineScope,
): Flow<Set<Long>> =
    hidingNothingOnError()
        .map { it.calendarsHiddenIn(view) }
        .distinctUntilChanged()
        // The grace period outlasts a flatMapLatest swap, so paging doesn't re-read it.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

/**
 * A failed read of the per-view setting hides nothing rather than failing what
 * shows it, and keeps reading, so the next change still lands.
 */
fun Flow<Map<Long, Set<CalendarView>>>.hidingNothingOnError(): Flow<Map<Long, Set<CalendarView>>> =
    retryWhen { cause, _ ->
        if (cause !is IOException) return@retryWhen false
        emit(emptyMap())
        delay(RETRY_DELAY_MS)
        true
    }

private const val RETRY_DELAY_MS = 1_000L

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
