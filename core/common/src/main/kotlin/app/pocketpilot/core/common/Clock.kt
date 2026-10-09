package app.pocketpilot.core.common

/** Wall-clock source, injectable so time-dependent logic is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
