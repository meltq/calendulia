package de.jeanlucmakiola.calendula.data.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.reminders.ReminderAlertStyle
import de.jeanlucmakiola.calendula.ui.common.sourceAppLabel
import java.util.Locale
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

    /** What the last [sync] saw, so an unchanged provider costs no channel writes. */
    @Volatile
    private var lastSync: Any? = null

    /**
     * Bring the channels in line with the provider's calendars. An empty
     * snapshot changes nothing: that is a provider hiccup far more often than
     * every calendar gone at once.
     */
    @Synchronized
    fun sync() {
        val calendars = calendarDataSource.calendars()
        if (calendars.isEmpty()) return
        val manager = manager
        val existing = manager.notificationChannels
        val existingIds = existing.mapTo(HashSet()) { it.id }
        if (syncKey(calendars, existingIds) == lastSync && LEGACY_REMINDER_CHANNEL_ID !in existingIds) return
        val groups = manager.notificationChannelGroups
        val plan = plan(calendars, existing, groups.map { it.id })
        plan.staleChannelIds.forEach(manager::deleteNotificationChannel)
        plan.staleGroupIds.forEach(manager::deleteNotificationChannelGroup)
        val groupNames = groups.associate { it.id to it.name?.toString() }
        write(
            groups = plan.groupsInUse.filter { groupNames[it.id] != groupLabel(it) },
            channels = plan.channelsToWrite,
            existing = existing,
        )
        if (RING_CHANNEL_ID in existingIds) writeRingChannel()
        val ids = existingIds - plan.staleChannelIds.toSet() + plan.channelsToWrite.map { it.id }
        lastSync = syncKey(calendars, if (retireLegacyChannel()) ids - LEGACY_REMINDER_CHANNEL_ID else ids)
    }

    private fun syncKey(calendars: List<CalendarSource>, channelIds: Set<String>): Any = listOf(
        calendars.map { listOf(it.id, it.displayName, it.accountName, it.accountType, it.isLocal, it.syncsEvents) },
        Locale.getDefault(),
        channelIds,
    )

    /**
     * The channel to post [calendarId]'s reminder on, created first if a sync
     * has not got to it yet. Null when the calendar no longer exists. Should the
     * calendar lookup fail or come back empty, the channel is still created,
     * plainly named and ungrouped, for the next sync to name and file — a
     * reminder is never lost to its channel. A ringing [style] posts on the
     * shared ring channel instead, once the calendar is known to exist.
     */
    @Synchronized
    fun channelFor(calendarId: Long, style: ReminderAlertStyle = ReminderAlertStyle.Notification): String? {
        val calendarChannel = calendarChannelFor(calendarId) ?: return null
        return if (style == ReminderAlertStyle.FullScreenRing) ringChannel() else calendarChannel
    }

    /**
     * The channel a ringing full-screen alert (#171) posts on, shared by every
     * calendar. Silent: the ring service owns the sound and vibration, so the
     * channel adds neither. High importance keeps the heads-up a denied
     * full-screen intent degrades to.
     */
    private fun ringChannel(): String {
        val manager = manager
        if (manager.getNotificationChannel(RING_CHANNEL_ID) == null) writeRingChannel()
        return RING_CHANNEL_ID
    }

    /** Creates the ring channel, or renames it after a language change. */
    private fun writeRingChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                RING_CHANNEL_ID,
                context.getString(R.string.reminder_ring_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_ring_channel_description)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /**
     * Whether a ring may sound for [calendarId]: neither its own channel nor
     * the ring channel is blocked, since the service plays the sound itself.
     */
    fun canRing(calendarId: Long): Boolean {
        val manager = manager
        return listOf(reminderChannelId(calendarId), RING_CHANNEL_ID).none {
            manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun calendarChannelFor(calendarId: Long): String? {
        val id = reminderChannelId(calendarId)
        val manager = manager
        if (manager.getNotificationChannel(id) != null) return id
        val existing = manager.notificationChannels
        val calendars = try {
            calendarDataSource.calendars()
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up calendar $calendarId for its channel", e)
            emptyList()
        }
        if (calendars.isEmpty()) {
            val fallback = ReminderChannelSpec(id, calendarId, context.getString(R.string.reminder_channel_name), null)
            write(emptyList(), listOf(fallback), existing)
            return id
        }
        val plan = plan(calendars, existing, emptyList())
        val spec = plan.channels.firstOrNull { it.calendarId == calendarId } ?: return null
        val knownGroups = manager.notificationChannelGroups.mapTo(HashSet()) { it.id }
        write(
            groups = plan.groups.filter { it.id == spec.groupId && it.id !in knownGroups },
            channels = listOf(spec),
            existing = existing,
        )
        return id
    }

    private fun plan(
        calendars: List<CalendarSource>,
        existing: List<NotificationChannel>,
        existingGroupIds: List<String>,
    ): ReminderChannelPlan = planReminderChannels(
        calendars = calendars,
        existing = existing.map { ExistingReminderChannel(it.id, it.name.toString(), it.description, it.group) },
        existingGroupIds = existingGroupIds,
        unnamed = context.getString(R.string.calendar_unnamed),
        description = context.getString(R.string.reminder_channel_description),
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
                    spec.groupId?.let { group = it }
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
        // Only honoured with Do Not Disturb access, so usually left to the user.
        setBypassDnd(template.canBypassDnd())
    }

    /**
     * Delete the pre-#145 channel once the per-calendar ones carry its settings,
     * but not while a reminder posted on it is still on screen — deleting the
     * channel would take that notification with it. Returns whether it is gone.
     */
    private fun retireLegacyChannel(): Boolean {
        val manager = manager
        if (manager.getNotificationChannel(LEGACY_REMINDER_CHANNEL_ID) == null) return true
        val showing = runCatching {
            manager.activeNotifications.any { it.notification.channelId == LEGACY_REMINDER_CHANNEL_ID }
        }.getOrElse { e ->
            Log.w(TAG, "Could not read active notifications", e)
            true
        }
        if (!showing) manager.deleteNotificationChannel(LEGACY_REMINDER_CHANNEL_ID)
        return !showing
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
