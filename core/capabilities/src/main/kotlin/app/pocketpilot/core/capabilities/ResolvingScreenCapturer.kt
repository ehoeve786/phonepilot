package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ScreenCapturer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** CAPTURE_SCREEN across backends in spec priority order (Shizuku screencap, then Accessibility). */
class ResolvingScreenCapturer(
    private val backends: List<ScreenCapturer>,
    scope: CoroutineScope,
) : ScreenCapturer {
    override val backendId: String = "resolver"
    override val available: StateFlow<Boolean> = anyAvailable(scope, backends)

    override suspend fun capture(): Frame = backends.firstAvailable().capture()
}
