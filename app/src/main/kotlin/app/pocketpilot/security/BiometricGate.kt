package app.pocketpilot.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import app.pocketpilot.network.api.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The biometric lock on approvals (spec section 8): while it is on, allowing a confirmation or an
 * app's access needs the owner's fingerprint, face or screen lock, so someone else holding the
 * unlocked phone cannot approve. On by default.
 */
class BiometricGate(
    context: Context,
    private val secrets: SecretStore,
) {
    private val _enabled = MutableStateFlow(readOrMigrate(context))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val manager = context.getSystemService(BiometricManager::class.java)

    /** False when the phone has no screen lock or fingerprint, so the lock has nothing to ask for. */
    fun lockAvailable(): Boolean = manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun set(on: Boolean) {
        secrets.put(NAME, on.toString())
        _enabled.value = on
    }

    /** Kept in the Keystore-backed store, so editing a shared-preferences file cannot switch the lock off. */
    private fun readOrMigrate(context: Context): Boolean {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.contains(LEGACY_KEY)) {
            if (secrets.get(NAME) == null) secrets.put(NAME, legacy.getBoolean(LEGACY_KEY, true).toString())
            legacy.edit().remove(LEGACY_KEY).apply()
        }
        return secrets.get(NAME)?.toBooleanStrictOrNull() ?: true
    }

    /**
     * Runs [onSuccess] once the owner proves it is them. Runs it straight away when the lock is off or
     * the phone has no screen lock to check against. A cancelled or failed prompt runs nothing, so
     * the owner can try again or deny.
     */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
    ) {
        val manager = activity.getSystemService(BiometricManager::class.java)
        if (!enabled.value || manager.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }
        BiometricPrompt
            .Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                },
            )
    }

    private companion object {
        const val NAME = "biometric_approvals"
        const val LEGACY_PREFS = "security"
        const val LEGACY_KEY = "biometric_approvals"
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }
}
