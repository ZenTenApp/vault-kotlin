package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.bravesync.auth.BraveSyncAuth
import app.zenten.vault.domain.VaultKeyRecoveryService
import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecoverVaultKeyViewModel @Inject constructor(
    private val recoveryService: VaultKeyRecoveryService,
) : ViewModel() {
    val entries = mutableStateListOf(*Array(24) { "" })

    private val _state = MutableStateFlow(RecoverVaultKeyState())
    val state: StateFlow<RecoverVaultKeyState> = _state.asStateFlow()

    fun updateEntry(index: Int, value: String) {
        entries[index] = value
        _state.value = _state.value.copy(error = null, showFieldErrors = false)
    }

    /** Validates every field together because recovery has no locally known expected sequence. */
    fun validateCombinations(): Boolean {
        val values = entries.map(String::toIntOrNull)
        if (values.any { it == null || it !in 1..Bip39Mnemonics.englishWordlist.size }) {
            _state.value = _state.value.copy(
                error = "Enter a number from 1 to ${Bip39Mnemonics.englishWordlist.size} in every box.",
                showFieldErrors = true,
            )
            return false
        }
        return runCatching {
            val words = values.map { Bip39Mnemonics.englishWordlist[requireNotNull(it) - 1] }
            // A valid checksum proves structural validity, but remote lookup proves ownership.
            BraveSyncAuth.mnemonicEntropy(words.joinToString(" "))
        }.fold(
            onSuccess = {
                _state.value = _state.value.copy(error = null, showFieldErrors = false)
                true
            },
            onFailure = {
                _state.value = _state.value.copy(
                    error = "These Secret Combinations are not valid. Check every value and try again.",
                    showFieldErrors = false,
                )
                false
            },
        )
    }

    fun recover(passphrase: String) {
        if (_state.value.inProgress || passphrase.isBlank()) return
        _state.value = RecoverVaultKeyState(inProgress = true)
        viewModelScope.launch {
            recoveryService.recover(entries.map(String::toInt), passphrase)
                .onSuccess { _state.value = RecoverVaultKeyState(completed = true) }
                .onFailure { error ->
                    val message = when (error) {
                        is java.io.IOException -> "Vault could not reach a backup source. Check your connection and try again."
                        is IllegalArgumentException -> error.message ?: "The Secret Combinations are invalid."
                        else -> "Unable to recover VaultKey. Check your Secret Combinations and passphrase, then try again."
                    }
                    _state.value = RecoverVaultKeyState(error = message)
                }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

data class RecoverVaultKeyState(
    val inProgress: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
    val showFieldErrors: Boolean = false,
)
