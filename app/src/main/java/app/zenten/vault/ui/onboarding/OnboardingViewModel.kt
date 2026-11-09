package app.zenten.vault.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.SetupService
import app.zenten.vault.domain.VaultKeyPersistedException
import app.zenten.vault.domain.VaultKeySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val setupService: SetupService,
    private val vaultKeySession: VaultKeySession,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

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

    fun performSetup(keyInput: String, passphrase: String) {
        if (_state.value.inProgress) return
        _state.value = OnboardingState(inProgress = true, stage = "Validating SSH key…")
        viewModelScope.launch {
            runCatching {
                // Parsing stays outside the service call, so a wrong key or passphrase fails here
                // before anything is persisted and must not offer the stored-key retry.
                val key = SshKeyCodec.normalizeToSingleLineBase64(keyInput)
                // Parsing proves the passphrase before anything is persisted; the identity the
                // backup step needs is derived later from the same seed, never stored here.
                SshKeyCodec.parse(key, passphrase.toCharArray())
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
