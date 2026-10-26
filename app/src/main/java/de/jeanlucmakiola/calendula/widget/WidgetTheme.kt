package de.jeanlucmakiola.calendula.widget

import androidx.compose.runtime.Composable
import androidx.glance.text.TextDecoration
import de.jeanlucmakiola.calendula.ui.theme.CalendulaDarkFallback
import de.jeanlucmakiola.calendula.ui.theme.CalendulaLightFallback
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.prefs.ThemeMode

/**
 * Glance equivalent of `CalendulaTheme`: Material You on API 31+, the app's
 * hand-tuned fallback schemes below that, in the light/dark [mode] the widget
 * was configured with (#292).
 */
@Composable
fun CalendulaGlanceTheme(mode: WidgetThemeMode, content: @Composable () -> Unit) {
    FloretGlanceTheme(
        lightScheme = CalendulaLightFallback,
        darkScheme = CalendulaDarkFallback,
        dynamicColor = true,
        mode = mode,
        content = content,
    )
}

/** A widget's root: its configured theme and background around [content]. */
@Composable
internal fun CalendulaWidgetRoot(appearance: WidgetAppearance, appTheme: ThemeMode, content: @Composable () -> Unit) {
    CalendulaGlanceTheme(appearance.theme.resolve(appTheme)) {
        WidgetSurface(background = appearance.background, content = content)
    }
}

/**
 * Glance's counterpart to the app's `declinedDecoration`: a declined invitation
 * reads the same on the home screen as it does inside the app (#180).
 */
fun glanceDeclinedDecoration(isDeclined: Boolean): TextDecoration =
    if (isDeclined) TextDecoration.LineThrough else TextDecoration.None
