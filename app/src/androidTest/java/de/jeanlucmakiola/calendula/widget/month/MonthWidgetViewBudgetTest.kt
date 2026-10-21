package de.jeanlucmakiola.calendula.widget.month

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders the month widget for a packed month through Glance's own translator
 * and counts what it inflates (#372): the view-id model in [WeekLayout] has to
 * match what Glance really builds, or a busy month fails with "too many views".
 */
@OptIn(ExperimentalGlanceApi::class)
@RunWith(AndroidJUnit4::class)
class MonthWidgetViewBudgetTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var calendarId = -1L

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    @Before
    fun packTheMonth() {
        val calendar = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(), calendar)
        calendarId = ContentUris.parseId(requireNotNull(uri))

        val zone = ZoneId.systemDefault()
        val first = LocalDate.now(zone).withDayOfMonth(1).minusDays(7)
        val events = (0 until 49).flatMap { offset ->
            val day = first.plusDays(offset.toLong())
            (0 until EVENTS_PER_DAY).map { i ->
                val start = day.atTime(8 + i, 0).atZone(zone).toInstant().toEpochMilli()
                ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, "Busy $i")
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, start + 30 * 60_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                }
            }
        }
        context.contentResolver.bulkInsert(CalendarContract.Events.CONTENT_URI.asSyncAdapter(), events.toTypedArray())
    }

    @After
    fun removeCalendar() {
        if (calendarId < 0) return
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId).asSyncAdapter(),
            null,
            null,
        )
    }

    @Test
    fun aPackedMonthRendersWithinGlancesViewIds() = runBlocking {
        for (size in listOf(DpSize(250.dp, 250.dp), DpSize(320.dp, 360.dp), DpSize(420.dp, 520.dp))) {
            val views = MonthWidget().compose(context, size = size)
            var total = 0
            var texts = 0
            instrumentation.runOnMainSync {
                val root = views.apply(context, FrameLayout(context))
                fun walk(view: View) {
                    total += 1
                    if (view is TextView) texts += 1
                    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                walk(root)
            }
            Log.i(TAG, "size=$size views=$total texts=$texts budget=$VIEW_BUDGET")
            // The grid itself rendered, not Glance's error layout: every day number is there.
            assertThat(texts).isAtLeast(35)
            assertThat(total).isAtMost(GLANCE_VIEW_IDS)
        }
    }

    private companion object {
        const val TAG = "MonthWidgetBudget"
        const val ACCOUNT = "calendula-widget-budget-test"
        const val EVENTS_PER_DAY = 6
        const val GLANCE_VIEW_IDS = 499
    }
}
