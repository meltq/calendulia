package de.jeanlucmakiola.calendula.ui.common

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.SyncAccount
import de.jeanlucmakiola.calendula.data.calendar.accountAccessIntent
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf

/**
 * Launches the system account picker for an account sync requests can't reach
 * yet (#240), one launch at a time. Access is re-read on every resume, which covers
 * the picker's return and grants or revokes made in system settings.
 */
@Composable
fun rememberSyncAccessPicker(sync: SyncViewModel): (SyncAccount) -> Unit {
    var open by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        open = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sync.refreshAccess() }
    val description = stringResource(R.string.sync_access_picker_description)
    return remember(launcher, description) {
        { account ->
            if (!open) {
                try {
                    launcher.launch(accountAccessIntent(account, description))
                    open = true
                } catch (e: ActivityNotFoundException) {
                    Log.w("SyncAccessPicker", "No account picker", e)
                }
            }
        }
    }
}

/** Re-reads account access whenever [drawerState] opens; the app may stay resumed in multi-window. */
@Composable
fun RefreshSyncAccessOnOpen(drawerState: DrawerState, sync: SyncViewModel) {
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) sync.refreshAccess()
    }
}

/**
 * The accounts sync requests can't reach yet (#240), one row each: tapping one
 * opens the system picker for just that account, "Don't ask" stops offering it.
 * Closes itself once no account is left.
 */
@Composable
fun SyncAccessSheet(
    accounts: List<SyncAccount>?,
    onGrant: (SyncAccount) -> Unit,
    onDismissAccount: (SyncAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(accounts?.isEmpty()) { if (accounts?.isEmpty() == true) onDismiss() }
    FullScreenPicker(title = stringResource(R.string.drawer_sync_access), onDismiss = onDismiss, predictiveBack = true) {
        GroupedHint(stringResource(R.string.sync_access_hint), verticalPadding = 8.dp)
        accounts.orEmpty().forEachIndexed { index, account ->
            SyncAccountRow(
                account = account,
                position = positionOf(index, accounts.orEmpty().size),
                actionLabel = stringResource(R.string.sync_access_dont_ask),
                onAction = { onDismissAccount(account) },
                onClick = { onGrant(account) },
            )
        }
    }
}

/** One synced account with a text action, as the access sheet and the calendar manager list them. */
@Composable
fun SyncAccountRow(
    account: SyncAccount,
    position: Position,
    actionLabel: String,
    onAction: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    GroupedRow(
        title = account.name,
        summary = sourceAppName(account.type),
        position = position,
        minHeight = 64.dp,
        leading = { SourceLogo(account.type) },
        trailing = { TextButton(onClick = onAction) { Text(actionLabel) } },
        onClick = onClick,
    )
}
