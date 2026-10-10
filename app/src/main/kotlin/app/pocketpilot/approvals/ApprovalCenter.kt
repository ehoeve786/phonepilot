package app.pocketpilot.approvals

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pocketpilot.R
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.policy.ConfirmationAnswer
import app.pocketpilot.core.policy.ConfirmationRequest
import app.pocketpilot.core.policy.Confirmer
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.server.oauth.ConsentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Something waiting for the owner's answer on the phone. */
sealed interface ApprovalItem {
    val id: String

    data class Consent(
        val request: ConsentRequest,
    ) : ApprovalItem {
        override val id: String get() = request.id
    }

    data class Confirm(
        override val id: String,
        val request: ConfirmationRequest,
    ) : ApprovalItem
}

/**
 * Collects everything that needs the owner (OAuth consent and tool confirmations), shows a
 * high-priority notification that opens [ApprovalActivity], and hands the answers back.
 */
class ApprovalCenter(
    private val context: Context,
    private val oauth: AuthorizationServer,
    scope: CoroutineScope,
) : Confirmer {
    private val confirmations = MutableStateFlow<List<ApprovalItem.Confirm>>(emptyList())
    private val answers = HashMap<String, CompletableDeferred<Boolean>>()

    val items: StateFlow<List<ApprovalItem>> =
        combine(oauth.pending, confirmations) { consents, confirms -> consents.map(ApprovalItem::Consent) + confirms }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { items.collect(::notify) }
    }

    override suspend fun confirm(request: ConfirmationRequest): ConfirmationAnswer {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Boolean>()
        synchronized(answers) { answers[id] = answer }
        confirmations.update { it + ApprovalItem.Confirm(id, request) }
        return try {
            when (withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { answer.await() }) {
                true -> ConfirmationAnswer.APPROVED
                false -> ConfirmationAnswer.DECLINED
                null -> ConfirmationAnswer.TIMED_OUT
            }
        } finally {
            synchronized(answers) { answers.remove(id) }
            confirmations.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** The owner's answer to a consent request; null scopes means Deny. */
    fun answerConsent(
        id: String,
        scopes: Set<String>?,
    ) {
        if (scopes == null) {
            oauth.deny(id)
        } else {
            oauth.approve(id, scopes.mapNotNull { runCatching { Scope(it) }.getOrNull() }.toSet())
        }
    }

    fun answerConfirm(
        id: String,
        allow: Boolean,
    ) {
        synchronized(answers) { answers[id] }?.complete(allow)
    }

    private fun notify(items: List<ApprovalItem>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = items.firstOrNull()
        if (first == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.approvals_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text =
            when (first) {
                is ApprovalItem.Consent -> context.getString(R.string.approvals_consent_text, first.request.clientName)
                is ApprovalItem.Confirm -> context.getString(R.string.approvals_confirm_text, first.request.principal, first.request.tool)
            }
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(context.getString(R.string.approvals_title))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "approvals"
        const val NOTIFICATION_ID = 2
        const val CONFIRM_TIMEOUT_MS = 60_000L
    }
}
