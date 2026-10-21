package app.pocketpilot.core.automation

import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun schema(text: String): JsonObject = AutomationJson.json.parseToJsonElement(text).jsonObject

private fun automationSpec(
    name: String,
    title: String,
    description: String,
    inputSchema: String,
    tier: RiskTier,
    readOnly: Boolean = false,
) = ToolSpec(
    name = name,
    title = title,
    description = description,
    inputSchema = schema(inputSchema),
    riskTier = tier,
    requiredScopes = setOf(Scope.TASK_RUN),
    annotations = ToolAnnotations(readOnly = readOnly),
    availability = Availability(Phase.P1),
)

private fun invalid(message: String): Nothing =
    throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, message, "Call automation.catalog for the types and their settings.")

/** `automation.catalog`: every trigger, condition and action type this build offers, with its settings. */
class AutomationCatalogTool(
    /** False in the Play build, which leaves out sideload-only types. */
    private val sideload: Boolean = true,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.catalog",
            "Automation building blocks",
            "Lists the trigger, condition and action types automation.propose accepts, each with its settings " +
                "(* = required, = default, [choices]). Pass kind to list only one of them.",
            """{"type":"object","properties":{"kind":{"type":"string","enum":["triggers","conditions","actions"]}},"additionalProperties":false}""",
            RiskTier.READ,
            readOnly = true,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val kind = (call.arguments["kind"] as? JsonPrimitive)?.contentOrNull
        val text =
            buildString {
                if (kind == null || kind == "triggers") {
                    appendLine("triggers (variables they set: {trigger.<name>}):")
                    AutomationCatalog.triggers.filter { sideload || !it.sideloadOnly }.forEach {
                        appendLine(
                            line(it.type, it.title, it.fields, it.variables),
                        )
                    }
                }
                if (kind == null || kind == "conditions") {
                    appendLine("conditions:")
                    AutomationCatalog.constraints.filter { sideload || !it.sideloadOnly }.forEach {
                        appendLine(
                            line(it.type, it.title, it.fields),
                        )
                    }
                }
                if (kind == null || kind == "actions") {
                    appendLine("actions (tier in brackets; flow.if/flow.while/flow.wait_until use the step's conditions):")
                    AutomationCatalog.actions
                        .filter {
                            sideload || !it.sideloadOnly
                        }.forEach { appendLine(line(it.type, "${it.title} [${it.tier.name.lowercase()}]", it.fields)) }
                }
            }
        return ToolResult.success(text.trimEnd())
    }

    private fun line(
        type: String,
        title: String,
        fields: List<FieldDef>,
        variables: List<String> = emptyList(),
    ): String =
        buildString {
            append("- ").append(type).append(": ").append(title)
            if (fields.isNotEmpty()) {
                append(" (")
                append(
                    fields.joinToString { f ->
                        buildString {
                            append(f.key)
                            if (f.required && f.default == null) append('*')
                            f.default?.let { append('=').append(it) }
                            if (f.choices.isNotEmpty()) append(" [").append(f.choices.joinToString("|") { it.value }).append(']')
                            if (f.kind == FieldKind.APP) append(" packages")
                            if (f.kind == FieldKind.TIME) append(" HH:mm")
                        }
                    },
                )
                append(')')
            }
            if (variables.isNotEmpty()) append(" sets ").append(variables.joinToString())
        }
}

/**
 * `automation.list`: the owner's rules and proposals. Each rule has its sentences in words and its full
 * settings in `rule`, in the shape `automation.update` takes, so one step can be changed and sent back.
 */
class AutomationListTool(
    private val store: RuleStore,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.list",
            "List automations",
            "Lists the phone's automations and pending proposals: id, name, on or off, whether it waits for the owner, " +
                "its triggers and actions in words, whether an AI app may run it (automation.run), its last run, and " +
                "its full settings as rule, in the shape automation.update takes. To change one step, edit that rule " +
                "and send the whole of it back with automation.update.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            RiskTier.READ,
            readOnly = true,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val rules =
            buildJsonArray {
                store.rules.forEach { rule ->
                    add(
                        buildJsonObject {
                            put("id", rule.id)
                            put("name", rule.name)
                            put("enabled", rule.enabled)
                            if (rule.pending) put("status", "waiting for the owner to accept")
                            put("when", JsonArray(rule.triggers.map { JsonPrimitive(Describe.trigger(it)) }))
                            if (!rule.constraints.isEmpty) put("onlyIf", Describe.group(rule.constraints))
                            put("then", JsonArray(rule.actions.map { JsonPrimitive(Describe.action(it)) }))
                            put("runnableByAi", rule.triggers.any { it.type == "mcp.call" })
                            rule.lastRun?.let { put("lastRun", "${it.outcome.name.lowercase()} at ${it.atMillis}") }
                            ruleSettings(rule)?.let { put("rule", it) }
                        },
                    )
                }
            }
        return ToolResult.success(buildJsonObject { put("automations", rules) }.toString())
    }
}

/**
 * A rule's settings in the shape `automation.update` takes, or null when it has a group of conditions inside
 * another group. That shape has no place for one, and an update would drop it, so such a rule is edited on the phone.
 */
internal fun ruleSettings(rule: Rule): JsonObject? {
    if (hasNestedGroup(rule.constraints) || rule.actions.any { hasNestedStep(it) }) return null
    return buildJsonObject {
        put("name", rule.name)
        put("triggers", JsonArray(rule.triggers.map { settingsOf(it.type, it.params) }))
        conditionSettings(this, rule.constraints)
        put("actions", JsonArray(rule.actions.map { stepSettings(it) }))
        put("runAs", if (rule.runAs == RunMode.AGENT) "agent" else "direct")
        if (rule.agentPrompt.isNotEmpty()) put("agentPrompt", rule.agentPrompt)
    }
}

private fun hasNestedGroup(group: ConstraintGroup): Boolean = group.items.any { it is ConstraintNode.Group }

private fun hasNestedStep(step: ActionStep): Boolean =
    hasNestedGroup(step.constraints) || (step.children + step.elseChildren).any { hasNestedStep(it) }

private fun stepSettings(step: ActionStep): JsonObject =
    buildJsonObject {
        put("type", step.type)
        put("params", paramsOf(step.params))
        conditionSettings(this, step.constraints)
        if (step.children.isNotEmpty()) put("actions", JsonArray(step.children.map { stepSettings(it) }))
        if (step.elseChildren.isNotEmpty()) put("elseActions", JsonArray(step.elseChildren.map { stepSettings(it) }))
        if (step.onError == OnError.CONTINUE) put("continueOnError", true)
    }

/** Writes a flat group of conditions as `conditionMode` and `conditions`. Callers have already ruled out nested groups. */
private fun conditionSettings(
    builder: JsonObjectBuilder,
    group: ConstraintGroup,
) {
    if (group.isEmpty) return
    builder.put("conditionMode", group.mode.name.lowercase())
    builder.put("conditions", JsonArray(group.items.map { settingsOf((it as ConstraintNode.Check).spec) }))
}

private fun settingsOf(
    type: String,
    params: Map<String, String>,
): JsonObject =
    buildJsonObject {
        put("type", type)
        put("params", paramsOf(params))
    }

private fun settingsOf(spec: ConstraintSpec): JsonObject = settingsOf(spec.type, spec.params)

private fun paramsOf(params: Map<String, String>): JsonObject = buildJsonObject { params.forEach { (key, value) -> put(key, value) } }

/** A rule as `automation.propose` takes it: flatter than [Rule], with conditions as a list. */
@Serializable
data class ProposedRule(
    val name: String,
    val triggers: List<TriggerSpec>,
    val conditions: List<ConstraintSpec> = emptyList(),
    /** all, any, none or one. */
    val conditionMode: String = "all",
    val actions: List<ProposedAction> = emptyList(),
    /** direct, or agent with agentPrompt. */
    val runAs: String = "direct",
    val agentPrompt: String = "",
)

@Serializable
data class ProposedAction(
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val conditions: List<ConstraintSpec> = emptyList(),
    val conditionMode: String = "all",
    val actions: List<ProposedAction> = emptyList(),
    val elseActions: List<ProposedAction> = emptyList(),
    val continueOnError: Boolean = false,
)

private fun mode(text: String): GroupMode = GroupMode.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: GroupMode.ALL

private fun conditions(
    specs: List<ConstraintSpec>,
    mode: String,
) = ConstraintGroup(mode(mode), specs.map { ConstraintNode.Check(it) })

private fun ProposedAction.toStep(): ActionStep =
    ActionStep(
        type = type,
        params = params,
        constraints = conditions(conditions, conditionMode),
        onError = if (continueOnError) OnError.CONTINUE else OnError.STOP,
        children = actions.map { it.toStep() },
        elseChildren = elseActions.map { it.toStep() },
    )

/**
 * `automation.propose`: an AI app suggests a rule. It lands as a proposal that does nothing until the
 * owner accepts it on the phone, so nothing reached over the network can change the phone silently.
 */
class AutomationProposeTool(
    private val store: RuleStore,
    private val sideload: Boolean,
    /** Tells the owner, for example with a notification. */
    private val onProposed: (Rule) -> Unit,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.propose",
            "Propose an automation",
            "Suggests an automation for the owner to accept on the phone; it does nothing until then. Get the types " +
                "and settings from automation.catalog. Shape: {name, triggers:[{type, params}], conditions:[{type, params}], " +
                "conditionMode: all|any|none|one, actions:[{type, params, conditions, actions (inside flow.if/repeat/while), " +
                "elseActions, continueOnError}], runAs: direct|agent, agentPrompt}. Settings are strings; {trigger.<name>} " +
                "and other variables are filled in when it runs. Returns the proposal id; read the outcome with automation.list. " +
                "To change a rule the owner already has, use automation.update instead.",
            """{"type":"object","properties":{$RULE_PROPERTIES},"required":["name","triggers"],"additionalProperties":false}""",
            RiskTier.READ,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val rule = validRule(call.arguments, store.newId(), session.principal, sideload)
        store.upsert(rule)
        onProposed(store.rule(rule.id) ?: rule)
        return ToolResult.success(
            buildJsonObject {
                put("id", rule.id)
                put("status", "pending")
                put("note", "The owner sees it on the phone and can accept, edit or decline it.")
            }.toString(),
        )
    }
}

/**
 * `automation.update`: an AI app changes one of the owner's rules in place, so the owner does not accept a
 * copy and delete the old one. It is confirmed like any sensitive tool (or allowed in its tool mode). A change
 * that adds or alters shell commands still waits as a proposal, since accepting those needs the owner's
 * fingerprint; accepting it updates the rule in place.
 */
class AutomationUpdateTool(
    private val store: RuleStore,
    private val sideload: Boolean,
    /** Tells the owner a rule was changed, and by whom. */
    private val onChanged: (Rule, String) -> Unit,
    /** Tells the owner a change waits for them, as for a new proposal. */
    private val onProposed: (Rule) -> Unit,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.update",
            "Change an automation",
            "Changes one of the owner's automations in place: give its id (from automation.list) and the whole new " +
                "rule, shaped as for automation.propose. Its on/off switch, pin and history stay. A change to shell " +
                "commands waits for the owner as a proposal instead.",
            """{"type":"object","properties":{"id":{"type":"string"},$RULE_PROPERTIES},"required":["id","name","triggers"],
            "additionalProperties":false}""",
            RiskTier.SENSITIVE,
        )

    override suspend fun confirmationDetail(call: ToolCall): String? =
        (call.arguments["id"] as? JsonPrimitive)?.contentOrNull?.let { id -> store.rule(id)?.name ?: id }

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val id = call.arguments["id"]?.jsonPrimitive?.contentOrNull ?: invalid("Give id")
        val current =
            store.rule(id) ?: throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "No automation $id", "Call automation.list for the ids.")
        val changed = validRule(JsonObject(call.arguments - "id"), id, session.principal, sideload)
        val shellChanged = changed.usesShell() && changed.shellCommands() != current.shellCommands()
        if (current.pending || !shellChanged) {
            store.change(id, changed.copy(proposedBy = current.proposedBy))
            val saved = store.rule(id) ?: changed
            if (!current.pending) onChanged(saved, session.principal)
            return ToolResult.success(
                buildJsonObject {
                    put("id", id)
                    put("status", if (current.pending) "pending" else "updated")
                }.toString(),
            )
        }
        val proposal = changed.copy(id = store.newId(), enabled = false, replaces = id)
        store.upsert(proposal)
        onProposed(store.rule(proposal.id) ?: proposal)
        return ToolResult.success(
            buildJsonObject {
                put("id", proposal.id)
                put("status", "pending")
                put("note", "It changes shell commands, so the owner accepts it on the phone; then it updates $id in place.")
            }.toString(),
        )
    }
}

/** Parses and checks a rule in the propose shape, or fails with what is wrong. */
private fun validRule(
    arguments: JsonObject,
    id: String,
    principal: String,
    sideload: Boolean,
): Rule {
    val proposed =
        runCatching { AutomationJson.json.decodeFromJsonElement(ProposedRule.serializer(), arguments) }
            .getOrElse { invalid("The rule does not have the expected shape: ${it.message?.lineSequence()?.firstOrNull()}") }
    val rule =
        Rule(
            id = id,
            name = proposed.name.trim(),
            enabled = false,
            triggers = proposed.triggers,
            constraints = conditions(proposed.conditions, proposed.conditionMode),
            actions = proposed.actions.map { it.toStep() },
            runAs = if (proposed.runAs.equals("agent", ignoreCase = true)) RunMode.AGENT else RunMode.DIRECT,
            agentPrompt = proposed.agentPrompt,
            proposedBy = principal,
        )
    val problems = RuleValidator.problems(rule, sideload)
    if (problems.isNotEmpty()) invalid("The rule has problems: ${problems.joinToString("; ")}")
    return rule
}

/** Settings of a trigger, condition or action, all strings. */
private const val PARAMS = """{"type":"object","additionalProperties":{"type":"string"}}"""

/** A trigger or condition: a catalog type and its settings. */
private const val TYPED_ITEM = """{"type":"object","properties":{"type":{"type":"string"},"params":$PARAMS},"required":["type"]}"""

private const val CONDITION_MODE = """{"type":"string","enum":["all","any","none","one"]}"""

/** The rule shape `automation.propose` and `automation.update` take. */
private const val RULE_PROPERTIES = """"name":{"type":"string"},"triggers":{"type":"array","items":$TYPED_ITEM},
    "conditions":{"type":"array","items":$TYPED_ITEM},"conditionMode":$CONDITION_MODE,
    "actions":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"},"params":$PARAMS,
    "conditions":{"type":"array","items":$TYPED_ITEM},"conditionMode":$CONDITION_MODE,
    "actions":{"type":"array","items":{"type":"object"},"description":"Steps inside flow.if, repeat or while, shaped like this one."},
    "elseActions":{"type":"array","items":{"type":"object"},"description":"Steps for the else branch of flow.if."},
    "continueOnError":{"type":"boolean"}},"required":["type"]}},
    "runAs":{"type":"string","enum":["direct","agent"]},"agentPrompt":{"type":"string"}"""

/** `automation.run`: runs a rule whose owner allowed AI apps to run it (its trigger "AI app asks"). */
class AutomationRunTool(
    private val store: RuleStore,
    private val engine: () -> AutomationEngine,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.run",
            "Run an automation",
            "Runs one of the owner's automations by id and returns its log. Only automations with the trigger " +
                "\"AI app asks\" (mcp.call) can be run this way; see runnableByAi in automation.list.",
            """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""",
            RiskTier.SENSITIVE,
        )

    override val timeoutMs: Long = AutomationEngine.MAX_RUN_MS + 10_000

    override suspend fun confirmationDetail(call: ToolCall): String? =
        (call.arguments["id"] as? JsonPrimitive)?.contentOrNull?.let { id -> store.rule(id)?.name ?: id }

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val id = call.arguments["id"]?.jsonPrimitive?.contentOrNull ?: invalid("Give id")
        val rule =
            store.rule(id) ?: throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "No automation $id", "Call automation.list for the ids.")
        if (rule.pending || !rule.enabled || rule.triggers.none { it.type == "mcp.call" }) {
            throw ToolException(
                ToolErrorCode.DENIED_BY_POLICY,
                "${rule.name} cannot be run by an AI app",
                "The owner must turn it on and give it the trigger \"AI app asks\".",
            )
        }
        val record = engine().run(rule, "AI app: ${session.principal}", mapOf("client" to session.principal))
        val text = "${rule.name}: ${record.outcome.name.lowercase()}\n" + record.lines.joinToString("\n")
        return if (record.outcome == RunOutcome.FAILED) {
            ToolResult.error(ToolErrorCode.INTERNAL_ERROR, text, "Read the log; fix the cause before running it again.")
        } else {
            ToolResult.success(text)
        }
    }
}
