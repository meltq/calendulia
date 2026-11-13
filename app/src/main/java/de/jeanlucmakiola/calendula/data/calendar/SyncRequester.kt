package de.jeanlucmakiola.calendula.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
     * Schedule a sync of [account]; may throw if the system refuses. [userInitiated]
     * marks it manual, which also overrides master-sync-off — only for a user's tap.
     */
    fun requestSync(account: SyncAccount, userInitiated: Boolean)
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

    override fun requestSync(account: SyncAccount, userInitiated: Boolean) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            if (userInitiated) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(
            Account(account.name, account.type),
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

/**
 * Who a sync request can go to: [reachable] by name, [needsAccess] only once the
 * user lets the app see the account (Android 13+, see [accountAccessIntent]).
 */
data class SyncTargets(val reachable: Set<SyncAccount>, val needsAccess: Set<SyncAccount>) {
    companion object {
        val None = SyncTargets(emptySet(), emptySet())
    }
}

/**
 * The system account picker, limited to [accounts]: the one the user picks
 * becomes visible to the app, so sync requests can name it. One account per pick.
 */
fun accountAccessIntent(accounts: Collection<SyncAccount>, description: String): Intent =
    AccountManager.newChooseAccountIntent(
        null,
        accounts.map { Account(it.name, it.type) },
        accounts.map { it.type }.distinct().toTypedArray(),
        description,
        null,
        null,
        null,
    )

/** Outcome of a user-triggered sync request (#240). */
enum class SyncRequestResult { Requested, PartlyFailed, NeedsAccess, NothingToSync, Failed }
