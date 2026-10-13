package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.IntentArgs
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Role
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/** One Google Maps search result, read from the result list on screen. */
internal data class MapsPlace(
    val name: String,
    val distanceMeters: Double,
    val distanceText: String,
    val address: String?,
    val sponsored: Boolean,
    /** Every text on the result card except its buttons, for matching the search. */
    val texts: List<String>,
) {
    val key: String get() = "$name|$address"
}

/** Reads Google Maps' search result cards and picks the closest one that matches the search. */
internal object MapsResults {
    private val DISTANCE = Regex("""^(\d+(?:[.,]\d+)?)\s?(m|km|mi|ft)$""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""^[\d.,]+$""")
    private const val SPONSORED = "Sponsored"

    /** The result cards on screen: each has a distance line inside a clickable card. */
    fun parse(snapshot: Snapshot): List<MapsPlace> {
        val byId = snapshot.elements.associateBy { it.id }

        fun Element.ancestors() = generateSequence(parent?.let(byId::get)) { it.parent?.let(byId::get) }

        val cards = mutableMapOf<String, String>()
        for (element in snapshot.elements) {
            val text = element.label() ?: continue
            if (text.parts().none { DISTANCE.matches(it) }) continue
            val card = element.ancestors().firstOrNull { it.clickable } ?: continue
            cards.putIfAbsent(card.id, text)
        }
        return cards.mapNotNull { (cardId, distanceLine) ->
            val inside =
                snapshot.elements.filter { e ->
                    val chain = e.ancestors().toList()
                    chain.any { it.id == cardId } && chain.takeWhile { it.id != cardId }.none { it.clickable } && !e.clickable
                }
            val texts = inside.mapNotNull { it.label() }.distinct()
            val name =
                texts.firstOrNull { !it.startsWith(SPONSORED) && !NUMBER.matches(it) && it != distanceLine } ?: return@mapNotNull null
            // "850.0 m" with the address on the next line, or "77.5 km · Egmont, BC" on one line.
            val parts = distanceLine.parts()
            val at = parts.indexOfFirst { DISTANCE.matches(it) }
            val distanceText = parts[at]
            val after = parts.getOrNull(at + 1) ?: texts.indexOf(distanceLine).takeIf { it >= 0 }?.let { texts.getOrNull(it + 1) }
            MapsPlace(
                name = name,
                distanceMeters = meters(distanceText) ?: return@mapNotNull null,
                distanceText = distanceText,
                address = after?.takeIf { it != name },
                sponsored = texts.any { it.startsWith(SPONSORED) },
                texts = texts,
            )
        }
    }

    /**
     * The closest place whose card mentions every word of [query] (a category such as "Gas station",
     * or the name). When no card matches, the closest of all. Ads lose ties.
     */
    fun pick(
        places: Collection<MapsPlace>,
        query: String,
    ): MapsPlace? {
        val words = query.lowercase().split(Regex("""\W+""")).filter { it.length > 1 }
        val matching =
            places.filter { place ->
                val card = place.texts.joinToString(" ").lowercase()
                words.all { word -> card.contains(word) || card.contains(word.removeSuffix("s")) }
            }
        return (matching.ifEmpty { places }).minWithOrNull(compareBy<MapsPlace> { it.distanceMeters }.thenBy { it.sponsored })
    }

    fun meters(text: String): Double? {
        val match = DISTANCE.matchEntire(text.trim()) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "km" -> value * 1000
            "mi" -> value * 1609.344
            "ft" -> value * 0.3048
            else -> value
        }
    }

    private fun String.parts(): List<String> = split('·', '•').map { it.trim() }.filter { it.isNotEmpty() }

    private fun Element.label(): String? = (text ?: description)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * `app.directions`: a route to the nearest place of a kind. Searches Google Maps, reads the distance
 * on each result and opens the route preview to the closest match, so the model does not have to
 * scroll and tap through Maps' list, where ads and far-away places come first.
 */
class AppDirectionsTool(
    private val admin: AppAdmin,
    private val reader: ScreenReader,
    private val input: InputController,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "app.directions",
            title = "Directions to nearest place",
            description =
                "Shows a route in Google Maps to the nearest place matching query, for example \"gas station\", " +
                    "\"pharmacy\" or \"Tim Hortons\". It searches near the phone, reads each result's distance, skips " +
                    "results that are a different kind of place, and opens the route preview. Navigation does not " +
                    "start; tap Start for that. Returns the place picked and the other matches.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{
                    "query":{"type":"string","description":"The kind of place or a name, for example gas station"},
                    "mode":{"type":"string","enum":["driving","walking","bicycling","transit"],"description":"Default driving"},
                    $RETURN_SNAPSHOT_PROP},"required":["query"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.INTERACT,
            requiredScopes = setOf(Scope.APPS_CONTROL, Scope.SCREEN_READ),
            requiredCapabilities = setOf(CapabilityId.RUN_SHELL, CapabilityId.READ_UI, CapabilityId.INJECT_INPUT),
            annotations = ToolAnnotations(readOnly = false, destructive = false, idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun targetPackage(call: ToolCall): String = MAPS

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val query =
            call.arguments
                .string("query")
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: invalid("Give query, for example gas station")
        val mode = call.arguments.string("mode") ?: "driving"
        if (mode !in MODES) invalid("mode must be one of ${MODES.joinToString()}")

        open("geo:0,0?q=${encode(query)}")
        val places = linkedMapOf<String, MapsPlace>()
        repeat(PAGES) { page ->
            val snapshot = reader.snapshot(SnapshotOptions(maxElements = MAX_ELEMENTS))
            val found = MapsResults.parse(snapshot).filter { places.putIfAbsent(it.key, it) == null }
            if (page == PAGES - 1 || (page > 0 && found.isEmpty())) return@repeat
            val list = snapshot.resultList() ?: return@repeat
            input.swipeOn(Target.OnElement(list.id, snapshot.id), SwipeDirection.UP, SWIPE_MS)
            reader.awaitIdle()
        }
        val best =
            MapsResults.pick(places.values, query) ?: throw ToolException(
                ToolErrorCode.ELEMENT_NOT_FOUND,
                "Google Maps showed no list of places for \"$query\".",
                "Read the screen with screen.snapshot: Maps may be showing a prompt or a single place.",
            )

        val destination = listOfNotNull(best.name, best.address).joinToString(", ")
        open("https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}&travelmode=$mode")
        val others =
            places.values
                .filter { it != best }
                .sortedBy { it.distanceMeters }
                .take(OTHERS)
                .joinToString("; ") { "${it.name} ${it.distanceText}${if (it.sponsored) " (ad)" else ""}" }
        val summary =
            buildString {
                append("Showing the $mode route to ${best.name}")
                best.address?.let { append(", ").append(it) }
                append(" (").append(best.distanceText).append(" away).")
                if (others.isNotEmpty()) append("\nOther results: ").append(others).append('.')
            }
        return afterAction(reader, summary, call.arguments.boolean("returnSnapshot") ?: false)
    }

    private suspend fun open(uri: String) {
        val args = IntentArgs.build(null, MAPS, VIEW, uri, null, emptyList(), emptyList())
        admin.startIntent(args, broadcast = false)
        withTimeoutOrNull(OPEN_WAIT_MS) {
            while (reader.foreground()?.packageName != MAPS) reader.awaitChange(OPEN_WAIT_MS)
        }
        reader.awaitIdle(quietMs = SETTLE_MS, timeoutMs = OPEN_WAIT_MS)
    }

    /** Maps' scrolling result list; the largest scrollable list when Maps renames it. */
    private fun Snapshot.resultList(): Element? =
        elements.firstOrNull { it.resourceId?.substringAfter(":id/") == "search_list_layout" && it.scrollable }
            ?: elements.filter { it.scrollable && it.role == Role.LIST }.maxByOrNull { it.bounds.height * it.bounds.width.toLong() }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val MAPS = "com.google.android.apps.maps"
        const val VIEW = "android.intent.action.VIEW"
        val MODES = listOf("driving", "walking", "bicycling", "transit")
        const val PAGES = 3
        const val OTHERS = 4
        const val MAX_ELEMENTS = 600
        const val SWIPE_MS = 400L
        const val SETTLE_MS = 800L
        const val OPEN_WAIT_MS = 6_000L
        const val RETURN_SNAPSHOT_PROP =
            """"returnSnapshot":{"type":"boolean","description":"Return the screen after the route shows (default false)"}"""
    }
}
