package de.jeanlucmakiola.calendula.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.di.IoDispatcher
import de.jeanlucmakiola.calendula.data.prefs.PastEventDisplay
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.firstDayOfWeek
import de.jeanlucmakiola.calendula.data.time.TodaySource
import de.jeanlucmakiola.calendula.data.time.currentDate
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.domain.FailureReason
import de.jeanlucmakiola.calendula.domain.calendarListFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Instant
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: CalendarRepository,
    settingsPrefs: SettingsPrefs,
    todaySource: TodaySource,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    // The saved agenda range plus the range-bar visibility toggle.
    private val agendaSettings = combine(
        settingsPrefs.agendaScreenRange,
        settingsPrefs.agendaShowRangeBar,
    ) { range, showBar -> AgendaSettings(range, showBar) }

    /**
     * First day of the week, for the calendar-aligned "this week" range. Public
     * because the range picker resolves each option to real dates, which needs
     * the same week start the window is built from.
     */
    val weekStart: StateFlow<DayOfWeek> = settingsPrefs.firstDayOfWeek(viewModelScope)

    /**
     * How to treat events that already ended today (show / dim / hide). A display
     * concern only, so it rides alongside the data state rather than re-querying;
     * the screen combines it with a per-minute "now" to fade or drop past rows.
     */
    val pastEventDisplay: StateFlow<PastEventDisplay> = settingsPrefs.pastEventDisplay
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PastEventDisplay.SHOW,
        )

    /**
     * Whether to keep today anchored at the top with a "nothing left today"
     * placeholder even once it has no remaining events (#35). A display concern
     * applied in the composition (after past-event filtering), so it rides
     * alongside the data state like [pastEventDisplay] rather than re-querying.
     */
    val showToday: StateFlow<Boolean> = settingsPrefs.agendaShowToday
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = true,
        )

    private val zone = TimeZone.currentSystemDefault()

    private val todayDate: LocalDate
        get() = currentDate()

    // The anchor and how many days before it are loaded, moved together so a
    // jump never queries a window that pairs the new anchor with the old span.
    private val _window = MutableStateFlow(AgendaWindow(todayDate, PAST_CHUNK_DAYS))
    val anchor: StateFlow<LocalDate> = _window
        .map { it.anchor }
        .stateIn(viewModelScope, SharingStarted.Eagerly, todayDate)

    /** The day last scrolled to the top, so a return to the agenda lands back on it. */
    var lastTopDate: LocalDate? = null
        private set

    private var lastToday = todayDate

    /**
     * Today, kept current across midnight (#373). An agenda still starting on
     * yesterday moves on to the new day; one jumped elsewhere stays put.
     */
    private val today: StateFlow<LocalDate> = todaySource.today
        .onEach { date ->
            val old = lastToday
            lastToday = date
            if (old != date && _window.value.anchor == old) {
                _window.value = _window.value.copy(anchor = date)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), todayDate)

    // A transient, in-view override of the saved agenda range. Held in memory
    // (not persisted), so it survives view switches and rotation within the
    // session but resets to the saved default when the app is relaunched.
    private val _rangeOverride = MutableStateFlow<AgendaRange?>(null)

    val state: StateFlow<AgendaUiState> =
        combine(
            _window,
            agendaSettings,
            _rangeOverride,
            weekStart,
            settingsPrefs.pastEventDisplay,
        ) { window, settings, override, weekStart, pastDisplay ->
            AgendaParams(
                anchor = window.anchor,
                // Hidden past events leave nothing to scroll back to (#367).
                pastDays = if (pastDisplay == PastEventDisplay.HIDE) 0 else window.pastDays,
                range = override ?: settings.range,
                rangeIsOverride = override != null && override != settings.range,
                weekStart = weekStart,
                showRangeBar = settings.showBar,
            )
        }
        .flatMapLatest { params ->
            val window = agendaRange(
                params.windowStart,
                params.pastDays + params.range.dayCount(params.anchor, params.weekStart) - 1,
                zone,
            )
            combine(
                repository.calendars(),
                repository.instances(window),
                today,
            ) { calendars, instances, today ->
                buildState(params, calendars, instances, today)
            }
        }
        .catch { emit(AgendaUiState.Failure(FailureReason.ProviderUnavailable)) }
        .flowOn(io)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AgendaUiState.Loading,
        )

    fun goToToday() {
        goToDate(todayDate)
    }

    /** Jump the agenda window to start on a specific date (drawer jump-to-date). */
    fun goToDate(date: LocalDate) {
        if (_window.value.anchor == date) return
        _window.value = AgendaWindow(date, PAST_CHUNK_DAYS)
        lastTopDate = null
    }

    /**
     * Extend the window [PAST_CHUNK_DAYS] further back (#367). Ignored while the
     * previous extension is still loading, so a scroll that keeps asking only
     * moves one chunk at a time.
     */
    fun loadEarlier() {
        val loaded = (state.value as? AgendaUiState.Success)?.windowStart ?: return
        val window = _window.value
        if (loaded != window.start) return
        _window.value = window.copy(pastDays = window.pastDays + PAST_CHUNK_DAYS)
    }

    fun rememberTopDay(date: LocalDate) {
        lastTopDate = date
    }

    /** Temporarily override the agenda range for this session (the bottom-left pill). */
    fun setRangeOverride(range: AgendaRange) {
        _rangeOverride.value = range
    }

    private data class AgendaSettings(
        val range: AgendaRange,
        val showBar: Boolean,
    )

    private data class AgendaWindow(val anchor: LocalDate, val pastDays: Int) {
        val start: LocalDate get() = anchor.minus(pastDays, DateTimeUnit.DAY)
    }

    private data class AgendaParams(
        val anchor: LocalDate,
        val pastDays: Int,
        val range: AgendaRange,
        val rangeIsOverride: Boolean,
        val weekStart: DayOfWeek,
        val showRangeBar: Boolean,
    ) {
        val windowStart: LocalDate get() = anchor.minus(pastDays, DateTimeUnit.DAY)
    }

    private fun buildState(
        params: AgendaParams,
        calendars: List<CalendarSource>,
        instances: List<EventInstance>,
        today: LocalDate,
    ): AgendaUiState {
        calendarListFailure(calendars)?.let { return AgendaUiState.Failure(it) }
        val anchor = params.anchor
        val rangeEnd = anchor.plus(
            params.range.dayCount(anchor, params.weekStart) - 1,
            DateTimeUnit.DAY,
        )
        val days = groupAgendaDays(params.windowStart, rangeEnd, instances, zone)
        return AgendaUiState.Success(
            anchor = anchor,
            windowStart = params.windowStart,
            today = today,
            days = days,
            range = params.range,
            rangeIsOverride = params.rangeIsOverride,
            rangeEnd = rangeEnd,
            showRangeBar = params.showRangeBar,
            zone = zone,
        )
    }
}

/** How many days each scroll back into the past loads (#367). */
internal const val PAST_CHUNK_DAYS = 30

/** Inclusive instant range from the start of [anchor] through [days] days ahead. */
internal fun agendaRange(anchor: LocalDate, days: Int, zone: TimeZone): ClosedRange<Instant> {
    val from = anchor.atStartOfDayIn(zone)
    val to = anchor.plus(days, DateTimeUnit.DAY).atTime(23, 59, 59).toInstant(zone)
    return from..to
}
