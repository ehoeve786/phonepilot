package app.pocketpilot.capability.shizuku

import android.os.ParcelFileDescriptor
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.AppAdminArgs
import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import app.pocketpilot.capability.api.apps.AppPermissions
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotBuilder
import app.pocketpilot.capability.api.screen.SnapshotCache
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.capability.api.screen.swipePoints
import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.capability.api.settings.SettingsController
import app.pocketpilot.capability.api.shell.ShellResult
import app.pocketpilot.capability.api.shell.ShellRunner
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Runs a privileged call off the main thread and turns its failures into tool errors. */
private suspend fun <T> ShizukuConnection.call(block: (IPocketPilotPrivileged) -> T): T =
    withContext(Dispatchers.IO) {
        val service = service()
        try {
            block(service)
        } catch (e: ToolException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, e.message ?: "Shizuku refused the arguments")
        } catch (e: Exception) {
            throw ToolException(
                ToolErrorCode.INTERNAL_ERROR,
                "Shizuku call failed: ${e.message}",
                "Retry; if it keeps failing, restart Shizuku",
            )
        }
    }

private fun ParcelFileDescriptor.readAllBytes(): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(this).use { it.readBytes() }

private fun ShizukuConnection.availability(scope: CoroutineScope): StateFlow<Boolean> =
    connectedFlow().stateIn(scope, SharingStarted.Eagerly, state.value == ShizukuState.CONNECTED)

/** READ_UI second backend: `uiautomator dump`, slower than Accessibility but needs no setting. */
class ShizukuScreenReader(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : ScreenReader {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    private val counter = AtomicLong()
    private val recent = SnapshotCache<Unit>()

    override suspend fun snapshot(options: SnapshotOptions): Snapshot {
        val xml = dump()
        val nodes = UiAutomatorXml.parse(xml)
        val root = nodes.firstOrNull()
        val size = root?.bounds?.let { ScreenSize(it.width, it.height) } ?: ScreenSize(0, 0)
        val snapshot = SnapshotBuilder.build("z${counter.incrementAndGet()}", root?.windowTitle, size, nodes, options).snapshot
        recent.put(snapshot, Unit)
        return snapshot
    }

    override fun bounds(target: Target.OnElement): Bounds = recent.element(target).bounds

    /** The element as the snapshot showed it, for callers that need more than its bounds. */
    fun element(target: Target.OnElement): Element = recent.element(target)

    override fun owns(snapshotId: String): Boolean = recent.contains(snapshotId)

    override suspend fun foreground(): ForegroundApp? {
        val component = shizuku.call { it.foregroundActivity() }.takeIf(String::isNotEmpty) ?: return null
        return ForegroundApp(component.substringBefore('/'), component.substringAfter('/'))
    }

    /** No event stream here: a short fixed pause stands in for "the screen went quiet". */
    override suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ): Boolean {
        delay(quietMs.coerceAtMost(timeoutMs))
        return true
    }

    /** Polls the UI dump until it differs from the one at the start. */
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        val before = dump().hashCode()
        return withTimeoutOrNull(timeoutMs) {
            while (dump().hashCode() == before) delay(POLL_MS)
            true
        } ?: false
    }

    private suspend fun dump(): String = shizuku.call { it.dumpUiHierarchy().readAllBytes() }.decodeToString()

    private companion object {
        const val POLL_MS = 500L
    }
}

/** INJECT_INPUT first backend: `input` as the shell user, which works on any window. */
class ShizukuInputController(
    private val shizuku: ShizukuConnection,
    private val reader: ShizukuScreenReader,
    scope: CoroutineScope,
) : InputController {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    override suspend fun tap(target: Target) {
        val (x, y) = point(target)
        shizuku.call { it.tap(x, y) }
    }

    override suspend fun longPress(
        target: Target,
        durationMs: Long,
    ) {
        val (x, y) = point(target)
        shizuku.call { it.swipe(x, y, x, y, durationMs) }
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) {
        shizuku.call { it.swipe(fromX, fromY, toX, toY, durationMs) }
    }

    override suspend fun swipeOn(
        target: Target.OnElement,
        direction: SwipeDirection,
        durationMs: Long,
    ) {
        val (fromX, fromY, toX, toY) = swipePoints(reader.bounds(target), direction).toList()
        swipe(fromX, fromY, toX, toY, durationMs)
    }

    /**
     * Shizuku types by key events, so it appends to the field. With an element it taps the field,
     * moves to the end and deletes what the snapshot showed there first, so the result matches
     * Accessibility's replace.
     */
    override suspend fun typeText(
        text: String,
        target: Target.OnElement?,
        submit: Boolean,
    ) {
        if (text.any { it.code !in 0x20..0x7e }) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "Shizuku can only type plain ASCII text",
                "Ask the phone's owner to turn on PocketPilot in Accessibility settings to type other characters",
            )
        }
        if (target != null) {
            val existing = reader.element(target).text?.length ?: 0
            tap(target)
            delay(FOCUS_SETTLE_MS)
            if (existing > 0) {
                val deletes = IntArray(existing.coerceAtMost(MAX_DELETE)) { KEYCODE_DEL }
                shizuku.call { it.keyEvents(intArrayOf(KEYCODE_MOVE_END) + deletes) }
            }
        }
        if (text.isNotEmpty()) shizuku.call { it.text(text) }
        if (submit) shizuku.call { it.keyEvents(intArrayOf(KEYCODE_ENTER)) }
    }

    override suspend fun pressKey(key: Key) {
        val code =
            when (key) {
                Key.BACK -> KEYCODE_BACK
                Key.HOME -> KEYCODE_HOME
                Key.ENTER -> KEYCODE_ENTER
                Key.DEL -> KEYCODE_DEL
                Key.TAB -> KEYCODE_TAB
            }
        shizuku.call { it.keyEvents(intArrayOf(code)) }
    }

    override suspend fun globalAction(action: GlobalAction) {
        when (action) {
            GlobalAction.BACK -> pressKey(Key.BACK)
            GlobalAction.HOME -> pressKey(Key.HOME)
            GlobalAction.RECENTS -> shizuku.call { it.keyEvents(intArrayOf(KEYCODE_APP_SWITCH)) }
            GlobalAction.NOTIFICATIONS -> shizuku.call { it.expandStatusBar(false) }
            GlobalAction.QUICK_SETTINGS -> shizuku.call { it.expandStatusBar(true) }
            GlobalAction.LOCK_SCREEN -> shizuku.call { it.keyEvents(intArrayOf(KEYCODE_SLEEP)) }
        }
    }

    private fun point(target: Target): Pair<Int, Int> =
        when (target) {
            is Target.AtPoint -> target.x to target.y
            is Target.OnElement -> reader.bounds(target).let { it.centerX to it.centerY }
        }

    private companion object {
        const val FOCUS_SETTLE_MS = 400L
        const val MAX_DELETE = 200
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_TAB = 61
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_MOVE_END = 123
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_SLEEP = 223
    }
}

/** CAPTURE_SCREEN first backend: raw `screencap`. */
class ShizukuScreenCapturer(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : ScreenCapturer {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    override suspend fun capture(): Frame {
        val bytes = shizuku.call { it.captureScreen().readAllBytes() }
        return withContext(Dispatchers.Default) { RawScreencap.decode(bytes) }
    }
}

/** LAUNCH_APPS second backend: `monkey` and `am start` as the shell user, allowed from the background. */
class ShizukuAppController(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : AppController {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    /** Listing stays with the package manager. */
    override suspend fun list(includeSystem: Boolean): List<AppInfo> = emptyList()

    override suspend fun launch(packageName: String) {
        shizuku.call { it.launchPackage(packageName) }
    }

    override suspend fun openUrl(url: String) {
        shizuku.call { it.openUrl(url) }
    }
}

/** WRITE_SETTINGS: allowlisted settings through fixed shell commands (`cmd uimode`, `settings put`, `svc`). */
class ShizukuSettingsController(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : SettingsController {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    override suspend fun get(key: SettingKey): String = shizuku.call { it.readSetting(key.wire) }

    override suspend fun set(
        key: SettingKey,
        value: String,
    ) {
        shizuku.call { it.writeSetting(key.wire, value) }
    }
}

/** App management through `am`, `pm` and `appops`; the privileged service checks every argument again. */
class ShizukuAppAdmin(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : AppAdmin {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    override suspend fun forceStop(packageName: String) {
        mutable(packageName)
        shizuku.call { it.forceStop(packageName) }
    }

    override suspend fun permissions(packageName: String): AppPermissions {
        AppAdminArgs.checkPackage(packageName)
        val dump = shizuku.call { it.dumpPackage(packageName) }
        if (!dump.contains("Package [$packageName]")) {
            throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "$packageName is not installed", "Find the package with app.list")
        }
        val ops = shizuku.call { it.appOps(packageName) }
        return AppPermissions(AppAdminParsers.runtimePermissions(dump), AppAdminParsers.appOps(ops))
    }

    override suspend fun setPermission(
        packageName: String,
        permission: String,
        granted: Boolean,
    ) {
        mutable(packageName)
        AppAdminArgs.checkPermission(permission)
        shizuku.call { it.setPermission(packageName, permission, granted) }
    }

    override suspend fun setAppOp(
        packageName: String,
        op: String,
        mode: String,
    ) {
        mutable(packageName)
        AppAdminArgs.checkOp(op)
        AppAdminArgs.checkMode(mode)
        shizuku.call { it.setAppOp(packageName, op, mode) }
    }

    override suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ) {
        mutable(packageName)
        AppAdminArgs.checkNotCritical(packageName)
        shizuku.call { it.setPackageEnabled(packageName, enabled) }
    }

    override suspend fun clearData(packageName: String) {
        mutable(packageName)
        AppAdminArgs.checkNotCritical(packageName)
        shizuku.call { it.clearPackageData(packageName) }
    }

    private fun mutable(packageName: String) {
        try {
            AppAdminArgs.checkPackage(packageName)
            AppAdminArgs.checkNotProtected(packageName)
        } catch (e: IllegalArgumentException) {
            throw ToolException(ToolErrorCode.DENIED_BY_POLICY, e.message ?: "Not allowed")
        }
    }
}

/** RUN_SHELL: `sh -c` as the shell user, for `shell.exec`. */
class ShizukuShellRunner(
    private val shizuku: ShizukuConnection,
    scope: CoroutineScope,
) : ShellRunner {
    override val backendId: String = BackendIds.SHIZUKU
    override val available: StateFlow<Boolean> = shizuku.availability(scope)

    override suspend fun run(
        command: String,
        timeoutMs: Long,
    ): ShellResult {
        val parts = shizuku.call { it.runShell(command, timeoutMs) }.split('\u0000', limit = 3)
        val exit = parts[0].toIntOrNull() ?: -1
        return ShellResult(
            exitCode = exit,
            stdout = parts.getOrElse(1) { "" },
            stderr = parts.getOrElse(2) { "" },
            timedOut = exit == -1,
        )
    }
}
