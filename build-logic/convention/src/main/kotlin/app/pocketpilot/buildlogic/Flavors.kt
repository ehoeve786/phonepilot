package app.pocketpilot.buildlogic

import com.android.build.api.dsl.ApplicationExtension

/**
 * The three distributions from spec section 3. Only the app module has flavors; library modules
 * stay flavorless and Pro-only code is wired in with `proImplementation` / `playImplementation`.
 */
enum class PocketPilotFlavor(
    val applicationIdSuffix: String?,
) {
    /** All open modules, no pro/, no Play Billing. GitHub releases, F-Droid later. */
    OSS(".oss"),

    /** oss plus pro/ modules and licence-key activation. Personal sideload builds. */
    PRO(".pro"),

    /** pro minus anything Play policy forbids, plus Play Billing. Uses the bare application ID. */
    PLAY(null),
    ;

    val flavorName: String = name.lowercase()

    companion object {
        const val DIMENSION = "distribution"
    }
}

internal fun ApplicationExtension.configureFlavors() {
    flavorDimensions += PocketPilotFlavor.DIMENSION
    productFlavors {
        PocketPilotFlavor.entries.forEach { flavor ->
            register(flavor.flavorName) {
                dimension = PocketPilotFlavor.DIMENSION
                flavor.applicationIdSuffix?.let { applicationIdSuffix = it }
            }
        }
    }
}
