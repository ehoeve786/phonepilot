package app.pocketpilot.network.certificates

import android.content.Context
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.gonet.gen.ppnet.CertManager
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/** The owner's own domain and where its certificate stands. */
data class CertificateState(
    val domain: String = "",
    val email: String = "",
    val hasToken: Boolean = false,
    val ready: Boolean = false,
    /** Expiry in Unix milliseconds while [ready]. */
    val notAfter: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
    /** Whether the domain's DNS record follows the relay's address. */
    val followRelay: Boolean = false,
)

/**
 * A publicly trusted certificate for the owner's domain (spec section 7, Certificates), used by the
 * WireGuard provider, which has no certificate of its own the way Tailscale does.
 * Let's Encrypt checks control of the domain with a DNS record added through a Cloudflare API
 * token. The token lives in the [SecretStore]; the certificate's key never leaves the phone.
 */
class CertificateManager(
    context: Context,
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "certificates")

    private val mutableState =
        MutableStateFlow(
            CertificateState(
                domain = prefs.getString(KEY_DOMAIN, "").orEmpty(),
                email = prefs.getString(KEY_EMAIL, "").orEmpty(),
                hasToken = secrets.get(SECRET_TOKEN) != null,
                followRelay = prefs.getBoolean(KEY_FOLLOW_RELAY, true),
            ),
        )
    val state: StateFlow<CertificateState> = mutableState.asStateFlow()

    /** The Go certificate store the listeners present; loading it loads the Go library. */
    val native: CertManager by lazy {
        go.Seq.setContext(appContext)
        Ppnet.newCertManager(dir.absolutePath).also { certs ->
            val s = mutableState.value
            certs.configure(s.domain, s.email, secrets.get(SECRET_TOKEN).orEmpty())
            certs.startRenewals()
        }
    }

    /** Domain names a remote client may use to reach this phone through a relay. */
    val domain: String? get() = mutableState.value.domain.takeIf(String::isNotEmpty)

    /** Saves the settings; a blank [token] keeps the saved one. */
    fun configure(
        domain: String,
        email: String,
        token: String,
    ) {
        val cleanDomain = domain.trim().trimEnd('.').lowercase()
        prefs
            .edit()
            .putString(KEY_DOMAIN, cleanDomain)
            .putString(KEY_EMAIL, email.trim())
            .apply()
        if (token.isNotBlank()) secrets.put(SECRET_TOKEN, token.trim())
        mutableState.value =
            mutableState.value.copy(
                domain = cleanDomain,
                email = email.trim(),
                hasToken = secrets.get(SECRET_TOKEN) != null,
                error = null,
            )
        scope.launch(Dispatchers.IO) {
            runCatching { native.configure(cleanDomain, email.trim(), secrets.get(SECRET_TOKEN).orEmpty()) }
            refresh()
        }
    }

    /** Gets a certificate if there is none or it expires within 30 days. Takes up to a few minutes. */
    fun request() {
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { native.obtain() }
            refresh()
            result.exceptionOrNull()?.let { e ->
                mutableState.value = mutableState.value.copy(busy = false, error = e.message ?: e.toString())
            }
        }
    }

    fun setFollowRelay(follow: Boolean) {
        prefs.edit().putBoolean(KEY_FOLLOW_RELAY, follow).apply()
        mutableState.value = mutableState.value.copy(followRelay = follow)
    }

    /**
     * Points the domain at the relay host (a router or server name or IPv4 address) when the owner
     * asked for that, so a changing home IP address does not break the connector.
     */
    suspend fun followRelay(relayHost: String) {
        if (!mutableState.value.followRelay || domain == null || !mutableState.value.hasToken) return
        withContext(Dispatchers.IO) {
            val ip =
                runCatching { InetAddress.getAllByName(relayHost).firstOrNull { it is Inet4Address }?.hostAddress }
                    .getOrNull() ?: return@withContext
            runCatching { native.pointDomainAt(ip) }.onFailure { e ->
                mutableState.value = mutableState.value.copy(error = e.message ?: e.toString())
            }
        }
    }

    /** Forgets the token and certificates. */
    fun forget() {
        secrets.remove(SECRET_TOKEN)
        scope.launch(Dispatchers.IO) {
            runCatching {
                native.forget()
                native.configure(mutableState.value.domain, mutableState.value.email, "")
            }
            refresh()
        }
        mutableState.value = mutableState.value.copy(hasToken = false, ready = false, notAfter = 0, error = null)
    }

    fun logs(): String = runCatching { native.logs() }.getOrDefault("")

    /** Reads the certificate's state, loading a saved certificate. */
    fun load() {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    private fun refresh() {
        val status = runCatching { JSONObject(native.status()) }.getOrNull() ?: return
        mutableState.value =
            mutableState.value.copy(
                ready = status.optBoolean("ready"),
                notAfter = status.optLong("notAfter"),
                busy = status.optBoolean("busy"),
                error = status.optString("error").takeIf(String::isNotEmpty),
            )
    }

    private companion object {
        const val PREFS = "network_certificates"
        const val KEY_DOMAIN = "domain"
        const val KEY_EMAIL = "email"
        const val KEY_FOLLOW_RELAY = "follow_relay"
        const val SECRET_TOKEN = "cloudflare_token"
    }
}
