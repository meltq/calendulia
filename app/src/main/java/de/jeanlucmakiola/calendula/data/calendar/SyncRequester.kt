package de.jeanlucmakiola.calendula.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import de.jeanlucmakiola.calendula.domain.CalendarSource
import javax.inject.Inject
import javax.inject.Singleton

/** The account behind a calendar row (`ACCOUNT_NAME` / `ACCOUNT_TYPE`). */
data class SyncAccount(val name: String, val type: String) {
    /** Whether a sync adapter can stand behind it: device-only calendars have none. */
    val isSyncable: Boolean
        get() = name.isNotBlank() && type.isNotBlank() && type != CalendarContract.ACCOUNT_TYPE_LOCAL
}

/**
 * Asks the system to run a calendar sync adapter sooner (#303, #240). The sync
 * itself stays the adapter's job; this only schedules it.
 */
interface SyncRequester {
    /**
     * Returns whether the request reached the system. [userInitiated] marks it
     * manual, which also overrides master-sync-off — only for a user's tap.
     */
    fun requestSync(account: SyncAccount, userInitiated: Boolean): Boolean
}

@Singleton
class AndroidSyncRequester @Inject constructor() : SyncRequester {
    override fun requestSync(account: SyncAccount, userInitiated: Boolean): Boolean = try {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            if (userInitiated) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(
            Account(account.name, account.type),
            CalendarContract.AUTHORITY,
            extras,
        )
        true
    } catch (e: Exception) {
        Log.w("SyncRequester", "Sync request refused", e)
        false
    }
}

/** The distinct accounts behind the visible, synced calendars — what a manual refresh asks. */
fun List<CalendarSource>.syncAccounts(): Set<SyncAccount> =
    filter { !it.isLocal && it.isVisibleInSystem }
        .map { SyncAccount(it.accountName, it.accountType) }
        .filterTo(linkedSetOf()) { it.isSyncable }

/** Outcome of a user-triggered sync request (#240). */
enum class SyncRequestResult { Requested, NothingToSync, Failed }
