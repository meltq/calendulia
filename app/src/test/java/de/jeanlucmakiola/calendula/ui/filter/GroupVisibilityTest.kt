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

    @Test
    fun `empty group is off`() {
        assertThat(groupVisibility(emptyList(), hidden = emptySet())).isEqualTo(ToggleableState.Off)
    }

    @Test
    fun `toggling twice from the stored set alternates`() {
        val hidden = setOf(9L)
        val once = toggledGroup(hidden, group)
        assertThat(once).isEqualTo(setOf(1L, 2L, 3L, 9L))
        assertThat(toggledGroup(once, group)).isEqualTo(setOf(9L))
    }

    @Test
    fun `toggling a partly shown group shows all`() {
        assertThat(toggledGroup(setOf(2L, 9L), group)).isEqualTo(setOf(9L))
    }
}
