package app.pocketpilot.core.model

import kotlinx.serialization.json.JsonObject

/** One piece of a tool result's content. */
sealed interface ContentPart {
    data class Text(
        val text: String,
    ) : ContentPart

    /** [base64] holds the encoded image bytes. */
    data class Image(
        val base64: String,
        val mimeType: String,
    ) : ContentPart
}

/** Stable error codes returned in a failed tool result (spec section 5, Errors). */
enum class ToolErrorCode {
    INVALID_ARGUMENTS,
    UNKNOWN_TOOL,
    STALE_ELEMENT,
    ELEMENT_NOT_FOUND,
    DEVICE_BUSY,
    DENIED_BY_POLICY,
    CONFIRMATION_DECLINED,
    CONFIRMATION_TIMEOUT,
    CAPABILITY_UNAVAILABLE,
    PROTECTED_SCREEN,
    TIMEOUT,
    RATE_LIMITED,
    INTERNAL_ERROR,
}

/**
 * The outcome of a [ToolCall]. Failures carry an [errorCode], a human message in [content] and a
 * [recoveryHint] a model can act on.
 */
data class ToolResult(
    val content: List<ContentPart>,
    val structured: JsonObject? = null,
    val isError: Boolean = false,
    val errorCode: ToolErrorCode? = null,
    val recoveryHint: String? = null,
) {
    companion object {
        fun success(
            text: String,
            structured: JsonObject? = null,
        ) = ToolResult(content = listOf(ContentPart.Text(text)), structured = structured)

        fun error(
            code: ToolErrorCode,
            message: String,
            recoveryHint: String? = null,
        ) = ToolResult(
            content = listOf(ContentPart.Text(message)),
            isError = true,
            errorCode = code,
            recoveryHint = recoveryHint,
        )
    }
}

/** Thrown by a tool implementation to fail a call with a specific code. */
class ToolException(
    val code: ToolErrorCode,
    message: String,
    val recoveryHint: String? = null,
) : RuntimeException(message)
