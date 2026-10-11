package app.pocketpilot.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
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
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
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
        const val PREFS = "security"
        const val KEY = "biometric_approvals"
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }
}
