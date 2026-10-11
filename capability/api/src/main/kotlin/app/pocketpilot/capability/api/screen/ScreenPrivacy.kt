package app.pocketpilot.capability.api.screen

/**
 * What snapshots and screenshots leave out (spec section 8): windows of apps on the deny list, such as
 * a banking app in split screen, and the text of those apps' notifications in the shade.
 */
interface ScreenPrivacy {
    fun hidesPackage(packageName: String): Boolean

    /** Titles and texts of notifications from hidden apps, removed wherever they show on screen. */
    suspend fun hiddenTexts(): Set<String>

    companion object {
        val NONE: ScreenPrivacy =
            object : ScreenPrivacy {
                override fun hidesPackage(packageName: String) = false

                override suspend fun hiddenTexts() = emptySet<String>()
            }
    }
}
