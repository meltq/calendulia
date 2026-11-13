package de.jeanlucmakiola.calendula.ui.filter

import androidx.compose.ui.state.ToggleableState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GroupVisibilityTest {

    private val group = listOf(1L, 2L, 3L)

    @Test
    fun `all shown is on and hides on tap`() {
        val state = groupVisibility(group, hidden = setOf(9L))
        assertThat(state).isEqualTo(ToggleableState.On)
        assertThat(state.nextGroupVisible()).isFalse()
    }

    @Test
    fun `none shown is off and shows on tap`() {
        val state = groupVisibility(group, hidden = setOf(1L, 2L, 3L))
        assertThat(state).isEqualTo(ToggleableState.Off)
        assertThat(state.nextGroupVisible()).isTrue()
    }

    @Test
    fun `some shown is indeterminate and shows all on tap`() {
        val state = groupVisibility(group, hidden = setOf(2L))
        assertThat(state).isEqualTo(ToggleableState.Indeterminate)
        assertThat(state.nextGroupVisible()).isTrue()
    }
}
