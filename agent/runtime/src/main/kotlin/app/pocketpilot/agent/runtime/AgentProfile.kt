package app.pocketpilot.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** The provider families the agent can talk to (spec section 9, Adapters). */
@Serializable
enum class ProviderKind(
    val displayName: String,
    /** Whether calls cost money by default, which turns the cost budget on. */
    val paid: Boolean,
) {
    ANTHROPIC("Anthropic", paid = true),
    OPENAI("OpenAI", paid = true),
    GEMINI("Google Gemini", paid = true),

    /** Ollama, LM Studio, llama.cpp, DeepSeek, OpenRouter and other servers with the OpenAI API. */
    OPENAI_COMPATIBLE("OpenAI-compatible", paid = false),
}

/**
 * A model the owner set up for Agent mode. The API key is not part of the profile; the app keeps it in
 * its secret store under [keyName].
 */
@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val model: String,
    /** Needed for [ProviderKind.OPENAI_COMPATIBLE], such as `http://192.168.1.20:11434/v1`. */
    val baseUrl: String? = null,
    /** Overrides the default cost limit; null uses the default for [kind]. */
    val maxCostUsd: Double? = null,
) {
    val keyName: String get() = "agent.key.$id"

    /** The id the provider reports, which the model registry is keyed by. */
    val providerId: String
        get() =
            when (kind) {
                ProviderKind.ANTHROPIC -> "anthropic"
                ProviderKind.OPENAI -> "openai"
                ProviderKind.GEMINI -> "gemini"
                ProviderKind.OPENAI_COMPATIBLE -> "openai-compatible:${compatibleName()}"
            }

    /** A short name for the compatible server, from its host. */
    fun compatibleName(): String =
        baseUrl
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.ifBlank { null } ?: "custom"

    /** Budgets for runs with this profile: the spec's defaults, with a cost limit for paid APIs. */
    fun budgets(): AgentBudgets = AgentBudgets(maxCostUsd = maxCostUsd ?: if (kind.paid) DEFAULT_PAID_LIMIT_USD else null)

    companion object {
        const val DEFAULT_PAID_LIMIT_USD = 0.50
    }
}

/** Keeps the owner's profiles in one JSON file. */
class ProfileStore(
    private val file: File,
) {
    private val serializer = ListSerializer(AgentProfile.serializer())
    private val mutableProfiles = MutableStateFlow(load())
    val profiles: StateFlow<List<AgentProfile>> = mutableProfiles.asStateFlow()

    /** Adds [profile], or replaces the one with the same id. */
    fun save(profile: AgentProfile) {
        write { list -> list.filterNot { it.id == profile.id } + profile }
    }

    fun remove(id: String) {
        write { list -> list.filterNot { it.id == id } }
    }

    @Synchronized
    private fun write(change: (List<AgentProfile>) -> List<AgentProfile>) {
        mutableProfiles.update(change)
        writeAtomically(file, json.encodeToString(serializer, mutableProfiles.value))
    }

    private fun load(): List<AgentProfile> =
        try {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
}

/**
 * Keeps one transcript file per run (spec section 9, Transcript), dropping the oldest beyond [keep].
 * The spec's Room tables come with the database in a later milestone; until then this is the store.
 */
class FileRunStore(
    private val dir: File,
    private val keep: Int = 50,
) : RunStore {
    private val lock = Any()

    override suspend fun save(run: RunState) {
        val text = json.encodeToString(RunState.serializer(), run)
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                writeAtomically(File(dir, "${run.id}.json"), text)
                files().drop(keep).forEach(File::delete)
            }
        }
    }

    /** Stored runs, newest first. */
    fun list(): List<RunState> = files().mapNotNull(::read)

    private fun files(): List<File> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            // Run ids are ULIDs, so names sort by start time.
            .sortedByDescending { it.name }

    private fun read(file: File): RunState? =
        try {
            json.decodeFromString(RunState.serializer(), file.readText())
        } catch (e: IllegalArgumentException) {
            null
        }
}

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private fun writeAtomically(
    file: File,
    text: String,
) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}
