package de.jeanlucmakiola.calendula.data.reminders

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefs
import de.jeanlucmakiola.calendula.ui.common.sourceAppPackages
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reminder notification channels: one per switched-on calendar, grouped
 * by account (#145), so each calendar can be muted or given its own sound in
 * the system settings before it ever fires.
 *
 * A channel the user has touched keeps their settings; [sync] only creates the
 * missing ones, refreshes names, and deletes channels whose calendar is gone or
 * switched off — the system restores a deleted channel's settings if its id
 * comes back.
 */
@Singleton
class ReminderChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarDataSource: CalendarDataSource,
    private val calendarPrefs: CalendarPrefs,
) {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Bring the channels in line with the provider's switched-on calendars. */
    suspend fun sync() {
        val pending = calendarPrefs.pendingDisabledCalendarIds.first()
        val calendars = calendarDataSource.calendars().filter { it.isVisibleInSystem && it.id !in pending }
        val manager = manager
        val plan = planReminderChannels(
            calendars = calendars,
            existingChannelIds = manager.notificationChannels.map { it.id },
            existingGroupIds = manager.notificationChannelGroups.map { it.id },
        )
        plan.staleChannelIds.forEach(manager::deleteNotificationChannel)
        plan.staleGroupIds.forEach(manager::deleteNotificationChannelGroup)
        create(plan)
        if (plan.channels.isNotEmpty()) retireLegacyChannel()
    }

    /**
     * The channel to post [calendarId]'s reminder on, created first if a sync
     * has not got to it yet. Null when the calendar no longer exists.
     */
    fun channelFor(calendarId: Long): String? {
        val id = reminderChannelId(calendarId)
        if (manager.getNotificationChannel(id) != null) return id
        val calendar = calendarDataSource.calendars().firstOrNull { it.id == calendarId } ?: return null
        create(planReminderChannels(listOf(calendar), emptyList(), emptyList()))
        return id
    }

    private fun create(plan: ReminderChannelPlan) {
        val manager = manager
        manager.createNotificationChannelGroups(
            plan.groups.map { NotificationChannelGroup(it.id, groupLabel(it)) },
        )
        // Only a channel's first creation sets its behaviour; later calls just
        // rename it, so an existing one passes its own importance back.
        val template = manager.getNotificationChannel(LEGACY_REMINDER_CHANNEL_ID)
        val unnamed = context.getString(R.string.calendar_unnamed)
        manager.createNotificationChannels(
            plan.channels.map { spec ->
                val existing = manager.getNotificationChannel(spec.id)
                NotificationChannel(
                    spec.id,
                    spec.name.ifBlank { unnamed },
                    existing?.importance ?: template?.importance ?: NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
                    group = spec.groupId
                    if (existing == null && template != null) inheritFrom(template)
                }
            },
        )
    }

    /**
     * Carry the old single channel's sound, vibration and lock-screen choices
     * over to a new per-calendar one, so an upgrade keeps how reminders sounded.
     */
    private fun NotificationChannel.inheritFrom(template: NotificationChannel) {
        setSound(template.sound, template.audioAttributes)
        enableVibration(template.shouldVibrate())
        vibrationPattern = template.vibrationPattern
        enableLights(template.shouldShowLights())
        lightColor = template.lightColor
        lockscreenVisibility = template.lockscreenVisibility
        setShowBadge(template.canShowBadge())
    }

    /**
     * Delete the pre-#145 channel once the per-calendar ones carry its settings,
     * but not while a reminder posted on it is still on screen — deleting the
     * channel would take that notification with it.
     */
    private fun retireLegacyChannel() {
        val manager = manager
        if (manager.getNotificationChannel(LEGACY_REMINDER_CHANNEL_ID) == null) return
        val showing = runCatching {
            manager.activeNotifications.any { it.notification.channelId == LEGACY_REMINDER_CHANNEL_ID }
        }.getOrElse { e ->
            Log.w(TAG, "Could not read active notifications", e)
            true
        }
        if (!showing) manager.deleteNotificationChannel(LEGACY_REMINDER_CHANNEL_ID)
    }

    private fun groupLabel(group: ReminderChannelGroupSpec): String {
        if (group.isLocal) return context.getString(R.string.calendars_local_header)
        val name = group.accountName.ifBlank { group.accountType }
            .ifBlank { context.getString(R.string.calendar_unnamed) }
        if (!group.ambiguous) return name
        return context.getString(R.string.calendars_account_from_source, name, sourceAppLabel(group.accountType))
    }

    private fun sourceAppLabel(accountType: String): String {
        val pm = context.packageManager
        return sourceAppPackages(context, accountType).firstNotNullOfOrNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: accountType
    }

    private companion object {
        const val TAG = "ReminderChannels"
    }
}
