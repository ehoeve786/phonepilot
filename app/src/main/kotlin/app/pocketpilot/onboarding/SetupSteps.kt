package app.pocketpilot.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pocketpilot.R
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.audit.AuditOutcome
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.feature.onboarding.SetupStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the phone already has, read live for the setup steps and Doctor. */
data class SetupState(
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val accessibilityOn: Boolean,
    val shizuku: ShizukuState,
    val notificationAccess: Boolean,
    val remoteConnected: Boolean,
    /** Recent tool calls, for the last two steps. */
    val audit: List<AuditEvent>,
)

/** The one action behind each step. */
class SetupActions(
    val allowNotifications: () -> Unit,
    val unrestrictBattery: () -> Unit,
    val turnOnAccessibility: () -> Unit,
    val setUpShizuku: () -> Unit,
    val allowNotificationAccess: () -> Unit,
    val openRemoteAccess: () -> Unit,
    val copyClaudeCodeCommand: () -> Unit,
)

/**
 * The eight setup steps from the design doc, section 2. The app works once Accessibility is on;
 * everything after it adds power, so those steps are optional.
 */
@Composable
fun setupSteps(
    state: SetupState,
    actions: SetupActions,
): List<SetupStep> {
    val clientCalls = state.audit.filter { it.principal != SessionFactory.OWNER_PRINCIPAL }
    return listOf(
        SetupStep(
            id = "notifications",
            title = stringResource(R.string.setup_notifications_title),
            reason = stringResource(R.string.setup_notifications_reason),
            done = state.notificationsAllowed,
            actionLabel = stringResource(R.string.setup_notifications_action),
            action = actions.allowNotifications,
        ),
        SetupStep(
            id = "battery",
            title = stringResource(R.string.setup_battery_title),
            reason = stringResource(R.string.setup_battery_reason),
            done = state.batteryUnrestricted,
            actionLabel = stringResource(R.string.setup_battery_action),
            action = actions.unrestrictBattery,
        ),
        SetupStep(
            id = "accessibility",
            title = stringResource(R.string.setup_accessibility_title),
            reason = stringResource(R.string.setup_accessibility_reason),
            done = state.accessibilityOn,
            actionLabel = stringResource(R.string.setup_accessibility_action),
            action = actions.turnOnAccessibility,
        ),
        SetupStep(
            id = "shizuku",
            title = stringResource(R.string.setup_shizuku_title),
            reason = stringResource(R.string.setup_shizuku_reason),
            done = state.shizuku == ShizukuState.CONNECTED,
            optional = true,
            actionLabel = stringResource(R.string.setup_shizuku_action),
            action = actions.setUpShizuku,
        ),
        SetupStep(
            id = "notification_access",
            title = stringResource(R.string.setup_listener_title),
            reason = stringResource(R.string.setup_listener_reason),
            done = state.notificationAccess,
            optional = true,
            actionLabel = stringResource(R.string.setup_listener_action),
            action = actions.allowNotificationAccess,
        ),
        SetupStep(
            id = "remote",
            title = stringResource(R.string.setup_remote_title),
            reason = stringResource(R.string.setup_remote_reason),
            done = state.remoteConnected,
            optional = true,
            actionLabel = stringResource(R.string.setup_remote_action),
            action = actions.openRemoteAccess,
        ),
        SetupStep(
            id = "client",
            title = stringResource(R.string.setup_client_title),
            reason = stringResource(R.string.setup_client_reason),
            done = clientCalls.isNotEmpty(),
            optional = true,
            actionLabel = stringResource(R.string.setup_client_action),
            action = actions.copyClaudeCodeCommand,
        ),
        SetupStep(
            id = "proof",
            title = stringResource(R.string.setup_proof_title),
            reason = stringResource(R.string.setup_proof_reason),
            done = state.audit.any { it.tool in PROOF_TOOLS && it.outcome == AuditOutcome.SUCCESS },
            optional = true,
        ),
    )
}

private val PROOF_TOOLS = setOf("screen.capture", "screen.snapshot")

/** Whether the owner finished or left setup, and the step to come back to. */
class OnboardingStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _finished = MutableStateFlow(prefs.getBoolean(KEY_FINISHED, false))
    val finished: StateFlow<Boolean> = _finished.asStateFlow()
    private val _index = MutableStateFlow(prefs.getInt(KEY_INDEX, 0))
    val index: StateFlow<Int> = _index.asStateFlow()

    fun setIndex(value: Int) {
        prefs.edit().putInt(KEY_INDEX, value).apply()
        _index.value = value
    }

    fun setFinished(value: Boolean) {
        prefs.edit().putBoolean(KEY_FINISHED, value).apply()
        _finished.value = value
    }

    private companion object {
        const val PREFS = "onboarding"
        const val KEY_FINISHED = "finished"
        const val KEY_INDEX = "index"
    }
}
