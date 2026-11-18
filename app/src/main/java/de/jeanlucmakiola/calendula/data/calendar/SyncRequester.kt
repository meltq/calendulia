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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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

    /** Where [account]'s calendar sync stands, or null when the system won't say. */
    fun syncActivity(account: SyncAccount): SyncActivity?

    /** Fires whenever any sync on the device becomes pending, starts or ends. */
    fun syncActivityChanges(): Flow<Unit>
}

/** A calendar sync adapter's state for one account, from `READ_SYNC_STATS`. */
enum class SyncActivity { Idle, Pending, Active }

/** How waiting on requested syncs ended: seen through, or not confirmed in time. */
enum class SyncWait { Completed, Unconfirmed }

/**
 * Waits until the syncs of [accounts] have run (#240): each account must have
 * been seen pending or active and be idle again, or — for one that never showed
 * up within [graceMillis] — be idle. A sync already running at the first look
 * isn't the requested one, so it has to finish before the account counts. Unconfirmed after [timeoutMillis], or at
 * once when the system won't report sync state.
 */
suspend fun SyncRequester.awaitSyncs(
    accounts: Set<SyncAccount>,
    timeoutMillis: Long = SYNC_WAIT_MILLIS,
    graceMillis: Long = SYNC_GRACE_MILLIS,
    pollMillis: Long = SYNC_POLL_MILLIS,
): SyncWait {
    if (accounts.isEmpty()) return SyncWait.Completed
    val seen = HashSet<SyncAccount>()
    // One already running when asked is someone else's sync: it only counts once idle again.
    var priorRuns: MutableSet<SyncAccount>? = null
    val graceOver = AtomicBoolean(false)
    return withTimeoutOrNull(timeoutMillis) {
        channelFlow {
            launch { syncActivityChanges().collect { send(Unit) } }
            launch {
                delay(graceMillis)
                graceOver.set(true)
                send(Unit)
            }
            // The listener alone can miss a change made before it was registered.
            while (true) {
                send(Unit)
                delay(pollMillis)
            }
        }.conflate().map {
            val states = accounts.associateWith { syncActivity(it) ?: return@map SyncWait.Unconfirmed }
            val prior = priorRuns ?: states.filterValues { it == SyncActivity.Active }.keys.toMutableSet()
                .also { priorRuns = it }
            states.forEach { (account, state) ->
                if (state != SyncActivity.Active) prior -= account
                if (state != SyncActivity.Idle && account !in prior) seen += account
            }
            val done = states.all { (account, state) ->
                state == SyncActivity.Idle && account !in prior && (account in seen || graceOver.get())
            }
            if (done) SyncWait.Completed else null
        }.filterNotNull().first()
    } ?: SyncWait.Unconfirmed
}

const val SYNC_WAIT_MILLIS = 60_000L
const val SYNC_GRACE_MILLIS = 5_000L
const val SYNC_POLL_MILLIS = 2_000L

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

    override fun syncActivity(account: SyncAccount): SyncActivity? = try {
        val android = Account(account.name, account.type)
        when {
            ContentResolver.isSyncActive(android, CalendarContract.AUTHORITY) -> SyncActivity.Active
            ContentResolver.isSyncPending(android, CalendarContract.AUTHORITY) -> SyncActivity.Pending
            else -> SyncActivity.Idle
        }
    } catch (e: SecurityException) {
        null
    }

    override fun syncActivityChanges(): Flow<Unit> = callbackFlow {
        val handle = try {
            ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING,
            ) { trySend(Unit) }
        } catch (e: SecurityException) {
            null
        }
        awaitClose { handle?.let(ContentResolver::removeStatusChangeListener) }
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
 * [dismissed] are hidden accounts the user said not to ask about.
 */
data class SyncTargets(
    val reachable: Set<SyncAccount>,
    val needsAccess: Set<SyncAccount>,
    val dismissed: Set<SyncAccount> = emptySet(),
) {
    companion object {
        val None = SyncTargets(emptySet(), emptySet())
    }
}

/**
 * The system account picker, limited to [account]: once picked it becomes
 * visible to the app, so sync requests can name it.
 */
fun accountAccessIntent(account: SyncAccount, description: String): Intent =
    AccountManager.newChooseAccountIntent(
        null,
        listOf(Account(account.name, account.type)),
        arrayOf(account.type),
        description,
        null,
        null,
        null,
    )

/**
 * Outcome of a user-triggered sync request (#240). [Completed] is only ever set
 * once the requested syncs were seen through, see [awaitSyncs].
 */
enum class SyncRequestResult { Requested, Completed, SomeNeedAccess, PartlyFailed, NeedsAccess, NothingToSync, Failed }

/** A sync request's [result] and the [accounts] it actually reached. */
data class SyncRequest(val result: SyncRequestResult, val accounts: Set<SyncAccount> = emptySet())
