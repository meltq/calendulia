package de.jeanlucmakiola.calendula.data.reminders

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.ui.common.sourceAppLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reminder notification channels: one per calendar, grouped by account
 * (#145), created up front so each calendar can be muted or given its own sound
 * in the system settings before it ever fires.
 *
 * A channel the user has touched keeps their settings; [sync] only creates the
 * missing ones, renames changed ones, and deletes those whose calendar is gone.
 */
@Singleton
class ReminderChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarDataSource: CalendarDataSource,
) {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Bring the channels in line with the provider's calendars. */
    fun sync() {
        val manager = manager
        val existing = manager.notificationChannels
        val plan = plan(existing)
        plan.staleChannelIds.forEach(manager::deleteNotificationChannel)
        plan.staleGroupIds.forEach(manager::deleteNotificationChannelGroup)
        val groupNames = manager.notificationChannelGroups.associate { it.id to it.name?.toString() }
        write(
            groups = plan.groups.filter { groupNames[it.id] != groupLabel(it) },
            channels = plan.channelsToWrite,
            existing = existing,
        )
        if (plan.channels.isNotEmpty()) retireLegacyChannel()
    }

    /**
     * The channel to post [calendarId]'s reminder on, created first if a sync
     * has not got to it yet. Null when the calendar no longer exists.
     */
    fun channelFor(calendarId: Long): String? {
        val id = reminderChannelId(calendarId)
        val manager = manager
        if (manager.getNotificationChannel(id) != null) return id
        val existing = manager.notificationChannels
        val plan = plan(existing)
        val spec = plan.channels.firstOrNull { it.calendarId == calendarId } ?: return null
        val knownGroups = manager.notificationChannelGroups.mapTo(HashSet()) { it.id }
        write(
            groups = plan.groups.filter { it.id == spec.groupId && it.id !in knownGroups },
            channels = listOf(spec),
            existing = existing,
        )
        return id
    }

    private fun plan(existing: List<NotificationChannel>): ReminderChannelPlan = planReminderChannels(
        calendars = calendarDataSource.calendars(),
        existing = existing.map { ExistingReminderChannel(it.id, it.name.toString()) },
        existingGroupIds = manager.notificationChannelGroups.map { it.id },
        unnamed = context.getString(R.string.calendar_unnamed),
    )

    private fun write(
        groups: List<ReminderChannelGroupSpec>,
        channels: List<ReminderChannelSpec>,
        existing: List<NotificationChannel>,
    ) {
        val manager = manager
        if (groups.isNotEmpty()) {
            manager.createNotificationChannelGroups(groups.map { NotificationChannelGroup(it.id, groupLabel(it)) })
        }
        if (channels.isEmpty()) return
        val byId = existing.associateBy { it.id }
        val template = byId[LEGACY_REMINDER_CHANNEL_ID]
        // Only a channel's first creation sets its behaviour; later calls just
        // rename it, so an existing one passes its own importance back.
        manager.createNotificationChannels(
            channels.map { spec ->
                val current = byId[spec.id]
                NotificationChannel(
                    spec.id,
                    spec.name,
                    current?.importance ?: template?.importance ?: NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
                    group = spec.groupId
                    if (current == null && template != null) inheritFrom(template)
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
        // The pattern setter also flips vibration on or off, so it goes first.
        vibrationPattern = template.vibrationPattern
        enableVibration(template.shouldVibrate())
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
        val name = group.label.ifBlank { context.getString(R.string.calendar_unnamed) }
        if (!group.ambiguous) return name
        return context.getString(R.string.calendars_account_from_source, name, sourceAppLabel(context, group.accountType))
    }

    private companion object {
        const val TAG = "ReminderChannels"
    }
}
