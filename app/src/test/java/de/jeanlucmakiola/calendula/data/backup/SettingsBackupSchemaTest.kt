package de.jeanlucmakiola.calendula.data.backup

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefKeys
import de.jeanlucmakiola.calendula.data.prefs.ReminderStateKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.floret.prefs.Pref
import org.junit.jupiter.api.Test

/**
 * Every key of the settings store is either backed up or deliberately left
 * out. A new key fails here until [SettingsBackupSchema] says which.
 */
class SettingsBackupSchemaTest {

    private val schema = SettingsBackupSchema

    private fun keysOf(holder: Any): List<String> = holder.javaClass.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name != "INSTANCE" }
        .flatMap { field ->
            field.isAccessible = true
            when (val value = field.get(null)) {
                is Pref<*> -> listOf(value.name)
                is Map<*, *> -> value.values.filterIsInstance<Pref<*>>().map { it.name }
                else -> emptyList()
            }
        }

    @Test
    fun `every stored key is classified`() {
        val stored = (keysOf(SettingsKeys) + keysOf(CalendarPrefKeys) + keysOf(ReminderStateKeys)).toSet()
        val classified = (schema.plain + schema.perCalendar + schema.excluded).map { it.name }

        assertThat(stored).isNotEmpty()
        assertThat(classified).containsAtLeastElementsIn(stored)
        assertThat(stored).containsAtLeastElementsIn(classified)
    }

    @Test
    fun `no key is classified twice`() {
        val classified = (schema.plain + schema.perCalendar + schema.excluded).map { it.name }

        assertThat(classified).containsNoDuplicates()
    }
}
