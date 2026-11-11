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

/**
 * The unlock session that outlives a single Activity instance.
 *
 * A configuration change (rotation, theme/dark mode, locale, font size) destroys and recreates the
 * Activity. This holder stays alive in the Activity-scoped `AppUnlockViewModel`, so the user does
 * not have to authenticate again merely because the screen was recreated. Everything here is
 * memory-only: process death discards the holder and the vault starts locked.
 *
 * It deliberately contains no Android types so the coordinator can be recreated against a new
 * Activity without copying session state across, and so the policy remains unit-testable.
 */
internal class AppUnlockSessionState {
    val state = MutableStateFlow<AppUnlockState>(AppUnlockState.Checking)

    /** Requests the host Activity to run the API 28-29 keyguard credential prompt. */
    val deviceCredentialRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True once the prompt has succeeded; cleared whenever authentication is required again. */
    var wasUnlocked = false

    /**
     * Monotonic time the Activity was last backgrounded, or null when the session has not been
     * backgrounded since it was unlocked (including after an Activity recreation).
     */
    var backgroundAt: Long? = null

    /** Guards against showing two biometric prompts at once. */
    var promptRequested = false

    /** True while the API 28-29 keyguard credential confirmation is outstanding. */
    var credentialRequestInFlight = false
}

/** Pure mapping from capability/error outcomes to the fail-closed state used by the gate. */
internal object DeviceLockPolicy {
    fun stateFor(canAuthenticate: Boolean): AppUnlockState =
        if (canAuthenticate) AppUnlockState.Locked else AppUnlockState.DeviceLockRequired

    /**
     * Decides whether the foreground boundary must re-prompt.
     *
     * `wasUnlocked` is memory-only, so a cold start or process death always passes false and
     * authenticates. A real background trip records its start in `backgroundAt`. A null timestamp
     * therefore means the Activity was recreated for a configuration change without the app being
     * backgrounded: nothing was hidden from the user, so the unlocked session and its grace window
     * are retained and no re-authentication is needed.
     */
    fun requiresAuthentication(wasUnlocked: Boolean, backgroundAt: Long?, now: Long): Boolean {
        if (!wasUnlocked) return true
        if (backgroundAt == null) return false
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
 * Owns the foreground unlock session and the Activity-bound prompt machinery.
 *
 * No unlock status or timestamp is persisted, so process death always returns the vault to a
 * locked state. The session flags live in [AppUnlockSessionState], which `AppUnlockViewModel`
 * retains across Activity recreation; a coordinator is rebound to each new Activity.
 *
 * The prompt always names its authenticator set explicitly. Leaving the set unset is what made
 * AndroidX Biometric 1.1.0 default a non-crypto prompt to `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`,
 * which admitted Class 2 biometrics despite the strong-biometric policy.
 */
internal class AppUnlockCoordinator(
    private val activity: FragmentActivity,
    executor: Executor,
    private val session: AppUnlockSessionState = AppUnlockSessionState(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val biometricManager = BiometricManager.from(activity)
    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
    private val promptKind = DeviceLockPolicy.promptKind(Build.VERSION.SDK_INT)

    val state: StateFlow<AppUnlockState> = session.state

    /**
     * Emits when the host activity must launch the keyguard device-credential prompt. Only used
     * on API 28-29, where the strong biometric and the credential cannot share one prompt.
     */
    val deviceCredentialRequests: SharedFlow<Unit> = session.deviceCredentialRequests

    /**
     * Set once this coordinator's Activity is being replaced or torn down. A prompt callback can
     * arrive after the Activity is gone (for example, a cancel triggered by [close]), so every
     * entry point checks this before mutating shared session state.
     */
    private var closed = false

    private val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (closed) return
                session.promptRequested = false
                session.wasUnlocked = true
                session.backgroundAt = null
                session.state.value = AppUnlockState.Unlocked
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (closed) return
                session.promptRequested = false
                if (promptKind == UnlockPromptKind.StrongBiometricOnly &&
                    DeviceLockPolicy.isCredentialFallbackError(errorCode)
                ) {
                    requestDeviceCredential()
                    return
                }
                val next = AuthErrorPolicy.stateFor(errorCode, canAuthenticate())
                if (next == AppUnlockState.DeviceLockRequired) session.wasUnlocked = false
                session.state.value = next
            }
        },
    )

    /**
     * Detaches this coordinator from the session. Idempotent, and safe to call from
     * `ViewModel.onCleared` or when the Activity is replaced for a configuration change.
     *
     * The in-flight prompt is cancelled so its window cannot outlive the Activity. Its callbacks
     * are ignored because [closed] is already set.
     */
    fun close() {
        if (closed) return
        closed = true
        runCatching { prompt.cancelAuthentication() }
    }

    fun onForeground() {
        if (closed) return
        if (!canAuthenticate()) {
            session.wasUnlocked = false
            session.backgroundAt = null
            session.state.value = AppUnlockState.DeviceLockRequired
            return
        }

        // Returning from the keyguard credential activity: its result callback settles the state,
        // so evaluating the boundary here would race it and prompt twice.
        if (session.credentialRequestInFlight) return

        val mustAuthenticate = DeviceLockPolicy.requiresAuthentication(
            wasUnlocked = session.wasUnlocked,
            backgroundAt = session.backgroundAt,
            now = elapsedRealtime(),
        )
        session.backgroundAt = null
        if (mustAuthenticate) {
            session.wasUnlocked = false
            session.state.value = AppUnlockState.Locked
            // On API 28-29 the credential fallback must stay an explicit user action. Auto-launching
            // it on every foreground would trap a user who cancels in a prompt loop.
            if (promptKind == UnlockPromptKind.Combined || strongBiometricAvailable()) {
                authenticate()
            }
        } else {
            session.state.value = AppUnlockState.Unlocked
        }
    }

    fun onBackground() {
        if (closed) return
        if (session.wasUnlocked) session.backgroundAt = elapsedRealtime()
    }

    fun authenticate() {
        if (closed) return
        if (!canAuthenticate()) {
            session.state.value = AppUnlockState.DeviceLockRequired
            return
        }
        if (session.promptRequested || session.credentialRequestInFlight) return
        if (promptKind == UnlockPromptKind.StrongBiometricOnly && !strongBiometricAvailable()) {
            requestDeviceCredential()
            return
        }
        session.promptRequested = true
        session.state.value = AppUnlockState.Authenticating
        prompt.authenticate(buildPromptInfo())
    }

    /** Called by the host activity with the result of the keyguard credential prompt. */
    fun onDeviceCredentialResult(succeeded: Boolean) {
        if (closed) return
        session.credentialRequestInFlight = false
        session.promptRequested = false
        if (succeeded) {
            // The keyguard confirmation returned to this Activity, so the session is foregrounded
            // and has not been backgrounded since unlocking. A null timestamp expresses exactly
            // that and keeps the ON_START foreground check from immediately relocking.
            session.wasUnlocked = true
            session.backgroundAt = null
            session.state.value = AppUnlockState.Unlocked
        } else {
            session.wasUnlocked = false
            session.backgroundAt = null
            session.state.value = AppUnlockState.Locked
        }
    }

    fun recheckDeviceLock() {
        if (closed) return
        session.wasUnlocked = false
        session.backgroundAt = null
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
        if (closed) return
        if (!canAuthenticate()) {
            session.state.value = AppUnlockState.DeviceLockRequired
            return
        }
        if (session.credentialRequestInFlight) return
        session.credentialRequestInFlight = true
        session.promptRequested = false
        session.state.value = AppUnlockState.Authenticating
        if (!session.deviceCredentialRequests.tryEmit(Unit)) {
            session.credentialRequestInFlight = false
            session.state.value = AppUnlockState.Locked
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
