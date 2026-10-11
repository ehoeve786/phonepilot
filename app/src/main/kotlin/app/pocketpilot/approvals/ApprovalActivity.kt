package app.pocketpilot.approvals

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.feature.approvals.ConfirmScreen
import app.pocketpilot.feature.approvals.ConfirmUi
import app.pocketpilot.feature.approvals.ConsentScreen
import app.pocketpilot.feature.approvals.ConsentUi
import app.pocketpilot.feature.approvals.ScopeOption
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.ui.PocketPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Shows the oldest open approval full screen, and closes when none are left. */
@AndroidEntryPoint
class ApprovalActivity : ComponentActivity() {
    @Inject lateinit var center: ApprovalCenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val items = center.items.collectAsStateWithLifecycle().value
            val first = items.firstOrNull()
            LaunchedEffect(first == null) { if (first == null) finish() }
            PocketPilotTheme {
                when (first) {
                    is ApprovalItem.Consent -> {
                        ConsentScreen(
                            ui = first.toUi(),
                            onAllow = { scopes -> center.answerConsent(first.id, scopes) },
                            onDeny = { center.answerConsent(first.id, null) },
                        )
                    }

                    is ApprovalItem.Confirm -> {
                        ConfirmScreen(
                            ui =
                                ConfirmUi(
                                    principal = first.request.principal,
                                    toolTitle = first.request.toolTitle,
                                    tool = first.request.tool,
                                    destructive = first.request.riskTier == RiskTier.DESTRUCTIVE,
                                    foregroundPackage = first.request.foregroundPackage,
                                    detail = first.request.detail,
                                ),
                            onAllow = { center.answerConfirm(first.id, true) },
                            onDeny = { center.answerConfirm(first.id, false) },
                        )
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }
    }

    /** Requested scopes start ticked; when the client asked for none, everything grantable does. */
    private fun ApprovalItem.Consent.toUi(): ConsentUi {
        val requested = request.requestedScopes.map { it.value }.toSet()
        return ConsentUi(
            clientName = request.clientName,
            redirectHost = request.redirectHost,
            options =
                AuthorizationServer.GRANTABLE
                    .map { it.value }
                    .sorted()
                    .map { ScopeOption(it, preselected = if (requested.isEmpty()) it in DEFAULT_SCOPES else it in requested) },
        )
    }

    private companion object {
        /** What a client that names no scopes needs to drive the phone like Claude Code does locally. */
        val DEFAULT_SCOPES = setOf("screen:read", "ui:interact", "apps:read", "apps:control", "device:read")
    }
}
