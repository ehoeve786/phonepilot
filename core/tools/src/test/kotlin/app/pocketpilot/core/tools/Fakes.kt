package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.Role
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeReader(
    var snapshot: Snapshot = settingsSnapshot(),
) : ScreenReader {
    override val available = MutableStateFlow(true)
    var foregroundApp: ForegroundApp? = ForegroundApp("com.android.settings", "SettingsActivity")
    var snapshots = 0

    override suspend fun snapshot(options: SnapshotOptions): Snapshot {
        snapshots++
        return snapshot
    }

    override suspend fun foreground() = foregroundApp

    override suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ) = true

    override suspend fun awaitChange(timeoutMs: Long) = true
}

internal class FakeInput : InputController {
    val calls = mutableListOf<String>()

    override suspend fun tap(target: Target) {
        calls += "tap $target"
    }

    override suspend fun longPress(
        target: Target,
        durationMs: Long,
    ) {
        calls += "longPress $target $durationMs"
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) {
        calls += "swipe $fromX,$fromY $toX,$toY $durationMs"
    }

    override suspend fun swipeOn(
        target: Target.OnElement,
        direction: SwipeDirection,
        durationMs: Long,
    ) {
        calls += "swipeOn ${target.elementId} $direction"
    }

    override suspend fun typeText(
        text: String,
        target: Target.OnElement?,
        submit: Boolean,
    ) {
        calls += "type $text ${target?.elementId} $submit"
    }

    override suspend fun pressKey(key: Key) {
        calls += "key $key"
    }

    override suspend fun globalAction(action: GlobalAction) {
        calls += "global $action"
    }
}

internal class FakeApps(
    private val reader: FakeReader,
) : AppController {
    val launched = mutableListOf<String>()
    val apps =
        listOf(
            AppInfo("com.android.settings", "Settings", "16", system = true),
            AppInfo("com.android.chrome", "Chrome", "140"),
            AppInfo("com.example.notes", "Notes"),
            AppInfo("com.example.notes.pro", "Notes Pro"),
        )

    override suspend fun list(includeSystem: Boolean) = apps.filter { includeSystem || !it.system }

    override suspend fun launch(packageName: String) {
        launched += packageName
        reader.foregroundApp = ForegroundApp(packageName, null)
    }

    override suspend fun openUrl(url: String) {
        launched += url
    }
}

internal fun settingsSnapshot() =
    Snapshot(
        id = "s1",
        packageName = "com.android.settings",
        screen = ScreenSize(1080, 2400),
        elements =
            listOf(
                Element("e1", role = Role.WINDOW, text = "Settings", bounds = Bounds(0, 0, 1080, 2400)),
                Element("e2", "e1", Role.LISTITEM, bounds = Bounds(0, 200, 1080, 350), clickable = true),
                Element("e3", "e2", Role.TEXT, text = "Display", resourceId = "android:id/title", bounds = Bounds(40, 210, 400, 260)),
                Element("e4", "e1", Role.TEXTFIELD, bounds = Bounds(0, 400, 1080, 500), editable = true, password = true),
            ),
    )
