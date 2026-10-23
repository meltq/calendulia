package de.jeanlucmakiola.calendula.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.unit.ColorProvider
import de.jeanlucmakiola.floret.glance.LocalWidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetBackground

/** Cards sit one tone off the root fill, and stay opaque over a wallpaper (#292). */
@Composable
internal fun widgetCardColor(): ColorProvider = when (LocalWidgetBackground.current) {
    WidgetBackground.SOLID -> GlanceTheme.colors.surfaceVariant
    WidgetBackground.TINTED, WidgetBackground.TRANSPARENT -> GlanceTheme.colors.widgetBackground
}
