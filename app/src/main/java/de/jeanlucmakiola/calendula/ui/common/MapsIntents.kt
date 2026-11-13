package de.jeanlucmakiola.calendula.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A `geo:` search for [query], answered by an installed maps app. */
fun geoSearchIntent(query: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))

/** A web maps search for [query], the fallback when no app handles `geo:`. */
fun webMapsSearchIntent(query: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

/** The first of [geoSearchIntent] and [webMapsSearchIntent] some installed app can open, or null. */
fun Context.resolvableMapsIntent(query: String): Intent? =
    listOf(geoSearchIntent(query), webMapsSearchIntent(query))
        .firstOrNull { it.resolveActivity(packageManager) != null }
