package de.jeanlucmakiola.calendula.widget

import androidx.compose.runtime.Composable
import androidx.glance.text.TextDecoration
import de.jeanlucmakiola.calendula.ui.theme.CalendulaDarkFallback
import de.jeanlucmakiola.calendula.ui.theme.CalendulaLightFallback
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetThemeMode

/**
 * Glance equivalent of `CalendulaTheme`: Material You on API 31+, the app's
 * hand-tuned fallback schemes below that, following the system's light/dark.
 */
@Composable
fun CalendulaGlanceTheme(content: @Composable () -> Unit) {
    FloretGlanceTheme(
        lightScheme = CalendulaLightFallback,
        darkScheme = CalendulaDarkFallback,
        dynamicColor = true,
        mode = WidgetThemeMode.SYSTEM,
        content = content,
    )
}

/**
 * Glance's counterpart to the app's `declinedDecoration`: a declined invitation
 * reads the same on the home screen as it does inside the app (#180).
 */
fun glanceDeclinedDecoration(isDeclined: Boolean): TextDecoration =
    if (isDeclined) TextDecoration.LineThrough else TextDecoration.None
