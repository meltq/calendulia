package de.jeanlucmakiola.calendula.data.backup

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ChangeArmTest {

    @Test
    fun `a lone run arms the idle slot as it starts and leaves it at the end`() {
        assertThat(changeArm(0, listOf(RUNNING), listOf(SUCCEEDED), atStart = true)).isEqualTo(ChangeArm.Enqueue)
        assertThat(changeArm(0, listOf(RUNNING), listOf(ENQUEUED), atStart = false)).isNull()
    }

    @Test
    fun `an idle other slot is armed at the end unless a watch is queued behind this run`() {
        assertThat(changeArm(1, listOf(RUNNING), emptyList(), atStart = false)).isEqualTo(ChangeArm.Enqueue)
        assertThat(changeArm(1, listOf(RUNNING, BLOCKED), listOf(SUCCEEDED), atStart = false)).isNull()
    }

    @Test
    fun `two runs that fired together leave one watch`() {
        assertThat(changeArm(0, listOf(RUNNING), listOf(RUNNING), atStart = true)).isNull()
        assertThat(changeArm(1, listOf(RUNNING), listOf(RUNNING), atStart = true)).isNull()
        assertThat(changeArm(0, listOf(RUNNING), listOf(RUNNING), atStart = false)).isEqualTo(ChangeArm.Append)
        assertThat(changeArm(1, listOf(RUNNING, BLOCKED), listOf(RUNNING), atStart = false)).isNull()
        assertThat(changeArm(1, listOf(RUNNING), listOf(RUNNING), atStart = false)).isNull()
    }
}
