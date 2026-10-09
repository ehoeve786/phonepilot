package app.pocketpilot.core.model

/**
 * What a build distribution is allowed to ship. The play flavor removes Play-sensitive
 * capabilities through this profile rather than through code forks (spec section 3).
 */
data class PolicyProfile(
    val flavor: Flavor,
    /** `shell.exec`: left out of play, kept in oss and pro (spec section 16). */
    val shellExecAvailable: Boolean,
    /** Play uses targeted `<queries>` instead of QUERY_ALL_PACKAGES. */
    val queryAllPackages: Boolean,
    val proFeatures: Boolean,
) {
    companion object {
        val OSS = PolicyProfile(Flavor.OSS, shellExecAvailable = true, queryAllPackages = true, proFeatures = false)
        val PRO = PolicyProfile(Flavor.PRO, shellExecAvailable = true, queryAllPackages = true, proFeatures = true)
        val PLAY = PolicyProfile(Flavor.PLAY, shellExecAvailable = false, queryAllPackages = false, proFeatures = true)
    }
}
