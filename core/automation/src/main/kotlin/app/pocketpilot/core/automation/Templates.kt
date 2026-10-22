package app.pocketpilot.core.automation

/** Starting points offered on an empty Automations tab; each opens in the builder to adjust. */
object Templates {
    val all: List<Rule> =
        listOf(
            Rule(
                id = "template-quiet-night",
                name = "Quiet nights",
                triggers = listOf(TriggerSpec("time.at", mapOf("time" to "22:30"))),
                actions =
                    listOf(
                        ActionStep("setting.dnd", mapOf("value" to "on")),
                        ActionStep("setting.brightness", mapOf("value" to "20")),
                    ),
            ),
            Rule(
                id = "template-low-battery",
                name = "Low battery",
                triggers = listOf(TriggerSpec("battery.level", mapOf("direction" to "below", "percent" to "20"))),
                constraints = ConstraintGroup.of(ConstraintSpec("power.charging", mapOf("state" to "no"))),
                actions =
                    listOf(
                        ActionStep("setting.battery_saver", mapOf("value" to "on")),
                        ActionStep("notify.post", mapOf("title" to "Battery at {trigger.level}%", "text" to "Battery saver is on.")),
                    ),
            ),
            Rule(
                id = "template-headphones",
                name = "Headphones in, music on",
                triggers = listOf(TriggerSpec("headset.changed", mapOf("state" to "connected"))),
                actions =
                    listOf(
                        ActionStep("setting.volume", mapOf("value" to "40")),
                        ActionStep("media.control", mapOf("command" to "play")),
                    ),
            ),
            Rule(
                id = "template-read-aloud",
                name = "Read messages aloud in the car",
                triggers = listOf(TriggerSpec("notification.posted", mapOf("apps" to "com.whatsapp,com.google.android.apps.messaging"))),
                constraints = ConstraintGroup.of(ConstraintSpec("bluetooth.connected", mapOf("name" to "Car"))),
                actions = listOf(ActionStep("speak", mapOf("text" to "{trigger.title} says {trigger.text}"))),
            ),
            Rule(
                id = "template-screenshot-button",
                name = "Screenshot from a button",
                triggers = listOf(TriggerSpec("floating_button", mapOf("slot" to "1", "press" to "tap", "label" to "Screenshot"))),
                actions = listOf(ActionStep("screen.screenshot")),
            ),
            Rule(
                id = "template-stopwatch",
                name = "Workout timer",
                triggers = listOf(TriggerSpec("tile", mapOf("slot" to "1"))),
                actions =
                    listOf(
                        ActionStep("timer.set", mapOf("minutes" to "45", "label" to "Workout")),
                        ActionStep("setting.dnd", mapOf("value" to "on")),
                    ),
            ),
        )

    /** "When … then …" in one line, for the template list. */
    fun summary(rule: Rule): String =
        "When " + rule.triggers.joinToString(" or ") { Describe.trigger(it).replaceFirstChar(Char::lowercase) } +
            ", " + rule.actions.joinToString(", then ") { Describe.action(it).replaceFirstChar(Char::lowercase) }
}
