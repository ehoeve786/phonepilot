package app.pocketpilot.core.model

import kotlinx.serialization.Serializable

/** A session's ULID; for MCP clients it is also the `Mcp-Session-Id` header value. */
@Serializable
@JvmInline
value class SessionId(
    val value: String,
) {
    init {
        require(ULID_PATTERN.matches(value)) { "SessionId must be a ULID, got '$value'" }
    }

    override fun toString(): String = value

    companion object {
        /** 26 Crockford base32 characters; the first is at most 7 so the timestamp fits 48 bits. */
        val ULID_PATTERN = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")
    }
}
