package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.AppPermissions
import app.pocketpilot.capability.api.shell.ShellResult
import app.pocketpilot.capability.api.shell.ShellRunner
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

            shellOn = false
            val off = d.dispatch(session, "shell.exec", args("command" to "id"))
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, off.errorCode)
            assertEquals(listOf("sh cmd uimode night no"), log)
        }

    @Test
    fun `shell is not in the Play build`() {
        val registry = ToolRegistry(setOf(ShellExecTool(shell) { true }), PolicyProfile.PLAY)
        assertTrue(registry.specs.isEmpty())
    }
}
