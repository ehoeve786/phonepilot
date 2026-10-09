package app.pocketpilot.core.model

import kotlinx.serialization.Serializable

/** Roadmap phase tags used to gate features (spec conventions). */
@Serializable
enum class Phase {
    /** Foundations. */
    P0,

    /** Public beta. */
    P1,

    /** Plugins and Pro. */
    P2,

    /** Platform. */
    P3,
}

/** The three build distributions (spec section 3). */
@Serializable
enum class Flavor {
    OSS,
    PRO,
    PLAY,
}
