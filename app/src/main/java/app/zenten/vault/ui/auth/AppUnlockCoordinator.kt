package app.zenten.vault.ui.auth

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

internal const val VAULT_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

sealed interface AppUnlockState {
    data object Checking : AppUnlockState
    data object DeviceLockRequired : AppUnlockState
    data object Locked : AppUnlockState
    data object Authenticating : AppUnlockState
    data object Unlocked : AppUnlockState
}

/** Maps Android's capability result to the fail-closed state used by the app gate. */
internal object DeviceLockPolicy {
    fun stateFor(authenticationResult: Int): AppUnlockState = when (authenticationResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppUnlockState.Locked
        else -> AppUnlockState.DeviceLockRequired
    }

    fun requiresAuthentication(wasUnlocked: Boolean, backgroundAt: Long?, now: Long): Boolean {
        if (!wasUnlocked || backgroundAt == null) return true
        return now - backgroundAt >= GRACE_PERIOD_MILLIS
    }

    const val GRACE_PERIOD_MILLIS = 30_000L
}

/**
 * Owns the in-memory foreground session. No unlock status or timestamp is persisted, so process
 * death always returns the vault to a locked state.
 */
class AppUnlockCoordinator(
    private val activity: FragmentActivity,
    executor: Executor,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val biometricManager = BiometricManager.from(activity)
    private val _state = MutableStateFlow<AppUnlockState>(AppUnlockState.Checking)
    val state: StateFlow<AppUnlockState> = _state

    private var wasUnlocked = false
    private var backgroundAt: Long? = null
    private var promptRequested = false

    private val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptRequested = false
                wasUnlocked = true
                backgroundAt = null
                _state.value = AppUnlockState.Unlocked
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptRequested = false
                _state.value = if (canAuthenticate()) {
                    AppUnlockState.Locked
                } else {
                    wasUnlocked = false
                    AppUnlockState.DeviceLockRequired
                }
            }
        },
    )

    fun onForeground() {
        if (!canAuthenticate()) {
            wasUnlocked = false
            backgroundAt = null
            _state.value = AppUnlockState.DeviceLockRequired
            return
        }

        val mustAuthenticate = DeviceLockPolicy.requiresAuthentication(
            wasUnlocked = wasUnlocked,
            backgroundAt = backgroundAt,
            now = elapsedRealtime(),
        )
        backgroundAt = null
        if (mustAuthenticate) {
            wasUnlocked = false
            _state.value = AppUnlockState.Locked
            authenticate()
        } else {
            _state.value = AppUnlockState.Unlocked
        }
    }

    fun onBackground() {
        if (wasUnlocked) backgroundAt = elapsedRealtime()
    }

    fun authenticate() {
        if (promptRequested || !canAuthenticate()) {
            if (!canAuthenticate()) _state.value = AppUnlockState.DeviceLockRequired
            return
        }
        promptRequested = true
        _state.value = AppUnlockState.Authenticating
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock ZenTen Vault")
                .setSubtitle("Confirm your identity to continue")
                .setAllowedAuthenticators(VAULT_AUTHENTICATORS)
                .build(),
        )
    }

    fun recheckDeviceLock() {
        wasUnlocked = false
        backgroundAt = null
        onForeground()
    }

    private fun canAuthenticate(): Boolean =
        biometricManager.canAuthenticate(VAULT_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}
