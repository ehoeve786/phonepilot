package app.pocketpilot.core.automation

import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** One JSON setup for the store, proposals and tool arguments. */
object AutomationJson {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            classDiscriminator = "kind"
        }
}

/** Fails one action with a message the owner sees in the run log. */
class AutomationFailure(
    message: String,
) : RuntimeException(message)

/** Fills `{name}` placeholders from [lookup]; unknown names stay as written so mistakes show. */
object Placeholders {
    private val PATTERN = Regex("\\{([A-Za-z_][A-Za-z0-9_.]*)\\}")

    fun fill(
        text: String,
        lookup: (String) -> String?,
    ): String = PATTERN.replace(text) { match -> lookup(match.groupValues[1]) ?: match.value }
}

/** Sentences for the builder's cards and the proposal sheet. */
object Describe {
    fun trigger(spec: TriggerSpec): String {
        val def = AutomationCatalog.trigger(spec.type) ?: return "Unknown trigger ${spec.type}"
        return def.sentence(RuleValidator.withDefaults(def.fields, spec.params))
    }

    fun constraint(spec: ConstraintSpec): String {
        val def = AutomationCatalog.constraint(spec.type) ?: return "Unknown condition ${spec.type}"
        return def.sentence(RuleValidator.withDefaults(def.fields, spec.params))
    }

    fun action(step: ActionStep): String {
        val def = AutomationCatalog.action(step.type) ?: return "Unknown action ${step.type}"
        val base = def.sentence(RuleValidator.withDefaults(def.fields, step.params))
        return if (def.conditional && !step.constraints.isEmpty) "$base ${group(step.constraints)}" else base
    }

    fun group(group: ConstraintGroup): String {
        val parts =
            group.items.map { node ->
                when (node) {
                    is ConstraintNode.Check -> constraint(node.spec)
                    is ConstraintNode.Group -> "(${group(node.group)})"
                }
            }
        return when (group.mode) {
            GroupMode.ALL -> parts.joinToString(" and ")
            GroupMode.ANY -> parts.joinToString(" or ")
            GroupMode.NONE -> "not " + parts.joinToString(" or ")
            GroupMode.ONE -> "exactly one of " + parts.joinToString(", ")
        }
    }
}

/** When scheduled triggers fire next. */
object Schedule {
    /** The next time after [after] that [spec] fires, or null if it is not a schedule. */
    fun next(
        spec: TriggerSpec,
        after: Long,
        lastFiredAt: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val params = AutomationCatalog.trigger(spec.type)?.let { RuleValidator.withDefaults(it.fields, spec.params) } ?: spec.params
        return when (spec.type) {
            "time.at" -> {
                val time = parseTime(params.p("time")) ?: return null
                val days: Set<DayOfWeek> = parseDays(params.p("days"))
                val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), zone)
                (0..7)
                    .asSequence()
                    .map { offset ->
                        start
                            .toLocalDate()
                            .plusDays(offset.toLong())
                            .atTime(time)
                            .atZone(zone)
                    }.firstOrNull { it.dayOfWeek in days && it.toInstant().toEpochMilli() > after }
                    ?.toInstant()
                    ?.toEpochMilli()
            }

            "time.interval" -> {
                val minutes = params.p("minutes").toLongOrNull()?.coerceAtLeast(1) ?: return null
                val period = minutes * 60_000
                val base = lastFiredAt ?: after
                (base + period).coerceAtLeast(after + 1_000)
            }

            else -> {
                null
            }
        }
    }
}
