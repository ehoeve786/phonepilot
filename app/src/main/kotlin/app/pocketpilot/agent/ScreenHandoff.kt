package app.pocketpilot.agent

import app.pocketpilot.capability.api.screen.ScreenReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Moves PocketPilot out of the agent's way. The policy never lets the agent read or act on
 * PocketPilot's own screen, so when a chat turn needs the screen while the owner is in the app, the
 * activity sends its task to the back.
 */
class ScreenHandoff(
    private val reader: ScreenReader,
    private val ownPackage: String,
) {
    private val mutableRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The activity collects these and calls `moveTaskToBack`. */
    val requests: SharedFlow<Unit> = mutableRequests.asSharedFlow()

    suspend fun inFront(): Boolean = runCatching { reader.foreground()?.packageName }.getOrNull() == ownPackage

    /** Asks the activity to step back and waits until it has, or gives up after a few seconds. */
    suspend fun leave() {
        if (!inFront()) return
        mutableRequests.tryEmit(Unit)
        withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
            while (inFront()) delay(LEAVE_POLL_MS)
        }
    }

    private companion object {
        const val LEAVE_TIMEOUT_MS = 3_000L
        const val LEAVE_POLL_MS = 150L
    }
}
