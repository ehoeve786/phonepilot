package app.pocketpilot.core.automation

/**
 * The types a variable can have, as MacroDroid offers them. Values are stored as text; a dictionary or an array
 * keeps each entry as its own variable, named with the key or position after a dot.
 */
enum class VariableType(
    val label: String,
) {
    BOOLEAN("Boolean"),
    INTEGER("Integer"),
    STRING("String"),
    DECIMAL("Decimal"),
    DICTIONARY("Dictionary"),
    ARRAY("Array"),
    COLOUR("Colour"),
    ;

    /** True for types whose entries are separate variables, named with a key. */
    val holdsEntries: Boolean get() = this == DICTIONARY || this == ARRAY

    /** The value as the type stores it, or a failure saying what the type takes. */
    fun normalize(value: String): String =
        when (this) {
            BOOLEAN -> {
                when (value.trim().lowercase()) {
                    "true", "on", "yes" -> "true"
                    "false", "off", "no" -> "false"
                    else -> throw AutomationFailure("A Boolean is true or false")
                }
            }

            INTEGER -> {
                value.trim().toLongOrNull()?.toString() ?: throw AutomationFailure("An Integer is a whole number")
            }

            DECIMAL -> {
                value.trim().toDoubleOrNull()?.toString() ?: throw AutomationFailure("A Decimal is a number")
            }

            COLOUR -> {
                value.trim().takeIf { HEX_COLOUR.matches(it) }?.uppercase()
                    ?: throw AutomationFailure("A Colour is #RRGGBB or #AARRGGBB")
            }

            STRING, DICTIONARY, ARRAY -> {
                value
            }
        }

    companion object {
        private val HEX_COLOUR = Regex("""#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})""")

        /** The type with this label, or [STRING] when none matches. */
        fun named(label: String?): VariableType = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: STRING
    }
}

/**
 * The types of the rule's local variables: the types of its starting values, then the types its Set variable steps
 * give names that have no starting value. Global Set variable steps are left out.
 */
fun Rule.localVariableTypes(): Map<String, VariableType> {
    val types = LinkedHashMap<String, VariableType>()

    fun walk(steps: List<ActionStep>) {
        steps.forEach { step ->
            if (step.type == "var.set" && step.params["scope"]?.trim() != "global") {
                val name = step.params["name"]?.trim().orEmpty()
                if (name.isNotEmpty()) types.putIfAbsent(name, VariableType.named(step.params["type"]))
            }
            walk(step.children)
            walk(step.elseChildren)
        }
    }
    walk(actions)
    return variables.keys.associateWith { variableTypes[it] ?: VariableType.STRING } + types.filterKeys { it !in variables }
}
