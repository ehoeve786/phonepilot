package app.pocketpilot.automation

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.widget.Toast
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.agent.runtime.RunStatus
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.core.automation.AgentOutcome
import app.pocketpilot.core.automation.AutomationFailure
import app.pocketpilot.core.automation.AutomationHost
import app.pocketpilot.core.automation.NotificationInfo
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.policy.PolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * What the automation engine needs from the phone: tool calls through the dispatcher (so policy, the
 * deny list and audit hold for rules as for any client), the phone's state, and the actions the app
 * does itself, such as speaking or the flashlight.
 */
class AndroidAutomationHost(
    private val context: Context,
    private val dispatcher: CallDispatcher,
    private val policy: PolicyEngine,
    private val phone: PhoneState,
    private val shell: ShizukuShellRunner,
    private val shellOn: () -> Boolean,
    private val hidden: (String) -> Boolean,
    private val agent: () -> AgentCoordinator,
    override val sideload: Boolean,
) : AutomationHost {
    private val ids = UlidGenerator(Clock.System)
    private var tts: TextToSpeech? = null

    override suspend fun callTool(
        session: Session,
        tool: String,
        arguments: JsonObject,
    ): ToolResult = dispatcher.dispatch(session, tool, arguments)

    /**
     * The owner made the rule (or accepted it), so its sensitive tools are confirmed for the run; the raw
     * shell still asks for each new command and destructive tools ask every time.
     */
    override fun openSession(
        rule: Rule,
        tools: Set<String>,
    ): Session {
        val session =
            Session(
                id = SessionId(ids.next()),
                kind = SessionKind.AUTOMATION,
                principal = "Automation: ${rule.name}",
                grantedScopes = Scope.KNOWN - setOf(Scope.TERMUX_RUN) - if (shellOn()) emptySet() else setOf(Scope.SHELL_EXEC),
                createdAtMillis = System.currentTimeMillis(),
            )
        policy.preConfirm(session.id, tools - SHELL_EXEC)
        return session
    }

    override fun closeSession(session: Session) = policy.endSession(session.id)

    override suspend fun state(key: String): String? = phone.read(key)

    override suspend fun notifications(): List<NotificationInfo> =
        if (!ListenerNotificationSource.available.value) {
            emptyList()
        } else {
            ListenerNotificationSource
                .active()
                .filterNot { hidden(it.packageName) }
                .map { NotificationInfo(it.packageName, it.title, it.text) }
        }

    override suspend fun askAgent(prompt: String): AgentOutcome {
        val run = agent().runForAutomation(prompt) ?: return AgentOutcome(NO_AGENT, costUsd = 0.0, ok = false)
        return AgentOutcome(
            summary = run.outcome ?: run.status.name.lowercase(),
            costUsd = run.costUsd,
            ok = run.status == RunStatus.FINISHED,
        )
    }

    override suspend fun native(
        type: String,
        params: Map<String, String>,
    ): String? {
        when (type) {
            "screen.wake" -> wake()
            "media.control" -> media(params["command"].orEmpty())
            "speak" -> speak(params["text"].orEmpty())
            "vibrate" -> vibrate(params["ms"]?.toLongOrNull() ?: DEFAULT_VIBRATE_MS)
            "flashlight" -> torch(params["state"] != "off")
            "toast" -> withContext(Dispatchers.Main) { Toast.makeText(context, params["text"].orEmpty(), Toast.LENGTH_LONG).show() }
            "http.request" -> return http(params)
            else -> throw AutomationFailure("This build cannot run $type")
        }
        return null
    }

    /** Through Shizuku when it runs, as a key press any screen lock accepts; else with a wake lock. */
    private suspend fun wake() {
        if (shell.available.value &&
            runCatching { shell.run("input keyevent KEYCODE_WAKEUP", SHELL_TIMEOUT_MS).exitCode == 0 }.getOrDefault(false)
        ) {
            return
        }
        @Suppress("DEPRECATION")
        val lock =
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "PocketPilot:automation")
        lock.acquire(WAKE_MS)
    }

    private fun media(command: String) {
        val code =
            when (command) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: startTts().also { tts = it }
        val id = ids.next()
        withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine { done ->
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == id && done.isActive) done.resume(Unit)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == id && done.isActive) done.resume(Unit)
                        }
                    },
                )
                if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS && done.isActive) done.resume(Unit)
                done.invokeOnCancellation { engine.stop() }
            }
        }
    }

    private suspend fun startTts(): TextToSpeech =
        withContext(Dispatchers.Main) {
            var engine: TextToSpeech? = null
            val ok =
                withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { ready ->
                        engine = TextToSpeech(context) { status -> if (ready.isActive) ready.resume(status == TextToSpeech.SUCCESS) }
                    }
                } ?: false
            if (!ok) {
                engine?.shutdown()
                throw AutomationFailure("Text-to-speech is not available on this phone")
            }
            engine!!
        }

    private fun vibrate(ms: Long) {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        vibrator.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, MAX_VIBRATE_MS), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun torch(on: Boolean) {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id =
            cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw AutomationFailure("This phone has no flashlight")
        cameras.setTorchMode(id, on)
    }

    private suspend fun http(params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val url = params["url"].orEmpty().trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw AutomationFailure("The URL must start with https://")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = params["method"].orEmpty().ifBlank { "GET" }.uppercase()
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                params["headers"].orEmpty().lines().forEach { line ->
                    val name = line.substringBefore(':', "").trim()
                    if (name.isNotEmpty()) connection.setRequestProperty(name, line.substringAfter(':').trim())
                }
                val body = params["body"].orEmpty()
                if (body.isNotEmpty() && connection.requestMethod != "GET") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
                val reply = stream?.bufferedReader()?.use { it.readText().take(MAX_REPLY_CHARS) }.orEmpty()
                if (status >= HTTP_ERROR) throw AutomationFailure("HTTP $status: ${reply.take(MAX_ERROR_CHARS)}")
                reply
            } finally {
                connection.disconnect()
            }
        }

    /** Frees text-to-speech when automations turn off. */
    fun release() {
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val SHELL_EXEC = "shell.exec"
        const val NO_AGENT = "Agent mode has no usable model, or another Agent run is in progress"
        const val SHELL_TIMEOUT_MS = 5_000L
        const val WAKE_MS = 5_000L
        const val SPEAK_TIMEOUT_MS = 120_000L
        const val TTS_INIT_TIMEOUT_MS = 10_000L
        const val DEFAULT_VIBRATE_MS = 500L
        const val MAX_VIBRATE_MS = 10_000L
        const val HTTP_TIMEOUT_MS = 30_000
        const val HTTP_ERROR = 400
        const val MAX_REPLY_CHARS = 100_000
        const val MAX_ERROR_CHARS = 200
    }
}
