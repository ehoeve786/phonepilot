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

/** `automation.catalog`: every trigger, condition and action type with its settings. */
class AutomationCatalogTool : ToolHandler {
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
                    AutomationCatalog.triggers.forEach { appendLine(line(it.type, it.title, it.fields, it.variables)) }
                }
                if (kind == null || kind == "conditions") {
                    appendLine("conditions:")
                    AutomationCatalog.constraints.forEach { appendLine(line(it.type, it.title, it.fields)) }
                }
                if (kind == null || kind == "actions") {
                    appendLine("actions (tier in brackets; flow.if/flow.while/flow.wait_until use the step's conditions):")
                    AutomationCatalog.actions.forEach { appendLine(line(it.type, "${it.title} [${it.tier.name.lowercase()}]", it.fields)) }
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

/** `automation.list`: the owner's rules and proposals. */
class AutomationListTool(
    private val store: RuleStore,
) : ToolHandler {
    override val spec =
        automationSpec(
            "automation.list",
            "List automations",
            "Lists the phone's automations and pending proposals: id, name, on or off, whether it waits for the owner, " +
                "its triggers and actions in words, whether an AI app may run it (automation.run), and its last run.",
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
                        },
                    )
                }
            }
        return ToolResult.success(buildJsonObject { put("automations", rules) }.toString())
    }
}

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
                "and other variables are filled in when it runs. Returns the proposal id; read the outcome with automation.list.",
            """{"type":"object","properties":{"name":{"type":"string"},"triggers":{"type":"array"},"conditions":{"type":"array"},
            "conditionMode":{"type":"string","enum":["all","any","none","one"]},"actions":{"type":"array"},
            "runAs":{"type":"string","enum":["direct","agent"]},"agentPrompt":{"type":"string"}},
            "required":["name","triggers"],"additionalProperties":false}""",
            RiskTier.READ,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val proposed =
            runCatching { AutomationJson.json.decodeFromJsonElement(ProposedRule.serializer(), call.arguments) }
                .getOrElse { invalid("The proposal does not have the expected shape: ${it.message?.lineSequence()?.firstOrNull()}") }
        val rule =
            Rule(
                id = store.newId(),
                name = proposed.name.trim(),
                enabled = false,
                triggers = proposed.triggers,
                constraints = conditions(proposed.conditions, proposed.conditionMode),
                actions = proposed.actions.map { it.toStep() },
                runAs = if (proposed.runAs.equals("agent", ignoreCase = true)) RunMode.AGENT else RunMode.DIRECT,
                agentPrompt = proposed.agentPrompt,
                proposedBy = session.principal,
            )
        val problems = RuleValidator.problems(rule, sideload)
        if (problems.isNotEmpty()) invalid("The proposal has problems: ${problems.joinToString("; ")}")
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
