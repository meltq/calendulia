package de.jeanlucmakiola.calendula.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
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
    /** Account types with a calendar sync adapter installed; for any other a request does nothing. */
    fun adapterAccountTypes(): Set<String>

    /**
     * The accounts of [types] a request may name, or null when any may. From
     * Android 13 a request for an account the app can't see is dropped without a word.
     */
    fun addressableAccounts(types: Set<String>): Set<SyncAccount>?

    /**
     * Schedule a sync of [account], or of every calendar account when null; may
     * throw if the system refuses. [userInitiated] marks it manual, which also
     * overrides master-sync-off — only for a user's tap.
     */
    fun requestSync(account: SyncAccount?, userInitiated: Boolean)
}

@Singleton
class AndroidSyncRequester @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncRequester {
    // Lists only adapters in packages visible to the app — hence the SyncAdapter <queries> entry.
    override fun adapterAccountTypes(): Set<String> =
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == CalendarContract.AUTHORITY }
            .mapTo(mutableSetOf()) { it.accountType }

    override fun addressableAccounts(types: Set<String>): Set<SyncAccount>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = AccountManager.get(context)
        return types.flatMapTo(mutableSetOf()) { type ->
            manager.getAccountsByType(type).map { SyncAccount(it.name, it.type) }
        }
    }

    override fun requestSync(account: SyncAccount?, userInitiated: Boolean) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            if (userInitiated) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(
            account?.let { Account(it.name, it.type) },
            CalendarContract.AUTHORITY,
            extras,
        )
    }
}

/** The distinct non-local accounts behind the visible calendars — what a manual refresh asks. */
fun List<CalendarSource>.syncAccounts(): Set<SyncAccount> =
    filter { it.isVisibleInSystem }
        .map { SyncAccount(it.accountName, it.accountType) }
        .filterTo(linkedSetOf()) { it.isSyncable }

/** Outcome of a user-triggered sync request (#240). */
enum class SyncRequestResult { Requested, PartlyFailed, NothingToSync, Failed }
