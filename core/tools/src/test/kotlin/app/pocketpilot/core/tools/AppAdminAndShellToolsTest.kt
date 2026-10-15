package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.ActivityEntry
import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.AppPermissions
import app.pocketpilot.capability.api.shell.ShellResult
import app.pocketpilot.capability.api.shell.ShellRunner
import app.pocketpilot.capability.api.shell.SystemReader
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.ConfirmationAnswer
import app.pocketpilot.core.policy.ConfirmationRequest
import app.pocketpilot.core.policy.PolicyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppAdminAndShellToolsTest {
    private val log = mutableListOf<String>()

    private val admin =
        object : AppAdmin {
            override val backendId = "fake"
            override val available = MutableStateFlow(true)

            override suspend fun forceStop(packageName: String) {
                log += "stop $packageName"
            }

            override suspend fun permissions(packageName: String) =
                AppPermissions(mapOf("android.permission.CAMERA" to false), mapOf("RUN_IN_BACKGROUND" to "ignore"))

            override suspend fun setPermission(
                packageName: String,
                permission: String,
                granted: Boolean,
            ) {
                log += "perm $packageName $permission $granted"
            }

            override suspend fun setAppOp(
                packageName: String,
                op: String,
                mode: String,
            ) {
                log += "op $packageName $op $mode"
            }

            override suspend fun setEnabled(
                packageName: String,
                enabled: Boolean,
            ) {
                log += "enabled $packageName $enabled"
            }

            override suspend fun clearData(packageName: String) {
                log += "clear $packageName"
            }

            override suspend fun activities(packageName: String) =
                listOf(
                    ActivityEntry(
                        "$packageName/.Stopwatch",
                        listOf("com.example.action.STOPWATCH"),
                        listOf("android.intent.category.DEFAULT"),
                    ),
                    ActivityEntry("$packageName/.Main", listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER")),
                )

            override suspend fun startIntent(
                args: List<String>,
                broadcast: Boolean,
            ): String {
                log += "${if (broadcast) "broadcast" else "start"} ${args.joinToString(" ")}"
                return "Broadcast completed: result=0"
            }

            override suspend fun setBatteryOptimized(
                packageName: String,
                optimized: Boolean,
            ) {
                log += "optimized $packageName $optimized"
            }

            override suspend fun setStandbyBucket(
                packageName: String,
                bucket: String,
            ) {
                log += "bucket $packageName $bucket"
            }
        }

    private var shellOn = true
    private val shell =
        object : ShellRunner {
            override val backendId = "fake"
            override val available = MutableStateFlow(true)

            override suspend fun run(
                command: String,
                timeoutMs: Long,
            ): ShellResult {
                log += "sh $command"
                return ShellResult(0, "ok\n", "")
            }
        }

    private val asked = mutableListOf<ConfirmationRequest>()
    private val policy =
        PolicyEngine(
            appPolicy = AppPolicy(),
            foreground = { null },
            confirmer = { request ->
                asked += request
                ConfirmationAnswer.APPROVED
            },
        )

    private fun dispatcher(vararg tools: ToolHandler) =
        CallDispatcher(ToolRegistry(tools.toSet(), PolicyProfile.OSS), InMemoryAuditSink(), policy)

    private val session = SessionFactory().clientSession("Claude", Scope.KNOWN)

    private fun args(vararg pairs: Pair<String, Any>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

    @Test
    fun `lists permissions and changes them, confirming with the exact change`() =
        runTest {
            val d = dispatcher(AppPermissionsTool(admin), AppSetPermissionTool(admin), AppSetAppOpTool(admin))
            val list = d.dispatch(session, "app.permissions", args("package" to "com.example.app"))
            assertTrue(list.content.toString().contains("RUN_IN_BACKGROUND"))
            d.dispatch(
                session,
                "app.set_permission",
                args(
                    "package" to "com.example.app",
                    "permission" to "android.permission.CAMERA",
                    "granted" to true,
                ),
            )
            d.dispatch(session, "app.set_appop", args("package" to "com.example.app", "op" to "RUN_IN_BACKGROUND", "mode" to "ignore"))
            assertEquals(listOf("perm com.example.app android.permission.CAMERA true", "op com.example.app RUN_IN_BACKGROUND ignore"), log)
            assertEquals("Grant android.permission.CAMERA for com.example.app", asked.first().detail)
        }

    @Test
    fun `only requested runtime permissions and harmless app ops can be opened up`() =
        runTest {
            val d = dispatcher(AppSetPermissionTool(admin), AppSetAppOpTool(admin))
            val secure =
                d.dispatch(
                    session,
                    "app.set_permission",
                    args("package" to "com.example.app", "permission" to "android.permission.WRITE_SECURE_SETTINGS", "granted" to true),
                )
            val notRequested =
                d.dispatch(
                    session,
                    "app.set_permission",
                    args("package" to "com.example.app", "permission" to "android.permission.READ_SMS", "granted" to true),
                )
            val overlay =
                d.dispatch(session, "app.set_appop", args("package" to "com.example.app", "op" to "SYSTEM_ALERT_WINDOW", "mode" to "allow"))
            val restrict =
                d.dispatch(
                    session,
                    "app.set_appop",
                    args("package" to "com.example.app", "op" to "SYSTEM_ALERT_WINDOW", "mode" to "ignore"),
                )
            listOf(secure, notRequested, overlay).forEach { assertEquals(ToolErrorCode.INVALID_ARGUMENTS, it.errorCode) }
            assertFalse(restrict.isError)
            assertEquals(listOf("op com.example.app SYSTEM_ALERT_WINDOW ignore"), log)
        }

    @Test
    fun `apps on the deny list cannot be changed`() =
        runTest {
            val d = dispatcher(AppStopTool(admin))
            val result = d.dispatch(session, "app.stop", args("package" to "com.td"))
            assertEquals(ToolErrorCode.DENIED_BY_POLICY, result.errorCode)
            assertTrue(log.isEmpty())
        }

    @Test
    fun `destructive app changes are confirmed every time`() =
        runTest {
            val d = dispatcher(AppClearDataTool(admin), AppSetEnabledTool(admin))
            d.dispatch(session, "app.clear_data", args("package" to "com.example.app"))
            d.dispatch(session, "app.clear_data", args("package" to "com.example.app"))
            d.dispatch(session, "app.set_enabled", args("package" to "com.example.app", "enabled" to false))
            assertEquals(3, asked.size)
            assertEquals("Disable com.example.app", asked.last().detail)
        }

    @Test
    fun `shell commands show the command, and do nothing while switched off`() =
        runTest {
            val d = dispatcher(ShellExecTool(shell) { shellOn })
            val ran = d.dispatch(session, "shell.exec", args("command" to "cmd uimode night no"))
            assertFalse(ran.isError)
            assertTrue(ran.content.toString().contains("Exit code 0"))
            assertEquals("cmd uimode night no", asked.single().detail)
            d.dispatch(session, "shell.exec", args("command" to "cmd uimode night no"))
            assertEquals(1, asked.size, "the same command runs again without asking")
            d.dispatch(session, "shell.exec", args("command" to "cmd uimode night yes"))
            assertEquals(2, asked.size, "a different command asks again")

            shellOn = false
            val off = d.dispatch(session, "shell.exec", args("command" to "id"))
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, off.errorCode)
            assertEquals(listOf("sh cmd uimode night no", "sh cmd uimode night no", "sh cmd uimode night yes"), log)
        }

    @Test
    fun `shell is not in the Play build`() {
        val registry = ToolRegistry(setOf(ShellExecTool(shell) { true }), PolicyProfile.PLAY)
        assertTrue(registry.specs.isEmpty())
    }

    @Test
    fun `lists an app's activities and sends intents only to a named app`() =
        runTest {
            val d = dispatcher(AppActivitiesTool(admin), AppSendBroadcastTool(admin))
            val list = d.dispatch(session, "app.activities", args("package" to "com.example.clock", "filter" to "stopwatch"))
            assertEquals("com.example.clock/.Stopwatch actions=com.example.action.STOPWATCH categories=DEFAULT", list.text())

            val extras =
                buildJsonObject {
                    put("package", "net.dinglisch.android.taskerm")
                    put("action", "net.example.RUN")
                    put(
                        "extras",
                        buildJsonObject {
                            put("task", "Lights")
                            put("count", 2)
                            put("on", true)
                        },
                    )
                }
            val sent = d.dispatch(session, "app.send_broadcast", extras)
            assertFalse(sent.isError)
            assertEquals(
                "broadcast -p net.dinglisch.android.taskerm -a net.example.RUN --es task Lights --ei count 2 --ez on true",
                log.single(),
            )

            val noApp = d.dispatch(session, "app.send_broadcast", args("action" to "net.example.RUN"))
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, noApp.errorCode)
            val call =
                d.dispatch(
                    session,
                    "app.send_broadcast",
                    args(
                        "package" to "com.android.phone",
                        "action" to "android.intent.action.CALL",
                    ),
                )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call.errorCode)
        }

    @Test
    fun `battery optimization and standby bucket go through the admin backend`() =
        runTest {
            val d = dispatcher(AppSetBatteryOptimizationTool(admin), AppSetStandbyBucketTool(admin))
            d.dispatch(session, "app.set_battery_optimization", args("package" to "com.termux", "optimized" to false))
            d.dispatch(session, "app.set_standby_bucket", args("package" to "com.termux", "bucket" to "active"))
            val bad = d.dispatch(session, "app.set_standby_bucket", args("package" to "com.termux", "bucket" to "never"))
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, bad.errorCode)
            assertEquals(listOf("optimized com.termux false", "bucket com.termux active"), log)
            assertEquals("Turn battery optimization off for com.termux", asked.first().detail)
        }

    private val ran = mutableListOf<Pair<List<String>, String>>()
    private val system =
        object : SystemReader {
            override val backendId = "fake"
            override val available = MutableStateFlow(true)

            override suspend fun read(
                argv: List<String>,
                filter: String,
                timeoutMs: Long,
            ): ShellResult {
                ran += argv to filter
                return when (argv[0]) {
                    "pidof" -> ShellResult(0, "4242\n", "")
                    "logcat" -> ShellResult(0, "E/App( 4242): login failed for erik@example.com token=abc123\n", "")
                    else -> ShellResult(0, "level: 80\ntemperature: 310\n", "")
                }
            }
        }

    @Test
    fun `adb query runs read-only commands without asking and refuses changes`() =
        runTest {
            val d = dispatcher(AdbQueryTool(system))
            val battery = d.dispatch(session, "adb.query", args("command" to "adb shell dumpsys battery", "grep" to "level"))
            assertFalse(battery.isError)
            assertEquals(listOf("dumpsys", "battery") to "level", ran.single())
            assertTrue(asked.isEmpty())

            for (command in listOf("settings put global adb_enabled 0", "pm uninstall com.example", "dumpsys notification", "sh -c id")) {
                assertEquals(
                    ToolErrorCode.INVALID_ARGUMENTS,
                    d.dispatch(session, "adb.query", args("command" to command)).errorCode,
                    command,
                )
            }
            assertEquals(1, ran.size)
        }

    @Test
    fun `adb logs filter by the app's process and mask personal data`() =
        runTest {
            val d = dispatcher(AdbLogsTool(system))
            val logs = d.dispatch(session, "adb.logs", args("package" to "com.example.app", "level" to "E"))
            assertEquals("E/App( 4242): login failed for [email] token=[masked]", logs.text())
            assertTrue(ran.last().first.containsAll(listOf("logcat", "-d", "*:E", "--pid=4242")))
            assertEquals("Read logcat logs of com.example.app", asked.single().detail)
        }

    private fun app.pocketpilot.core.model.ToolResult.text(): String =
        content.filterIsInstance<app.pocketpilot.core.model.ContentPart.Text>().joinToString("\n") { it.text }

    @Test
    fun `battery usage names the apps behind batterystats uids`() =
        runTest {
            val reader =
                object : SystemReader {
                    override val backendId = "fake"
                    override val available = MutableStateFlow(true)

                    override suspend fun read(
                        argv: List<String>,
                        filter: String,
                        timeoutMs: Long,
                    ): ShellResult =
                        if (argv[0] == "dumpsys") {
                            ShellResult(0, "    UID u0a234: 60.0 fg: 50.0 ( screen=40.0 )\n    UID 1000: 30.0\n    UID u0a5: 10.0\n", "")
                        } else {
                            ShellResult(0, "package:com.example.game uid:10234\npackage:android uid:1000\n", "")
                        }
                }
            val result = dispatcher(BatteryUsageTool(reader)).dispatch(session, "device.battery_usage", args("top" to 2))
            assertEquals(
                "Battery use since the last full charge, about 100 mAh by apps and the system:\n" +
                    "com.example.game: 60.0 mAh (60%)\nAndroid system: 30.0 mAh (30%)",
                result.text(),
            )
            assertTrue(asked.isEmpty())
        }
}
