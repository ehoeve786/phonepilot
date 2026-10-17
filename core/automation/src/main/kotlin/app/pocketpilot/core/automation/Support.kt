package app.pocketpilot.core.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** True when [text] has a placeholder, so its value can change from one run to the next. */
    fun contains(text: String): Boolean = PATTERN.containsMatchIn(text)
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

/** A circle on the map, for location triggers and conditions. */
data class Place(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    /** The same text for the same circle, so the app's alerts can name the rules they are for. */
    val key: String get() = "%.5f,%.5f,%d".format(Locale.ROOT, latitude, longitude, radiusMeters.toInt())

    fun contains(
        latitude: Double,
        longitude: Double,
    ): Boolean = distanceMeters(this.latitude, this.longitude, latitude, longitude) <= radiusMeters

    companion object {
        const val DEFAULT_RADIUS = 150.0
        const val MIN_RADIUS = 50.0

        /** The place in a trigger's or condition's settings: "at" as "latitude, longitude" and "radius" in meters. */
        fun from(params: Map<String, String>): Place? {
            val (lat, lon) = coordinates(params.p("at")) ?: return null
            val radius = params.p("radius").toDoubleOrNull() ?: DEFAULT_RADIUS
            return Place(lat, lon, radius.coerceAtLeast(MIN_RADIUS))
        }

        /** "49.2827, -123.1207", as Google Maps copies it. */
        fun coordinates(text: String): Pair<Double, Double>? {
            val parts = text.split(',').map { it.trim().toDoubleOrNull() }
            val lat = parts.getOrNull(0) ?: return null
            val lon = parts.getOrNull(1) ?: return null
            if (parts.size != 2 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }

        /** Great-circle distance in meters. */
        fun distanceMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a =
                sin(dLat / 2).let { it * it } +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).let { it * it }
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/** Picks one value out of JSON text with a path such as `data.items[0].name` or `data.items.0.name`. */
object JsonPick {
    /** The value as text: a string or number as is, an object or array as JSON; null when the path leads nowhere. */
    fun pick(
        text: String,
        path: String,
    ): String? {
        var element: JsonElement =
            runCatching { AutomationJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
        for (part in parts(path)) {
            element =
                when (element) {
                    is JsonObject -> element[part]
                    is JsonArray -> part.toIntOrNull()?.let { element.getOrNull(if (it < 0) element.size + it else it) }
                    else -> null
                } ?: return null
        }
        return when (val value = element) {
            is JsonPrimitive -> if (value is JsonNull) "" else value.content
            else -> value.toString()
        }
    }

    private fun parts(path: String): List<String> =
        path
            .trim()
            .removePrefix("$")
            .replace("[", ".")
            .replace("]", "")
            .split('.')
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter(String::isNotEmpty)
}
