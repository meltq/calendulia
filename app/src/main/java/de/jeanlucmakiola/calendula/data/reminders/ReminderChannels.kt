package de.jeanlucmakiola.calendula.data.reminders

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.ui.common.sourceAppLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * A sound picked in the app ([setSound], #518) recreates the channel under the
 * calendar's next version; the old one goes once nothing shows on it.
 */
@Singleton
class ReminderChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarDataSource: CalendarDataSource,
    private val settingsPrefs: SettingsPrefs,
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
        val versions = versions()
        if (syncKey(calendars, existingIds, versions) == lastSync && LEGACY_REMINDER_CHANNEL_ID !in existingIds) return
        val groups = manager.notificationChannelGroups
        val plan = plan(calendars, existing, groups.map { it.id }, versions, showingChannelIds())
        plan.staleChannelIds.forEach(manager::deleteNotificationChannel)
        plan.staleGroupIds.forEach(manager::deleteNotificationChannelGroup)
        val groupNames = groups.associate { it.id to it.name?.toString() }
        write(
            groups = plan.groupsInUse.filter { groupNames[it.id] != groupLabel(it) },
            channels = plan.channelsToWrite,
            existing = existing,
        )
        val ids = existingIds - plan.staleChannelIds.toSet() + plan.channelsToWrite.map { it.id }
        val legacyGone = retireLegacyChannel()
        // A kept channel still has to go once its notification does, so don't cache.
        lastSync = if (plan.keptChannelIds.isEmpty()) {
            syncKey(calendars, if (legacyGone) ids - LEGACY_REMINDER_CHANNEL_ID else ids, versions)
        } else {
            null
        }
    }

    private fun syncKey(calendars: List<CalendarSource>, channelIds: Set<String>, versions: Map<Long, Int>): Any = listOf(
        calendars.map { listOf(it.id, it.displayName, it.accountName, it.accountType, it.isLocal, it.syncsEvents) },
        Locale.getDefault(),
        channelIds,
        versions,
    )

    private fun versions(): Map<Long, Int> = runBlocking { settingsPrefs.reminderChannelVersions.first() }

    /** Channels with a notification on screen; on failure every channel counts, so none is deleted. */
    private fun showingChannelIds(): Set<String> = runCatching {
        manager.activeNotifications.mapNotNullTo(HashSet()) { it.notification.channelId }
    }.getOrElse { e ->
        Log.w(TAG, "Could not read active notifications", e)
        manager.notificationChannels.mapTo(HashSet()) { it.id }
    }

    /**
     * The channel to post [calendarId]'s reminder on, created first if a sync
     * has not got to it yet. Null when the calendar no longer exists. Should the
     * calendar lookup fail or come back empty, the channel is still created,
     * plainly named and ungrouped, for the next sync to name and file — a
     * reminder is never lost to its channel.
     */
    @Synchronized
    fun channelFor(calendarId: Long): String? {
        val versions = versions()
        val id = reminderChannelId(calendarId, versions[calendarId] ?: 0)
        val manager = manager
        if (manager.getNotificationChannel(id) != null) return id
        val existing = manager.notificationChannels
        val calendars = try {
            calendarDataSource.calendars()
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up calendar $calendarId for its channel", e)
            emptyList()
        }
        val fallback = ReminderChannelSpec(id, calendarId, context.getString(R.string.reminder_channel_name), null)
        if (calendars.isEmpty()) {
            write(emptyList(), listOf(fallback), existing)
            return id
        }
        val plan = plan(calendars, existing, emptyList(), versions)
        val spec = plan.channels.firstOrNull { it.calendarId == calendarId } ?: return null
        try {
            val knownGroups = manager.notificationChannelGroups.mapTo(HashSet()) { it.id }
            write(
                groups = plan.groups.filter { it.id == spec.groupId && it.id !in knownGroups },
                channels = listOf(spec),
                existing = existing,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not file the channel of calendar $calendarId", e)
            write(emptyList(), listOf(fallback), existing)
        }
        return id
    }

    /** What the system currently lets through for [calendarIds]' reminders (#145). */
    fun status(calendarIds: Collection<Long>): ReminderChannelStatus {
        val manager = manager
        val appEnabled = manager.areNotificationsEnabled()
        val channels = manager.notificationChannels.associate {
            it.id to ReminderChannelState(it.importance == NotificationManager.IMPORTANCE_NONE, it.group)
        }
        val blockedGroups = manager.notificationChannelGroups.filter { it.isBlocked }.mapTo(HashSet()) { it.id }
        return reminderChannelStatus(calendarIds, appEnabled, channels, blockedGroups, versions())
    }

    /**
     * The sound each of [calendarIds]' channels plays; null is silent. A
     * calendar whose channel doesn't exist yet is left out.
     */
    fun sounds(calendarIds: Collection<Long>): Map<Long, Uri?> {
        val versions = versions()
        val manager = manager
        return calendarIds.mapNotNull { id ->
            manager.getNotificationChannel(reminderChannelId(id, versions[id] ?: 0))?.let { id to it.sound }
        }.toMap()
    }

    /**
     * Give [calendarId]'s reminders [sound] (null = silent). A channel's sound is
     * fixed once created, so this moves the calendar to a new channel version
     * carrying over everything else, and retires the old one. Returns whether
     * the calendar had a channel to move.
     */
    suspend fun setSound(calendarId: Long, sound: Uri?): Boolean {
        val oldId = channelFor(calendarId) ?: return false
        val floor = manager.notificationChannels
            .mapNotNull { parseReminderChannelId(it.id) }
            .filter { it.first == calendarId }
            .maxOfOrNull { it.second + 1 } ?: 1
        val version = settingsPrefs.bumpReminderChannelVersion(calendarId, atLeast = floor)
        return recreate(oldId, reminderChannelId(calendarId, version), sound)
    }

    @Synchronized
    private fun recreate(oldId: String, newId: String, sound: Uri?): Boolean {
        val manager = manager
        val old = manager.getNotificationChannel(oldId) ?: return false
        val channel = NotificationChannel(newId, old.name, old.importance).apply {
            description = old.description
            old.group?.let { group = it }
            inheritFrom(old)
            setSound(sound, old.audioAttributes ?: DEFAULT_AUDIO_ATTRIBUTES)
        }
        manager.createNotificationChannel(channel)
        if (oldId !in showingChannelIds()) manager.deleteNotificationChannel(oldId)
        lastSync = null
        return true
    }

    private fun plan(
        calendars: List<CalendarSource>,
        existing: List<NotificationChannel>,
        existingGroupIds: List<String>,
        versions: Map<Long, Int>,
        showingChannelIds: Set<String> = emptySet(),
    ): ReminderChannelPlan = planReminderChannels(
        calendars = calendars,
        existing = existing.map { ExistingReminderChannel(it.id, it.name.toString(), it.description, it.group) },
        existingGroupIds = existingGroupIds,
        unnamed = context.getString(R.string.calendar_unnamed),
        description = context.getString(R.string.reminder_channel_description),
        versions = versions,
        showingChannelIds = showingChannelIds,
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
        val description = context.getString(R.string.reminder_channel_description)
        val legacy = byId[LEGACY_REMINDER_CHANNEL_ID]
        // Importance only applies on creation; an existing channel keeps its own.
        manager.createNotificationChannels(
            channels.map { spec ->
                val current = byId[spec.id]
                val template = spec.templateId?.let(byId::get) ?: legacy
                NotificationChannel(
                    spec.id,
                    spec.name,
                    current?.importance ?: template?.importance ?: NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    this.description = description
                    spec.groupId?.let { group = it }
                    if (current == null && template != null) inheritFrom(template)
                }
            },
        )
    }

    /**
     * Carry the old single channel's sound, vibration, lights and badge over to
     * a new per-calendar one (the system resets lock-screen visibility itself), so an upgrade keeps how reminders sounded.
     */
    private fun NotificationChannel.inheritFrom(template: NotificationChannel) {
        setSound(template.sound, template.audioAttributes)
        // The pattern setter also flips vibration on or off, so it goes first.
        vibrationPattern = template.vibrationPattern
        enableVibration(template.shouldVibrate())
        enableLights(template.shouldShowLights())
        lightColor = template.lightColor
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

        val DEFAULT_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

