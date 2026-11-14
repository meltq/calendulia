package de.jeanlucmakiola.calendula.data.backup

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ChangeRunPolicyTest {

    @Test
    fun `a run that hasn't started yet covers the change`() {
        assertThat(changeRunPolicy(listOf(ENQUEUED))).isNull()
        assertThat(changeRunPolicy(listOf(RUNNING, BLOCKED))).isNull()
    }

    @Test
    fun `a run already going gets the next one queued behind it`() {
        assertThat(changeRunPolicy(listOf(RUNNING))).isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    @Test
    fun `with nothing pending a fresh run starts`() {
        assertThat(changeRunPolicy(emptyList())).isEqualTo(ExistingWorkPolicy.REPLACE)
        assertThat(changeRunPolicy(listOf(SUCCEEDED, CANCELLED))).isEqualTo(ExistingWorkPolicy.REPLACE)
    }
}
