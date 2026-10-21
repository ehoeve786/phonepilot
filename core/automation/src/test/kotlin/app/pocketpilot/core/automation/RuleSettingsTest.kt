package app.pocketpilot.core.automation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings `automation.list` returns for a rule, in the shape `automation.update` takes. */
class RuleSettingsTest {
    private val rule =
        Rule(
            id = "r1",
            name = "New Skip Order",
            triggers = listOf(TriggerSpec("notification.posted", mapOf("apps" to "com.delco.courier", "text" to "matched"))),
            constraints = ConstraintGroup.of(ConstraintSpec("bluetooth.connected", mapOf("name" to "Tesla"))),
            actions =
                listOf(
                    ActionStep("speak", mapOf("text" to "New Skip Order")),
                    ActionStep(
                        type = "flow.repeat",
                        params = mapOf("count" to "2"),
                        children = listOf(ActionStep("vibrate", mapOf("ms" to "500"), onError = OnError.CONTINUE)),
                    ),
                ),
            runAs = RunMode.DIRECT,
        )

    @Test
    fun `triggers, conditions and steps come back with their settings`() {
        val settings = ruleSettings(rule)!!

        assertEquals("New Skip Order", settings.text("name"))
        val trigger =
            settings
                .getValue("triggers")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("notification.posted", trigger.text("type"))
        assertEquals("matched", trigger.getValue("params").jsonObject.text("text"))
        assertEquals("all", settings.text("conditionMode"))
        val condition =
            settings
                .getValue("conditions")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("Tesla", condition.getValue("params").jsonObject.text("name"))
        val steps = settings.getValue("actions").jsonArray
        assertEquals(
            "New Skip Order",
            steps[0]
                .jsonObject
                .getValue("params")
                .jsonObject
                .text("text"),
        )
        val repeat = steps[1].jsonObject
        assertEquals("2", repeat.getValue("params").jsonObject.text("count"))
        val child =
            repeat
                .getValue("actions")
                .jsonArray
                .single()
                .jsonObject
        assertTrue(
            child
                .getValue("continueOnError")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("direct", settings.text("runAs"))
    }

    @Test
    fun `a rule without conditions has no condition fields`() {
        val settings = ruleSettings(rule.copy(constraints = ConstraintGroup()))!!

        assertFalse(settings.containsKey("conditions"))
        assertFalse(settings.containsKey("conditionMode"))
    }

    @Test
    fun `a rule with nested condition groups has no settings, so an update cannot drop them`() {
        val nested =
            rule.copy(
                constraints = ConstraintGroup(items = listOf(ConstraintNode.Group(ConstraintGroup.of(ConstraintSpec("dnd.on"))))),
            )

        assertNull(ruleSettings(nested))
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
