package app.zenten.vault.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.SetupService
import app.zenten.vault.domain.VaultKeyPersistedException
import app.zenten.vault.domain.VaultKeySession
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val setupService: SetupService,
    private val vaultKeySession: VaultKeySession,
    private val clock: Clock,
    private val passphraseStrengthChecker: PassphraseStrengthChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Feedback for the passphrase typed on the import step.
     *
     * Held apart from [OnboardingState] because strength measurement arrives asynchronously and
     * must not disturb the setup progress/error state it sits beside.
     */
    private val _passphraseFeedback = MutableStateFlow(PassphraseFeedback())
    val passphraseFeedback: StateFlow<PassphraseFeedback> = _passphraseFeedback.asStateFlow()

    private val passphraseMeter = OnboardingPassphraseMeter(viewModelScope) { measured ->
        _passphraseFeedback.value = _passphraseFeedback.value.copy(measured = measured)
    }

    init {
        // A launch that resumed a stored, still-unbacked imported Vault Key routes here without
        // the passphrase of the previous session (it is never persisted), so the retry action
        // must be visible before anything has failed in this process.
        viewModelScope.launch {
            val pending = setupService.hasPendingImportedVaultKeyBackup()
            if (pending && !_state.value.inProgress && !_state.value.completed) {
                _state.value = _state.value.copy(canRetryStoredVaultKey = true)
            }
        }
    }

    /**
     * Schedules the debounced strength preview for the passphrase being typed.
     *
     * The preview never unlocks the submit on its own; [performSetup] re-measures and enforces
     * the policy, so acceptance cannot depend on how quickly the preview caught up.
     */
    fun onPassphraseChanged(passphrase: String) {
        _passphraseFeedback.value = _passphraseFeedback.value.copy(rejectedAsWeak = false)
        passphraseMeter.onChanged(passphrase, passphraseStrengthChecker)
    }

    /** Drops the cached measurement when the passphrase step leaves the screen. */
    fun clearPassphraseFeedback() {
        passphraseMeter.clear()
        _passphraseFeedback.value = PassphraseFeedback()
    }

    fun performSetup(keyInput: String, passphrase: String) {
        if (_state.value.inProgress) return
        _state.value = OnboardingState(inProgress = true, stage = "Validating SSH key…")
        viewModelScope.launch {
            // Measure before touching the key so a weak passphrase is refused before any
            // bcrypt work or persistence, and so the refusal can show its explanation.
            val measured = passphraseStrengthChecker.measure(passphrase)
            _passphraseFeedback.value = PassphraseFeedback(
                measured = MeasuredPassphrase(measured),
            )
            if (!VaultKeyPassphrasePolicy.isValid(passphrase, measured)) {
                // Parsing would succeed here, so the refusal is reported as a passphrase problem
                // rather than a wrong key: the imported key stays on the step for a retry.
                _passphraseFeedback.value = PassphraseFeedback(
                    measured = MeasuredPassphrase(measured),
                    rejectedAsWeak = true,
                )
                _state.value = OnboardingState()
                return@launch
            }
            performSetupAfterPassphraseCheck(keyInput, passphrase)
        }
    }

    /**
     * Runs the import once the passphrase has cleared the policy.
     *
     * Suspends rather than launching, so the caller's pending state and the strength refusal
     * above are all part of the same [viewModelScope] launch and a test can observe them in order.
     */
    internal suspend fun performSetupAfterPassphraseCheck(keyInput: String, passphrase: String) {
        runCatching {
            // Parsing stays outside the service call, so a wrong key or passphrase fails here
            // before anything is persisted and must not offer the stored-key retry.
            val key = SshKeyCodec.normalizeToSingleLineBase64(keyInput)
            // Parsing proves the passphrase before anything is persisted; the identity the
            // backup step needs is derived later from the same seed, never stored here.
            // Parsing decrypts with bcrypt_pbkdf, which can take seconds at high work factors,
            // so keep it off the Main looper.
            withContext(Dispatchers.Default) {
                SshKeyCodec.parse(key, passphrase.toCharArray())
            }
            val createdAt = clock.millis() * 1_000L
            // Only the encrypted container is stored: every derived value (Nostr identity,
            // Sync code) is rebuilt from the seed by the flow that needs it.
            val account = Account(
                createdAt = createdAt,
                zenTenKey = key,
            )
            // The passphrase was just used to parse the key, so remember it in the session
            // (never on disk) before backing up and any retry needs it.
            vaultKeySession.establish(passphrase.toCharArray())
            _state.value = _state.value.copy(stage = "Backing up VaultKey account…")
            setupService.performSetup(account).getOrThrow()
        }.onSuccess {
            _state.value = OnboardingState(completed = true, stage = "Setup complete")
        }.onFailure { error ->
            // Only a post-persistence failure can be resumed from the stored key; the
            // service signals that with VaultKeyPersistedException.
            val retryStoredKey = error is VaultKeyPersistedException
            _state.value = OnboardingState(
                error = error.message ?: "Setup failed. Please try again.",
                canRetryStoredVaultKey = retryStoredKey,
            )
        }
    }

    fun retryStoredVaultKey() {
        if (_state.value.inProgress) return
        _state.value = OnboardingState(inProgress = true, stage = "Retrying vaultKey backup…", canRetryStoredVaultKey = true)
        viewModelScope.launch {
            setupService.retryVaultKeyBackup()
                .onSuccess { _state.value = OnboardingState(completed = true, stage = "Setup complete") }
                .onFailure { error ->
                    _state.value = OnboardingState(
                        error = error.message ?: "Backup quorum is unavailable.",
                        canRetryStoredVaultKey = true,
                    )
                }
        }
    }

    fun clearError() { _state.value = OnboardingState() }
}

data class OnboardingState(
    val inProgress: Boolean = false,
    val completed: Boolean = false,
    val stage: String? = null,
    val error: String? = null,
    /** True when the imported key is stored locally and only its remote backup is missing. */
    val canRetryStoredVaultKey: Boolean = false,
)

/**
 * Passphrase-step feedback for the import and recovery screens.
 *
 * [measured] carries the estimator's projection so the meter and its sheet work; it therefore
 * holds passphrase fragments while the step is on screen. It is never persisted or logged and is
 * dropped when the step leaves.
 */
data class PassphraseFeedback(
    val measured: MeasuredPassphrase? = null,
    /** True when a submit was refused because the passphrase failed the strength policy. */
    val rejectedAsWeak: Boolean = false,
)
