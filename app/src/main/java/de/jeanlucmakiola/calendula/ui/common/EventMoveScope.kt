package de.jeanlucmakiola.calendula.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.EventInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Drag-to-reschedule wiring (#68), provided once at `CalendarHost` and read by
 * whichever event block is being composed. Null means moving is off entirely and
 * blocks register no drag gesture at all.
 */
@Immutable
class EventMoveScope(
    /**
     * Calendars whose events may be moved. Nothing below the UI enforces this —
     * the repository writes whatever it is handed — so this gate is load-bearing.
     */
    val movableCalendarIds: Set<Long>,
    /**
     * Whether the *gesture* is on (#173). Off gates the drag alone: [edit] and the
     * TalkBack action below stay, since invoking a menu item can't cause the
     * accidental drags the setting exists to stop.
     */
    val dragEnabled: Boolean,
    /**
     * Hand a drop to the writer. Returns nothing on purpose: a drop is answered
     * before its write resolves, so [drops] — not a return value — is what tells
     * a held copy whether anything landed.
     */
    val move: (MoveRequest) -> Unit,
    /**
     * Each drop's progress by its token — see `RescheduleViewModel.drops`. A flow
     * rather than a value so this scope stays the same object across a move —
     * every visible block reads it as a composition local.
     */
    val drops: StateFlow<DropStates>,
    /** The token of the undo write that just began — see `RescheduleViewModel.undoStarted`. */
    val undoStarted: StateFlow<Long?>,
    /** Open an event in the edit form — the pointer-free route to the same change. */
    val edit: (EventInstance) -> Unit,
) {
    fun allows(event: EventInstance): Boolean = event.calendarId in movableCalendarIds
}

val LocalEventMove = compositionLocalOf<EventMoveScope?> { null }

private val moveTokens = AtomicLong()

/** A fresh identity for one drop or undo, unique for the process. */
fun nextMoveToken(): Long = moveTokens.incrementAndGet()

/** How many ended drops [DropStates] remembers — far more than can be on screen at once. */
private const val ENDED_KEPT = 32

/** Per-drop progress, by token — see `RescheduleViewModel.drops`. */
@Immutable
data class DropStates(
    val pending: Set<Long> = emptySet(),
    val abandoned: Set<Long> = emptySet(),
) {
    fun started(token: Long): DropStates = copy(pending = pending + token)

    fun ended(token: Long, landed: Boolean): DropStates = copy(
        pending = pending - token,
        abandoned = if (landed) abandoned else (abandoned + token).toList().takeLast(ENDED_KEPT).toSet(),
    )
}

private val NO_DROPS = MutableStateFlow(DropStates())

private val NEVER_UNDONE = MutableStateFlow<Long?>(null)

/**
 * Every drop's progress — empty wherever moving is off. A drag overlay keeps
 * the token of its own drop and holds its copy while that token is pending.
 */
@Composable
fun dropStates(): DropStates {
    val flow = LocalEventMove.current?.drops ?: NO_DROPS
    return flow.collectAsStateWithLifecycle().value
}

/**
 * Runs [onUndo] with the undo's token when an undo write begins, and never for
 * one that began before this composable came on screen.
 */
@Composable
fun OnUndoStarted(onUndo: (token: Long) -> Unit) {
    val flow = LocalEventMove.current?.undoStarted ?: NEVER_UNDONE
    val token by flow.collectAsStateWithLifecycle()
    var seen by remember { mutableStateOf(token) }
    LaunchedEffect(token) {
        val started = token
        if (started == null || started == seen) return@LaunchedEffect
        seen = started
        onUndo(started)
    }
}

/** Opacity the source block keeps while its floating copy travels. */
const val GHOST_ALPHA: Float = 0.3f

/**
 * Opacity for a block whose copy is in flight: ghosted from the lift until the
 * copy is handed back, then animated up rather than switched, so the block
 * appears to travel to its new slot instead of vanishing and reappearing.
 */
@Composable
fun ghostAlpha(lifted: Boolean): Float = animateFloatAsState(
    targetValue = if (lifted) GHOST_ALPHA else 1f,
    label = "ghost-alpha",
).value

/**
 * Whether [event] can be picked up and dragged — its calendar allows moving and
 * the gesture is switched on. Not the same question as [eventMoveAction], which
 * survives the setting.
 */
@Composable
fun eventDragAllowed(event: EventInstance): Boolean {
    val move = LocalEventMove.current ?: return false
    return move.dragEnabled && move.allows(event)
}

/**
 * Whether a long press may select events at all (#141) — the gesture setting,
 * whatever the event's calendar allows. A read-only event is selected but stays put.
 */
@Composable
fun eventSelectAllowed(): Boolean = LocalEventMove.current?.dragEnabled == true

/**
 * A TalkBack action that opens [event] in the edit form, so rescheduling isn't
 * pointer-only. Null when this event can't be moved.
 */
@Composable
fun eventMoveAction(event: EventInstance): CustomAccessibilityAction? {
    val move = LocalEventMove.current ?: return null
    if (!move.allows(event)) return null
    val label = stringResource(R.string.event_move_action)
    return remember(event.instanceId, label, move) {
        CustomAccessibilityAction(label) {
            move.edit(event)
            true
        }
    }
}
