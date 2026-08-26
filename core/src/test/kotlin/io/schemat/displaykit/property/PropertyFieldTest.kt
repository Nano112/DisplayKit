package io.schemat.displaykit.property

import io.schemat.displaykit.action.ActionInvocationResult
import io.schemat.displaykit.action.ActionMenuSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropertyFieldTest {
    @Test
    fun integerFieldOwnsBoundsStepFormattingAndActions() {
        var value = 2
        val field = IntPropertyField(
            id = "signal",
            label = "Signal",
            min = 0,
            max = 3,
            unit = "V",
            get = { value },
            set = { value = it }
        )
        val model = PropertySheetModel(listOf(field))
        val session = ActionMenuSession(model.actionPage("properties"))

        assertEquals("2 V", field.valueText)
        assertEquals(ActionInvocationResult.INVOKED, session.invoke("signal.next"))
        assertEquals(3, value)
        assertEquals(PropertyChangeResult.AT_END, field.next())
        assertEquals(ActionInvocationResult.INVOKED, session.invoke("signal.previous"))
        assertEquals(2, value)
    }

    @Test
    fun booleanAndChoiceFieldsExposeConsistentNavigation() {
        var enabled = false
        val toggle = BooleanPropertyField("enabled", "Enabled", get = { enabled }, set = { enabled = it })
        var choice = "a"
        val select = ChoicePropertyField(
            "mode",
            "Mode",
            listOf("a", "b"),
            format = { it.uppercase() },
            get = { choice },
            set = { choice = it }
        )

        assertEquals(null, toggle.previousLabel)
        toggle.next()
        assertEquals("On", toggle.valueText)
        select.next()
        assertEquals("B", select.valueText)
        assertEquals(PropertyChangeResult.AT_END, select.next())
    }

    @Test
    fun invalidPropertyDefinitionsFailEarly() {
        assertFailsWith<IllegalArgumentException> {
            IntPropertyField("bad", "Bad", 5, 1, get = { 2 }, set = {})
        }
        assertFailsWith<IllegalArgumentException> {
            PropertySheetModel(
                listOf(
                    BooleanPropertyField("same", "One", get = { true }, set = {}),
                    BooleanPropertyField("same", "Two", get = { true }, set = {})
                )
            )
        }
    }
}
