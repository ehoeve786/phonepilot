package app.pocketpilot.core.automation

/** Checks a rule against the catalog before it is saved or proposed. */
object RuleValidator {
    fun problems(
        rule: Rule,
        sideload: Boolean = true,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (rule.name.isBlank()) problems += "Give the rule a name"
        if (rule.triggers.isEmpty()) problems += "Add a trigger, or \"Run button only\""
        rule.triggers.forEach { spec ->
            val def = AutomationCatalog.trigger(spec.type)
            if (def == null) {
                problems += "Unknown trigger ${spec.type}"
            } else {
                if (def.sideloadOnly && !sideload) problems += "${def.title} is only in the sideload build"
                missing(def.fields, spec.params).forEach { problems += "${def.title}: set ${it.label}" }
                place(def.title, def.fields, spec.params, problems)
            }
        }
        group(rule.constraints, problems, sideload)
        if (rule.runAs == RunMode.AGENT) {
            if (rule.agentPrompt.isBlank()) problems += "Write the task for Agent mode"
        } else if (rule.actions.isEmpty()) {
            problems += "Add an action"
        }
        actions(rule.actions, problems, sideload)
        return problems
    }

    private fun actions(
        steps: List<ActionStep>,
        problems: MutableList<String>,
        sideload: Boolean,
    ) {
        steps.forEach { step ->
            val def = AutomationCatalog.action(step.type)
            if (def == null) {
                problems += "Unknown action ${step.type}"
                return@forEach
            }
            if (def.sideloadOnly && !sideload) problems += "${def.title} is only in the sideload build"
            missing(def.fields, step.params).forEach { problems += "${def.title}: set ${it.label}" }
            if (def.conditional && def.type != "flow.while" && step.constraints.isEmpty && def.type != "flow.wait_until") {
                problems += "${def.title}: add a condition"
            }
            if (def.type == "flow.wait_until" && step.constraints.isEmpty) problems += "${def.title}: add a condition"
            if (!def.container && (step.children.isNotEmpty() || step.elseChildren.isNotEmpty())) {
                problems += "${def.title} cannot hold other actions"
            }
            group(step.constraints, problems, sideload)
            actions(step.children, problems, sideload)
            actions(step.elseChildren, problems, sideload)
        }
    }

    private fun group(
        group: ConstraintGroup,
        problems: MutableList<String>,
        sideload: Boolean,
    ) {
        group.items.forEach { node ->
            when (node) {
                is ConstraintNode.Check -> {
                    val def = AutomationCatalog.constraint(node.spec.type)
                    if (def == null) {
                        problems += "Unknown condition ${node.spec.type}"
                    } else {
                        if (def.sideloadOnly && !sideload) problems += "${def.title} is only in the sideload build"
                        missing(def.fields, node.spec.params).forEach { problems += "${def.title}: set ${it.label}" }
                        place(def.title, def.fields, node.spec.params, problems)
                    }
                }

                is ConstraintNode.Group -> {
                    group(node.group, problems, sideload)
                }
            }
        }
    }

    /** A location setting must be coordinates a map app gives. */
    private fun place(
        title: String,
        fields: List<FieldDef>,
        params: Map<String, String>,
        problems: MutableList<String>,
    ) {
        if (fields.none { it.key == "at" } || params["at"].isNullOrBlank()) return
        if (Place.coordinates(params["at"].orEmpty()) == null) problems += "$title: give the place as latitude, longitude"
    }

    private fun missing(
        fields: List<FieldDef>,
        params: Map<String, String>,
    ): List<FieldDef> = fields.filter { it.required && it.default == null && params[it.key].isNullOrBlank() }

    /** [params] with every field's default filled in where it is missing. */
    fun withDefaults(
        fields: List<FieldDef>,
        params: Map<String, String>,
    ): Map<String, String> = fields.mapNotNull { f -> f.default?.let { f.key to it } }.toMap() + params.filterValues { it.isNotEmpty() }
}
