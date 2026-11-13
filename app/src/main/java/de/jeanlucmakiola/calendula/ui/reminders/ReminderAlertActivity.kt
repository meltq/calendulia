package de.jeanlucmakiola.calendula.ui.reminders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.reminders.FullScreenAlerts
import de.jeanlucmakiola.calendula.data.reminders.ReminderActionReceiver
import de.jeanlucmakiola.calendula.data.reminders.ReminderActionReceiver.Companion.putAlert
import de.jeanlucmakiola.calendula.data.reminders.ReminderAlert
import de.jeanlucmakiola.calendula.data.reminders.ReminderNotifier
import de.jeanlucmakiola.calendula.data.reminders.ReminderRingService
import de.jeanlucmakiola.calendula.ui.common.LocalSoftenColors
import de.jeanlucmakiola.calendula.ui.theme.CalendulaTheme
import de.jeanlucmakiola.calendula.ui.theme.WindowThemeCache
import de.jeanlucmakiola.floret.di.IoDispatcher
import de.jeanlucmakiola.floret.prefs.ThemeMode
import de.jeanlucmakiola.floret.prefs.isDark
import de.jeanlucmakiola.floret.prefs.isPureBlack
import de.jeanlucmakiola.floret.ring.showOverLockScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The full-screen reminder alert (#171), opened by the reminder's full-screen
 * intent over the lock screen, or from its heads-up while the phone is in use.
 * It stays until the reminder is dismissed or snoozed — here, from the
 * notification, or by the ring giving up — and back does not close it.
 */
@AndroidEntryPoint
class ReminderAlertActivity : ComponentActivity() {

    @Inject lateinit var notifier: ReminderNotifier
    @Inject lateinit var alerts: FullScreenAlerts
    @Inject lateinit var settingsPrefs: SettingsPrefs
    @Inject lateinit var calendarDataSource: CalendarDataSource

    @Inject @IoDispatcher
    lateinit var io: CoroutineDispatcher

    private var state by mutableStateOf<ReminderAlertUiState>(ReminderAlertUiState.Loading)
    private var alert: ReminderAlert? = null
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        lifecycleScope.launch {
            // An alert nobody answers lets the display sleep again; it stays on the lock screen.
            delay(SCREEN_ON_MILLIS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        show(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                alerts.handled.collect { key -> if (key == alert?.key) finish() }
            }
        }
        val cachedTheme = WindowThemeCache.read(this) ?: ThemeMode.SYSTEM
        val cachedDynamicColor = WindowThemeCache.readDynamicColor(this) ?: true
        setContent {
            val themeMode by settingsPrefs.themeMode.collectAsStateWithLifecycle(cachedTheme)
            val dynamicColor by settingsPrefs.dynamicColor.collectAsStateWithLifecycle(cachedDynamicColor)
            val soften by settingsPrefs.softenCalendarColors.collectAsStateWithLifecycle(true)
            CalendulaTheme(
                darkTheme = themeMode.isDark(isSystemInDarkTheme()),
                dynamicColor = dynamicColor,
                pureBlack = themeMode.isPureBlack,
            ) {
                CompositionLocalProvider(LocalSoftenColors provides soften) {
                    BackHandler(enabled = true) { /* Handled by Dismiss or Snooze, not by leaving. */ }
                    when (val current = state) {
                        ReminderAlertUiState.Loading ->
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                        is ReminderAlertUiState.Showing -> ReminderAlertScreen(
                            state = current,
                            onSnooze = { act(ReminderActionReceiver.ACTION_SNOOZE) },
                            onDismiss = { act(ReminderActionReceiver.ACTION_DISMISS) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    /** Tapped from a stale notification, or handled while this window was away. */
    override fun onResume() {
        super.onResume()
        val current = alert
        if (current != null && !notifier.isShowing(current)) finish()
    }

    private fun show(intent: Intent) {
        val next = ReminderActionReceiver.alertFrom(intent)
        if (next == null) {
            finish()
            return
        }
        alert = next
        loading?.cancel()
        state = ReminderAlertUiState.Loading
        loading = lifecycleScope.launch {
            val calendar = try {
                withContext(io) { calendarDataSource.calendars().firstOrNull { it.id == next.calendarId } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The alert still shows, only without its calendar.
                Log.w(TAG, "Could not read the calendar of event ${next.eventId}", e)
                null
            }
            state = ReminderAlertUiState.Showing(
                title = next.title,
                time = notifier.timeText(next),
                location = next.location?.takeIf { it.isNotBlank() },
                calendarName = calendar?.displayName,
                calendarColor = calendar?.color,
            )
        }
    }

    private fun act(action: String) {
        val current = alert ?: return
        sendBroadcast(ReminderActionReceiver.intent(this, action, current))
        finish()
    }

    companion object {
        private const val TAG = "ReminderAlertActivity"
        private const val SCREEN_ON_MILLIS = ReminderRingService.RING_TIMEOUT_MILLIS

        fun intent(context: Context, alert: ReminderAlert): Intent =
            Intent(context, ReminderAlertActivity::class.java)
                .setData("calendula://reminder-alert/${alert.key}".toUri())
                .putAlert(alert)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
