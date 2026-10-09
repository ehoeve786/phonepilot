package app.pocketpilot.server.mcp

import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Maps a PocketPilot [ToolSpec] onto the MCP tool shape returned by tools/list. */
internal fun ToolSpec.toMcpTool(): Tool =
    Tool(
        name = name,
        title = title,
        description = description,
        inputSchema = inputSchema.toToolSchema(),
        outputSchema = outputSchema?.toToolSchema(),
        annotations =
            ToolAnnotations(
                title = title,
                readOnlyHint = annotations.readOnly,
                destructiveHint = annotations.destructive,
                idempotentHint = annotations.idempotent,
                openWorldHint = annotations.openWorld,
            ),
    )

private fun JsonObject.toToolSchema(): ToolSchema =
    ToolSchema(
        properties = this["properties"]?.jsonObject,
        required = this["required"]?.jsonArray?.map { it.jsonPrimitive.content },
    )

/**
 * Maps a [ToolResult] onto MCP. Failures put the stable error code and recovery hint in structured
 * content, and repeat them under the human message, since many clients only show models the text
 * (spec section 5, Errors).
 */
internal fun ToolResult.toMcpResult(): CallToolResult =
    CallToolResult(
        content =
            content.mapIndexed { index, part ->
                when (part) {
                    is ContentPart.Text -> TextContent(if (isError && index == 0) part.text + errorFooter() else part.text)
                    is ContentPart.Image -> ImageContent(data = part.base64, mimeType = part.mimeType)
                }
            },
        isError = isError,
        structuredContent =
            if (isError) {
                buildJsonObject {
                    put("code", errorCode?.name)
                    recoveryHint?.let { put("recoveryHint", it) }
                }
            } else {
                structured
            },
    )

private fun ToolResult.errorFooter(): String =
    buildString {
        errorCode?.let { append("\nError code: ").append(it.name) }
        recoveryHint?.let { append("\nWhat to do: ").append(it) }
    }
