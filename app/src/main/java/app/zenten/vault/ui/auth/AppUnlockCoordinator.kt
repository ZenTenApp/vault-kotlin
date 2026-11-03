package app.zenten.vault.ui.auth

import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * Strong biometrics plus the device PIN/pattern/password. Weak (Class 2) biometrics are not
 * sufficient for vault access.
 */
internal const val VAULT_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** The authenticator set a single prompt may request on the running API level. */
internal enum class UnlockPromptKind {
    /** One prompt may accept a strong biometric or the device credential (API 26-27, 30+). */
    Combined,

    /** The platform rejects combining both, so the prompt is strong-biometric-only (API 28-29). */
    StrongBiometricOnly,
}

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

    /**
     * AndroidX Biometric 1.1.0 rejects `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` on API 28-29 and
     * accepts it on API 26-27 and 30+. Only the middle band needs a separate credential prompt.
     */
    fun promptKind(sdkInt: Int): UnlockPromptKind = when {
        sdkInt < Build.VERSION_CODES.P -> UnlockPromptKind.Combined
        sdkInt <= Build.VERSION_CODES.Q -> UnlockPromptKind.StrongBiometricOnly
        else -> UnlockPromptKind.Combined
    }

    /**
     * Errors that mean the strong-biometric prompt cannot complete. On API 28-29 these hand off
     * to the keyguard credential prompt instead of being treated as a cancellation.
     */
    fun isCredentialFallbackError(errorCode: Int): Boolean =
        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
            errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
            errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT

    /**
     * API 28-29 can only check strong-biometric availability approximately. Anything other than
     * success or "unknown" means the credential prompt is the only usable path.
     */
    fun strongBiometricUnavailable(status: Int): Boolean =
        status != BiometricManager.BIOMETRIC_SUCCESS &&
            status != BiometricManager.BIOMETRIC_STATUS_UNKNOWN

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
 *
 * The prompt always names its authenticator set explicitly. Leaving the set unset is what made
 * AndroidX Biometric 1.1.0 default a non-crypto prompt to `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`,
 * which admitted Class 2 biometrics despite the strong-biometric policy.
 */
class AppUnlockCoordinator(
    private val activity: FragmentActivity,
    executor: Executor,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val biometricManager = BiometricManager.from(activity)
    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
    private val promptKind = DeviceLockPolicy.promptKind(Build.VERSION.SDK_INT)
    private val _state = MutableStateFlow<AppUnlockState>(AppUnlockState.Checking)
    val state: StateFlow<AppUnlockState> = _state

    /**
     * Emits when the host activity must launch the keyguard device-credential prompt. Only used
     * on API 28-29, where the strong biometric and the credential cannot share one prompt.
     */
    private val _deviceCredentialRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deviceCredentialRequests: SharedFlow<Unit> = _deviceCredentialRequests

    private var wasUnlocked = false
    private var backgroundAt: Long? = null
    private var promptRequested = false
    private var credentialRequestInFlight = false

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
                if (promptKind == UnlockPromptKind.StrongBiometricOnly &&
                    DeviceLockPolicy.isCredentialFallbackError(errorCode)
                ) {
                    requestDeviceCredential()
                    return
                }
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

        // Returning from the keyguard credential activity: its result callback settles the state,
        // so evaluating the boundary here would race it and prompt twice.
        if (credentialRequestInFlight) return

        val mustAuthenticate = DeviceLockPolicy.requiresAuthentication(
            wasUnlocked = wasUnlocked,
            backgroundAt = backgroundAt,
            now = elapsedRealtime(),
        )
        backgroundAt = null
        if (mustAuthenticate) {
            wasUnlocked = false
            _state.value = AppUnlockState.Locked
            // On API 28-29 the credential fallback must stay an explicit user action. Auto-launching
            // it on every foreground would trap a user who cancels in a prompt loop.
            if (promptKind == UnlockPromptKind.Combined || strongBiometricAvailable()) {
                authenticate()
            }
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
        if (promptRequested || credentialRequestInFlight) return
        if (promptKind == UnlockPromptKind.StrongBiometricOnly && !strongBiometricAvailable()) {
            requestDeviceCredential()
            return
        }
        promptRequested = true
        _state.value = AppUnlockState.Authenticating
        prompt.authenticate(buildPromptInfo())
    }

    /** Called by the host activity with the result of the keyguard credential prompt. */
    fun onDeviceCredentialResult(succeeded: Boolean) {
        credentialRequestInFlight = false
        promptRequested = false
        if (succeeded) {
            // Record the return as the start of a fresh grace window so the ON_START foreground
            // check does not immediately relock the session this prompt just unlocked.
            wasUnlocked = true
            backgroundAt = elapsedRealtime()
            _state.value = AppUnlockState.Unlocked
        } else {
            wasUnlocked = false
            backgroundAt = null
            _state.value = AppUnlockState.Locked
        }
    }

    fun recheckDeviceLock() {
        wasUnlocked = false
        backgroundAt = null
        onForeground()
    }

    /**
     * API 28-29 cannot ask `canAuthenticate` for the combined set. A secure keyguard proves the
     * device credential exists; whether a strong biometric is also enrolled is decided separately
     * because either factor may open the vault on those versions.
     */
    private fun canAuthenticate(): Boolean = when (promptKind) {
        UnlockPromptKind.Combined ->
            biometricManager.canAuthenticate(VAULT_AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS

        UnlockPromptKind.StrongBiometricOnly ->
            keyguardManager?.isDeviceSecure == true
    }

    private fun strongBiometricAvailable(): Boolean =
        !DeviceLockPolicy.strongBiometricUnavailable(
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
        )

    private fun requestDeviceCredential() {
        if (!canAuthenticate()) {
            _state.value = AppUnlockState.DeviceLockRequired
            return
        }
        if (credentialRequestInFlight) return
        credentialRequestInFlight = true
        promptRequested = false
        _state.value = AppUnlockState.Authenticating
        if (!_deviceCredentialRequests.tryEmit(Unit)) {
            credentialRequestInFlight = false
            _state.value = AppUnlockState.Locked
        }
    }

    /** Builds a prompt that always names an explicit authenticator set; see the class KDoc. */
    private fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ZenTen Vault")
            .setSubtitle("Confirm your identity to continue")
        when (promptKind) {
            UnlockPromptKind.Combined -> builder.setAllowedAuthenticators(VAULT_AUTHENTICATORS)
            UnlockPromptKind.StrongBiometricOnly -> builder
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use device PIN")
        }
        return builder.build()
    }
}
