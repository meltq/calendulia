package de.jeanlucmakiola.calendula.ui.theme

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.edit
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.prefs.ThemeMode

/**
 * A synchronous copy of the theme choice, for the window backdrop drawn before
 * the first Compose frame. The real setting lives in DataStore, which only
 * answers asynchronously — too late for onCreate, so a Light, Dark or Black
 * override flashed the night-qualifier backdrop on a cold start or a
 * recreation (#181). MainActivity mirrors every stored value here and reads it
 * back before setContent.
 */
internal object WindowThemeCache {

    private const val FILE = "window_theme"
    private const val KEY_THEME_MODE = "theme_mode"

    /** The last mirrored theme choice, or null before the first mirror. */
    fun read(context: Context): ThemeMode? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }

    fun write(context: Context, mode: ThemeMode) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_THEME_MODE, null) == mode.name) return
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }
}

/**
 * The window backdrop matching this theme choice's Compose background, or null
 * for [ThemeMode.SYSTEM], whose night-qualified theme default already matches.
 */
@ColorRes
internal fun ThemeMode.windowBackgroundRes(): Int? = when (this) {
    ThemeMode.SYSTEM -> null
    ThemeMode.LIGHT -> R.color.window_background_light
    ThemeMode.DARK -> R.color.window_background_dark
    ThemeMode.BLACK -> R.color.window_background_black
}
