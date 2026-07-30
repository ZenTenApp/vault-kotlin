package app.zenten.vault.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.domain.SetupService
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
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun performSetup(keyInput: String, passphrase: String) {
        if (_state.value.inProgress) return
        _state.value = OnboardingState(inProgress = true, stage = "Validating SSH key…")
        viewModelScope.launch {
            runCatching {
                val key = SshKeyCodec.normalizeToSingleLineBase64(keyInput)
                _state.value = _state.value.copy(stage = "Deriving recovery identity…")
                val parsed = SshKeyCodec.parse(key, passphrase.toCharArray())
                val mnemonic = Seedify.toMnemonic(parsed.seed)
                val nostr = NostrKeyDerivation.derive(mnemonic)
                val createdAt = clock.millis() * 1_000L
                val account = Account(
                    createdAt = createdAt,
                    zenTenKey = key,
                    zenTenPass = passphrase,
                    pubkey = SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
                    mnemonic = mnemonic,
                    nostr = NostrKeyPair(nostr.publicKey.hex(), nostr.privateKey.hex()),
                )
                _state.value = _state.value.copy(stage = "Backing up master account…")
                setupService.performSetup(account).getOrThrow()
            }.onSuccess {
                _state.value = OnboardingState(completed = true, stage = "Setup complete")
            }.onFailure { error ->
                _state.value = OnboardingState(error = error.message ?: "Setup failed. Please try again.")
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
)

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
