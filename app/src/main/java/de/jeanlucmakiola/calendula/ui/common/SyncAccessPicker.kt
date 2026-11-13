package de.jeanlucmakiola.calendula.ui.common

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.SyncAccount
import de.jeanlucmakiola.calendula.data.calendar.accountAccessIntent

/**
 * Launches the system account picker for accounts sync requests can't reach yet
 * (#240), one launch at a time. Access is re-read on every resume, which covers
 * the picker's return and grants or revokes made in system settings.
 */
@Composable
fun rememberSyncAccessPicker(sync: SyncViewModel): (Set<SyncAccount>) -> Unit {
    var open by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        open = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sync.refreshAccess() }
    val description = stringResource(R.string.sync_access_picker_description)
    return remember(launcher, description) {
        { accounts ->
            if (!open) {
                try {
                    launcher.launch(accountAccessIntent(accounts, description))
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
