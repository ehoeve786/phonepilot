package app.pocketpilot.core.model

import kotlinx.serialization.Serializable

/** How dangerous a tool is; drives default confirmation (spec section 8). Ordered least to most. */
@Serializable
enum class RiskTier {
    /** Runs silently. */
    READ,

    /** Runs with the visible "PocketPilot is controlling this phone" overlay. */
    INTERACT,

    /** Confirmed on first use per session. */
    SENSITIVE,

    /** Confirmed on every call. */
    DESTRUCTIVE,
}
