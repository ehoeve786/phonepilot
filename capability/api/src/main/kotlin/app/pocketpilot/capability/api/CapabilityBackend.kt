package app.pocketpilot.capability.api

import kotlinx.coroutines.flow.StateFlow

/**
 * One backend for a capability (spec section 6, Backend priority): the Accessibility service,
 * Shizuku, the package manager. The resolver picks the first available backend in priority order.
 */
interface CapabilityBackend {
    /** Stable name shown in Doctor and audit, for example `accessibility` or `shizuku`. */
    val backendId: String

    /** True while this backend can serve calls. */
    val available: StateFlow<Boolean>
}

object BackendIds {
    const val ACCESSIBILITY = "accessibility"
    const val SHIZUKU = "shizuku"
    const val PACKAGE_MANAGER = "package_manager"
}
