package de.jeanlucmakiola.calendula.widget.config

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.resolveFirstDay
import de.jeanlucmakiola.calendula.widget.AgendaWidgetData
import de.jeanlucmakiola.calendula.widget.AgendaWidgetOptions
import de.jeanlucmakiola.calendula.widget.MonthWidgetSource
import de.jeanlucmakiola.calendula.widget.WidgetAppearance
import de.jeanlucmakiola.calendula.widget.agenda.AgendaWidget
import de.jeanlucmakiola.calendula.widget.agenda.AgendaWidgetReceiver
import de.jeanlucmakiola.calendula.widget.loadAgendaWidgetData
import de.jeanlucmakiola.calendula.widget.loadThemedMonthSource
import de.jeanlucmakiola.calendula.widget.month.MonthWidget
import de.jeanlucmakiola.calendula.widget.month.MonthWidgetReceiver
import de.jeanlucmakiola.calendula.widget.monthIndexOf
import de.jeanlucmakiola.calendula.widget.monthWindowCenter
import de.jeanlucmakiola.calendula.widget.agendaWidgetOptions
import de.jeanlucmakiola.calendula.widget.putAgendaOptions
import de.jeanlucmakiola.calendula.widget.putAppearance
import de.jeanlucmakiola.calendula.widget.systemZone
import de.jeanlucmakiola.calendula.widget.today
import de.jeanlucmakiola.calendula.widget.widgetAppearance
import de.jeanlucmakiola.calendula.widget.widgetSettings
import de.jeanlucmakiola.calendula.widget.widgetState
import de.jeanlucmakiola.floret.glance.updateWithRefresh
import de.jeanlucmakiola.floret.prefs.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import java.util.Locale

/** The widgets a config screen can edit, told apart by their receiver. */
enum class WidgetKind(val receiver: Class<*>) {
    AGENDA(AgendaWidgetReceiver::class.java),
    MONTH(MonthWidgetReceiver::class.java),
    ;

    fun widget(): GlanceAppWidget = when (this) {
        AGENDA -> AgendaWidget()
        MONTH -> MonthWidget()
    }

    companion object {
        fun of(context: Context, appWidgetId: Int): WidgetKind? {
            val provider = AppWidgetManager.getInstance(context)?.getAppWidgetInfo(appWidgetId)?.provider ?: return null
            return entries.firstOrNull { it.receiver.name == provider.className }
        }
    }
}

/** What the preview draws, loaded with the draft's options. */
sealed interface WidgetPreviewData {
    data class Agenda(val data: AgendaWidgetData) : WidgetPreviewData
    data class Month(val source: MonthWidgetSource, val index: Int) : WidgetPreviewData
}

sealed interface WidgetConfigUiState {
    data object Loading : WidgetConfigUiState

    /** The widget is gone, or isn't one of ours; the screen closes. */
    data object Missing : WidgetConfigUiState

    /** Its stored options couldn't be read. */
    data object Failed : WidgetConfigUiState

    data class Ready(
        val kind: WidgetKind,
        val appearance: WidgetAppearance,
        /** Null for a widget with no content options. */
        val agenda: AgendaWidgetOptions?,
        val appTheme: ThemeMode,
        val weekStart: DayOfWeek,
        /** The widget's size on the home screen, which the preview is laid out at. */
        val placedSize: DpSize,
        /** Null until the first preview load lands. */
        val preview: WidgetPreviewData?,
        /** The first preview load failed, so there's nothing to show. */
        val previewFailed: Boolean = false,
        val saving: Boolean = false,
    ) : WidgetConfigUiState
}

/** One-time outcomes of a save, for the screen that's showing when they land. */
enum class WidgetConfigEvent { SAVED, SAVE_FAILED }

/**
 * Backs [WidgetConfigActivity] (#292): the placed widget's stored options as an
 * editable draft, a preview loaded with that draft, and the save that writes it
 * into the widget's Glance state and redraws it.
 */
@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SettingsPrefs,
    savedState: SavedStateHandle,
) : ViewModel() {

    val appWidgetId: Int = savedState[AppWidgetManager.EXTRA_APPWIDGET_ID] ?: AppWidgetManager.INVALID_APPWIDGET_ID

    private val _state = MutableStateFlow<WidgetConfigUiState>(WidgetConfigUiState.Loading)
    val state: StateFlow<WidgetConfigUiState> = _state.asStateFlow()

    private val _events = Channel<WidgetConfigEvent>(Channel.BUFFERED)
    val events: Flow<WidgetConfigEvent> = _events.receiveAsFlow()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        if (_state.value != WidgetConfigUiState.Failed) return
        _state.value = WidgetConfigUiState.Loading
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val kind = WidgetKind.of(context, appWidgetId)
        if (kind == null) {
            _state.value = WidgetConfigUiState.Missing
            return
        }
        _state.value = try {
            val stored = context.widgetState(GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId))
            val settings = prefs.widgetSettings.first()
            WidgetConfigUiState.Ready(
                kind = kind,
                appearance = stored.widgetAppearance,
                agenda = if (kind == WidgetKind.AGENDA) stored.agendaWidgetOptions(settings.agendaDefaults) else null,
                appTheme = settings.themeMode,
                weekStart = settings.weekStart.resolveFirstDay(Locale.getDefault()),
                placedSize = placedSize(kind),
                preview = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            // getGlanceIdBy: the widget was removed meanwhile.
            WidgetConfigUiState.Missing
        } catch (_: Exception) {
            WidgetConfigUiState.Failed
        }
        reloadPreview()
    }

    /** The size the launcher placed the widget at (portrait), or a default before it has reported one. */
    private fun placedSize(kind: WidgetKind): DpSize {
        val options = AppWidgetManager.getInstance(context)?.getAppWidgetOptions(appWidgetId)
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (width > 0 && height > 0) return DpSize(width.dp, height.dp)
        return when (kind) {
            WidgetKind.AGENDA -> DpSize(260.dp, 280.dp)
            WidgetKind.MONTH -> DpSize(320.dp, 340.dp)
        }
    }

    fun setAppearance(appearance: WidgetAppearance) = edit { it.copy(appearance = appearance) }

    fun setAgendaOptions(options: AgendaWidgetOptions) {
        edit { it.copy(agenda = options) }
        reloadPreview()
    }

    private fun edit(change: (WidgetConfigUiState.Ready) -> WidgetConfigUiState.Ready) {
        _state.update { if (it is WidgetConfigUiState.Ready) change(it) else it }
    }

    private fun reloadPreview() {
        val ready = _state.value as? WidgetConfigUiState.Ready ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val preview = try {
                loadPreview(ready)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The options stay editable; the preview keeps what it had.
                edit { it.copy(previewFailed = it.preview == null) }
                return@launch
            }
            edit { it.copy(preview = preview, previewFailed = false) }
        }
    }

    private suspend fun loadPreview(ready: WidgetConfigUiState.Ready): WidgetPreviewData = when (ready.kind) {
        WidgetKind.AGENDA -> {
            val settings = prefs.widgetSettings.first()
            WidgetPreviewData.Agenda(context.loadAgendaWidgetData(settings, requireNotNull(ready.agenda)))
        }
        WidgetKind.MONTH -> {
            val index = monthIndexOf(today(systemZone()))
            WidgetPreviewData.Month(context.loadThemedMonthSource(monthWindowCenter(index)).content, index)
        }
    }

    /** Writes the draft into the widget and redraws it, then reports how that went on [events]. */
    fun save() {
        val ready = _state.value as? WidgetConfigUiState.Ready ?: return
        if (ready.saving) return
        edit { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                ready.kind.widget().updateWithRefresh(context, glanceId) { state ->
                    state.putAppearance(ready.appearance)
                    ready.agenda?.let { state.putAgendaOptions(it) }
                }
                _events.send(WidgetConfigEvent.SAVED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                _state.value = WidgetConfigUiState.Missing
            } catch (_: Exception) {
                edit { it.copy(saving = false) }
                _events.send(WidgetConfigEvent.SAVE_FAILED)
            }
        }
    }
}
