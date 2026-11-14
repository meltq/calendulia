package de.jeanlucmakiola.calendula.data.backup

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WatchStepTest {

    @Test
    fun `a lone run arms the idle slot and leaves a waiting one`() {
        assertThat(watchStep(0, listOf(listOf(RUNNING), listOf(SUCCEEDED)))).isEqualTo(WatchStep.Arm(1, append = false))
        assertThat(watchStep(0, listOf(listOf(RUNNING), listOf(ENQUEUED)))).isEqualTo(WatchStep.None)
    }

    @Test
    fun `a run that still shows as running gets the watch queued behind it`() {
        assertThat(watchStep(1, listOf(listOf(RUNNING), listOf(RUNNING)))).isEqualTo(WatchStep.Arm(0, append = true))
    }

    @Test
    fun `runs that fired together end with one watch`() {
        val afterFirst = listOf(listOf(RUNNING), listOf(RUNNING, BLOCKED))
        assertThat(watchStep(1, afterFirst)).isEqualTo(WatchStep.None)
        assertThat(watchStep(0, afterFirst)).isEqualTo(WatchStep.None)
    }

    @Test
    fun `a second waiting watch is cancelled`() {
        assertThat(watchStep(0, listOf(listOf(ENQUEUED), listOf(ENQUEUED)))).isEqualTo(WatchStep.Cancel(1))
        assertThat(watchStep(null, listOf(listOf(ENQUEUED), listOf(ENQUEUED)))).isEqualTo(WatchStep.Cancel(1))
    }

    @Test
    fun `reconcile arms slot 0 only when nothing waits`() {
        assertThat(watchStep(null, listOf(emptyList(), emptyList()))).isEqualTo(WatchStep.Arm(0, append = false))
        assertThat(watchStep(null, listOf(emptyList(), listOf(ENQUEUED)))).isEqualTo(WatchStep.None)
    }
}
