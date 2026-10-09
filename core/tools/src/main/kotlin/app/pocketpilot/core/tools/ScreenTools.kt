package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.ImageFormat
import app.pocketpilot.capability.api.screen.Role
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.core.imaging.CaptureRequest
import app.pocketpilot.core.imaging.ImagePipeline
import app.pocketpilot.core.imaging.Transform
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.ContentPart
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64

private fun readSpec(
    name: String,
    title: String,
    description: String,
    inputSchema: String,
    capability: CapabilityId = CapabilityId.READ_UI,
) = ToolSpec(
    name = name,
    title = title,
    description = description,
    inputSchema = schema(inputSchema),
    riskTier = RiskTier.READ,
    requiredScopes = setOf(Scope.SCREEN_READ),
    requiredCapabilities = setOf(capability),
    annotations = ToolAnnotations(readOnly = true),
    availability = Availability(Phase.P0),
)

/** `screen.snapshot`: the UI as a flat element list (spec section 6). */
class ScreenSnapshotTool(
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        readSpec(
            name = "screen.snapshot",
            title = "Screen snapshot",
            description =
                "Returns what is on screen as JSON: snapshotId, the foreground package, screen size and a flat list " +
                    "of elements with id, parent, role, text, desc, rid (resource ID), bounds [left,top,right,bottom] " +
                    "and state flags. Use element IDs with ui.* tools; they are only valid for this snapshot. " +
                    "Prefer this over screen.capture: it is cheaper and exact.",
            inputSchema =
                """{"type":"object","properties":{
                "maxNodes":{"type":"integer","description":"Element limit (default 300)"},
                "includeInvisible":{"type":"boolean","description":"Include elements not visible to the user"}
                },"additionalProperties":false}""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val max = call.arguments.int("maxNodes") ?: SnapshotOptions.DEFAULT_MAX_ELEMENTS
        if (max !in 1..MAX_NODES) invalid("maxNodes must be between 1 and $MAX_NODES")
        val options = SnapshotOptions(maxElements = max, includeInvisible = call.arguments.boolean("includeInvisible") ?: false)
        return ToolResult.success(reader.snapshot(options).toJson())
    }

    private companion object {
        const val MAX_NODES = 2_000
    }
}

/** `screen.find`: elements matching a selector in a fresh snapshot. */
class ScreenFindTool(
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        readSpec(
            name = "screen.find",
            title = "Find on screen",
            description =
                "Finds elements on the current screen. text and description match case-insensitively by substring, " +
                    "resourceId by suffix (\"title\" matches \"android:id/title\"), role exactly. Returns the snapshotId " +
                    "and the matching elements.",
            inputSchema = selectorSchema(),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val selector = Selector.from(call.arguments)
        val snapshot = reader.snapshot()
        val matches = selector.match(snapshot)
        if (matches.isEmpty()) {
            throw ToolException(
                ToolErrorCode.ELEMENT_NOT_FOUND,
                "Nothing on screen matches $selector",
                "Take a screen.snapshot to see what is there, or scroll with ui.swipe and try again",
            )
        }
        return ToolResult.success(compactJson.encodeToString(FindResult.serializer(), FindResult(snapshot.id, matches)))
    }
}

/** `screen.wait_for`: polls until a selector matches. */
class ScreenWaitForTool(
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        readSpec(
            name = "screen.wait_for",
            title = "Wait for element",
            description =
                "Waits until an element matching the selector is on screen (same matching as screen.find) and returns " +
                    "it, or fails with TIMEOUT. timeoutMs defaults to 10000, at most 30000.",
            inputSchema = selectorSchema(""","timeoutMs":{"type":"integer"}"""),
        )

    override val timeoutMs: Long = MAX_WAIT_MS + 5_000

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val selector = Selector.from(call.arguments)
        val wait = (call.arguments.long("timeoutMs") ?: DEFAULT_WAIT_MS).coerceIn(0, MAX_WAIT_MS)
        val found =
            withTimeoutOrNull(wait) {
                var result: FindResult? = null
                while (result == null) {
                    val snapshot = reader.snapshot()
                    val matches = selector.match(snapshot)
                    if (matches.isNotEmpty()) result = FindResult(snapshot.id, matches) else reader.awaitChange(POLL_MS)
                }
                result
            }
        found ?: throw ToolException(
            ToolErrorCode.TIMEOUT,
            "Nothing matched $selector within $wait ms",
            "Take a screen.snapshot to see what is on screen instead",
        )
        return ToolResult.success(compactJson.encodeToString(FindResult.serializer(), found))
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 30_000L
        const val POLL_MS = 500L
    }
}

/** `screen.wait_for_change`: returns the next settled screen. */
class ScreenWaitForChangeTool(
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        readSpec(
            name = "screen.wait_for_change",
            title = "Wait for screen change",
            description =
                "Waits for the screen to change and settle, then returns the new snapshot. If nothing changes within " +
                    "timeoutMs (default 5000, at most 30000) it says so and returns the current snapshot.",
            inputSchema = """{"type":"object","properties":{"timeoutMs":{"type":"integer"}},"additionalProperties":false}""",
        )

    override val timeoutMs: Long = 35_000

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val wait = (call.arguments.long("timeoutMs") ?: 5_000L).coerceIn(0, 30_000)
        val changed = reader.awaitChange(wait)
        val snapshot = reader.snapshot()
        val lead = if (changed) "The screen changed." else "No change within $wait ms."
        return ToolResult.success("$lead\n${snapshot.toJson()}")
    }
}

/** `screen.capture`: a screenshot, scaled and redacted, with the transform back to screen pixels. */
class ScreenCaptureTool(
    private val capturer: ScreenCapturer,
    private val pipeline: ImagePipeline,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        readSpec(
            name = "screen.capture",
            title = "Screenshot",
            description =
                "Takes a screenshot. The long edge is scaled to maxEdge (default 1280, 512 to native). Password fields " +
                    "are blacked out. The text part gives the image size and a transform: screenX = offsetX + imageX * " +
                    "scaleX, screenY = offsetY + imageY * scaleY; ui.tap takes screen pixels. Use screen.snapshot when " +
                    "you only need text and element IDs.",
            inputSchema =
                """{"type":"object","properties":{
                "maxEdge":{"type":"integer"},
                "format":{"type":"string","enum":["webp","jpeg","png"]},
                "quality":{"type":"integer","description":"30 to 100, default 80"},
                "region":{"type":"object","description":"Crop to this screen rectangle","properties":{
                  "left":{"type":"integer"},"top":{"type":"integer"},"right":{"type":"integer"},"bottom":{"type":"integer"}},
                  "required":["left","top","right","bottom"]}
                },"additionalProperties":false}""",
            capability = CapabilityId.CAPTURE_SCREEN,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val args = call.arguments
        val region =
            args["region"]?.jsonObject?.let { r ->
                Bounds(r.int("left") ?: 0, r.int("top") ?: 0, r.int("right") ?: 0, r.int("bottom") ?: 0)
                    .also { if (it.isEmpty) invalid("region must have right > left and bottom > top") }
            }
        val format = args.string("format")?.let { ImageFormat.valueOf(it.uppercase()) } ?: ImageFormat.WEBP
        val passwords = if (reader.available.value) passwordBounds(reader.snapshot()) else emptyList()
        val frame = capturer.capture()
        val image =
            pipeline.process(
                frame,
                CaptureRequest(
                    maxEdge = args.int("maxEdge") ?: CaptureRequest.DEFAULT_MAX_EDGE,
                    format = format,
                    quality = args.int("quality") ?: CaptureRequest.DEFAULT_QUALITY,
                    region = region,
                    redact = passwords,
                ),
            )
        val info = CaptureInfo(image.width, image.height, frame.width, frame.height, image.transform, "%016x".format(image.hash))
        return ToolResult(
            content =
                listOf(
                    ContentPart.Image(Base64.getEncoder().encodeToString(image.bytes), image.format.mimeType),
                    ContentPart.Text(compactJson.encodeToString(CaptureInfo.serializer(), info)),
                ),
        )
    }

    private fun passwordBounds(snapshot: Snapshot): List<Bounds> = snapshot.elements.filter(Element::password).map(Element::bounds)

    @Serializable
    private data class CaptureInfo(
        val imageWidth: Int,
        val imageHeight: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val transform: Transform,
        val hash: String,
    )
}

private fun selectorSchema(extraProperties: String = "") =
    """{"type":"object","properties":{
    "text":{"type":"string"},
    "description":{"type":"string"},
    "resourceId":{"type":"string"},
    "role":{"type":"string","enum":[${Role.entries.joinToString(",") { "\"${it.name.lowercase()}\"" }}]}
    $extraProperties},"additionalProperties":false}"""

@Serializable
internal data class FindResult(
    val snapshotId: String,
    val matches: List<Element>,
)

internal data class Selector(
    val text: String?,
    val description: String?,
    val resourceId: String?,
    val role: Role?,
) {
    fun match(snapshot: Snapshot): List<Element> =
        snapshot.elements.filter { e ->
            (text == null || e.text?.contains(text, ignoreCase = true) == true) &&
                (description == null || e.description?.contains(description, ignoreCase = true) == true) &&
                (resourceId == null || e.resourceId?.let { it == resourceId || it.endsWith("/$resourceId") } == true) &&
                (role == null || e.role == role)
        }

    override fun toString(): String =
        listOfNotNull(
            text?.let { "text \"$it\"" },
            description?.let { "description \"$it\"" },
            resourceId?.let { "resourceId $it" },
            role?.let { "role ${it.name.lowercase()}" },
        ).joinToString(", ")

    companion object {
        fun from(arguments: JsonObject): Selector {
            val selector =
                Selector(
                    text = arguments.string("text"),
                    description = arguments.string("description"),
                    resourceId = arguments.string("resourceId"),
                    role = arguments.string("role")?.let { Role.valueOf(it.uppercase()) },
                )
            if (selector.text == null && selector.description == null && selector.resourceId == null && selector.role == null) {
                invalid("Give at least one of text, description, resourceId or role")
            }
            return selector
        }
    }
}
