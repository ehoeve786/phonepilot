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
import app.pocketpilot.capability.api.files.FileStore
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.core.automation.AgentOutcome
import app.pocketpilot.core.automation.AutomationFailure
import app.pocketpilot.core.automation.AutomationHost
import app.pocketpilot.core.automation.AutomationJson
import app.pocketpilot.core.automation.NotificationInfo
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.StateKeys
import app.pocketpilot.core.automation.fixedShellCommands
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.policy.PolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
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
    private val files: FileStore,
    private val homeAssistant: HomeAssistant,
    private val calendars: Calendars,
    private val places: PlaceWatcher,
    private val screen: ScreenReader,
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
     * shell still asks for each new command and destructive tools ask every time. A run that another app
     * started through the "Event received" trigger gets none of that: every sensitive call asks the owner,
     * except the rule's fixed shell commands, which the owner saved.
     */
    override fun openSession(
        rule: Rule,
        tools: Set<String>,
        fromOutside: Boolean,
    ): Session {
        val session =
            Session(
                id = SessionId(ids.next()),
                kind = SessionKind.AUTOMATION,
                principal = "Automation: ${rule.name}",
                grantedScopes = Scope.KNOWN - setOf(Scope.TERMUX_RUN) - if (shellOn()) emptySet() else setOf(Scope.SHELL_EXEC),
                createdAtMillis = System.currentTimeMillis(),
            )
        if (!fromOutside) {
            policy.preConfirm(session.id, tools - SHELL_EXEC)
        }
        // The fixed commands are the owner's own saved text, so they run without asking even when another app
        // starts the rule. A command built from event data is not fixed and still asks each time.
        policy.preConfirmDetails(session.id, SHELL_EXEC, rule.fixedShellCommands())
        return session
    }

    override fun closeSession(session: Session) = policy.endSession(session.id)

    override suspend fun state(key: String): String? {
        StateKeys.homeAssistantEntity(key)?.let { return homeAssistant.stateOf(it) }
        StateKeys.screenTextOf(key)?.let { return screenShows(it) }
        StateKeys.screenTabOf(key)?.let { return tabSelected(it) }
        return when (key) {
            StateKeys.LOCATION -> places.lastLocation()
            StateKeys.CALENDAR_NOW -> withContext(Dispatchers.IO) { runCatching { calendars.now() }.getOrNull() }
            else -> phone.read(key)
        }
    }

    /** "on" when [text] is on the screen now, else "off"; a screen that cannot be read counts as "off". */
    private suspend fun screenShows(text: String): String {
        val shown =
            runCatching {
                // Nothing is read from a hidden app, such as a bank, so a rule cannot probe its screen.
                if (hidden(screen.foreground()?.packageName.orEmpty())) return@runCatching false
                screen.snapshot().elements.any { element ->
                    listOfNotNull(element.text, element.description).any { it.contains(text, ignoreCase = true) }
                }
            }.getOrDefault(false)
        return if (shown) "on" else "off"
    }

    /**
     * "on" when the tab named [label] has its selection bar on the screen, else "off". Reads the layout
     * tree, so no capture is taken. Nothing is read while a hidden app is in front.
     */
    private suspend fun tabSelected(label: String): String {
        val shown =
            runCatching {
                if (hidden(screen.foreground()?.packageName.orEmpty())) return@runCatching false
                screen.hasSelectionBarOf(label)
            }.getOrDefault(false)
        return if (shown) "on" else "off"
    }

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

            "http.download" -> return download(params)

            "ha.call" -> return homeAssistant.call(params.p("service"), params.p("entity"), jsonObject(params["data"]))

            "ha.get" -> return homeGet(params.p("entity"), params.p("attribute"))

            "calendar.add" -> return calendarAdd(params)

            "calendar.read" -> return withContext(
                Dispatchers.IO,
            ) { calendars.upcoming(params.p("hours").toIntOrNull() ?: 24, params.p("title")) }

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

    /** Saves a web address to a new file in a shared folder; returns the file's path. */
    private suspend fun download(params: Map<String, String>): String {
        val url = params.p("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw AutomationFailure("The URL must start with https://")
        val path = params.p("path").ifBlank { throw AutomationFailure("Give the path to save to") }
        val bytes =
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = HTTP_TIMEOUT_MS
                    connection.readTimeout = HTTP_TIMEOUT_MS
                    val status = connection.responseCode
                    if (status >= HTTP_ERROR) throw AutomationFailure("HTTP $status")
                    if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) throw AutomationFailure("The file is over 50 MB")
                    connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > MAX_DOWNLOAD_BYTES) throw AutomationFailure("The file is over 50 MB")
                        }
                        out.toByteArray()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        try {
            files.write(path, bytes, replace = false)
        } catch (e: ToolException) {
            throw AutomationFailure(e.message ?: "Could not save $path")
        }
        return path
    }

    private suspend fun homeGet(
        entity: String,
        attribute: String,
    ): String {
        if (entity.isBlank()) throw AutomationFailure("Give the entity ID")
        val json = homeAssistant.entity(entity)
        if (attribute.isBlank()) return json["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val value = (json["attributes"] as? JsonObject)?.get(attribute) ?: throw AutomationFailure("$entity has no attribute $attribute")
        return (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    }

    private suspend fun calendarAdd(params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            calendars.add(
                title = params.p("title").ifBlank { throw AutomationFailure("Give the event a title") },
                start = params.p("start"),
                minutes = params.p("minutes").toIntOrNull() ?: DEFAULT_EVENT_MINUTES,
                location = params.p("location"),
                calendar = params.p("calendar"),
            )
        }

    private fun jsonObject(text: String?): JsonObject =
        if (text.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { AutomationJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw AutomationFailure("Extra data must be a JSON object")
        }

    private fun Map<String, String>.p(key: String): String = this[key].orEmpty().trim()

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
        const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
        const val DEFAULT_EVENT_MINUTES = 60
    }
}
