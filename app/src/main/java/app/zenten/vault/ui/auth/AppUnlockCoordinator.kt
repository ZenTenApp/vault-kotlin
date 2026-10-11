package app.zenten.vault.ui.auth

import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * Strong biometrics plus the device PIN/pattern/password. Weak (Class 2) biometrics are not
 * sufficient for vault access.
 */
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

/** Pure mapping from capability/error outcomes to the fail-closed state used by the gate. */
internal object DeviceLockPolicy {
    fun stateFor(canAuthenticate: Boolean): AppUnlockState =
        if (canAuthenticate) AppUnlockState.Locked else AppUnlockState.DeviceLockRequired

    fun requiresAuthentication(wasUnlocked: Boolean, backgroundAt: Long?, now: Long): Boolean {
        if (!wasUnlocked || backgroundAt == null) return true
        return now - backgroundAt >= GRACE_PERIOD_MILLIS
    }

    const val GRACE_PERIOD_MILLIS = 30_000L
}

/**
 * Classifies prompt errors. Errors that mean the device cannot satisfy the policy route to the
 * enrollment screen; everything else stays on the retryable locked screen.
 */
internal object AuthErrorPolicy {
    private val ENROLLMENT_ERRORS = setOf(
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
    )

    fun stateFor(errorCode: Int, canAuthenticate: Boolean): AppUnlockState = when {
        errorCode in ENROLLMENT_ERRORS -> AppUnlockState.DeviceLockRequired
        !canAuthenticate -> AppUnlockState.DeviceLockRequired
        else -> AppUnlockState.Locked
    }
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
    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
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
                val next = AuthErrorPolicy.stateFor(errorCode, canAuthenticate())
                if (next == AppUnlockState.DeviceLockRequired) wasUnlocked = false
                _state.value = next
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
        if (!canAuthenticate()) {
            _state.value = AppUnlockState.DeviceLockRequired
            return
        }
        if (promptRequested) return
        promptRequested = true
        _state.value = AppUnlockState.Authenticating
        prompt.authenticate(buildPromptInfo())
    }

    fun recheckDeviceLock() {
        wasUnlocked = false
        backgroundAt = null
        onForeground()
    }

    /**
     * `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is unsupported by `canAuthenticate` before API 30,
     * so older devices fall back to the keyguard's own "device is secure" signal. A secure
     * keyguard is a prerequisite for biometric enrollment, so it covers both authenticators.
     */
    private fun canAuthenticate(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return biometricManager.canAuthenticate(VAULT_AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        return keyguardManager?.isDeviceSecure == true
    }

    /** Matches the prompt policy to the running API level so `build()` never throws. */
    private fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ZenTen Vault")
            .setSubtitle("Confirm your identity to continue")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(VAULT_AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }
}
