package app.zenten.vault.ui.onboarding.recovervaultkey

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.bravesync.auth.BraveSyncAuth
import app.zenten.vault.domain.VaultKeyRecoveryService
import app.zenten.vault.ui.onboarding.MeasuredPassphrase
import app.zenten.vault.ui.onboarding.OnboardingPassphraseMeter
import app.zenten.vault.ui.onboarding.PassphraseFeedback
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy
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
    private val passphraseStrengthChecker: PassphraseStrengthChecker,
) : ViewModel() {
    val entries = mutableStateListOf(*Array(24) { "" })

    private val _state = MutableStateFlow(RecoverVaultKeyState())
    val state: StateFlow<RecoverVaultKeyState> = _state.asStateFlow()

    /** Live strength feedback for the passphrase step, kept apart from the recovery state. */
    private val _passphraseFeedback = MutableStateFlow(PassphraseFeedback())
    val passphraseFeedback: StateFlow<PassphraseFeedback> = _passphraseFeedback.asStateFlow()

    private val passphraseMeter = OnboardingPassphraseMeter(viewModelScope) { measured ->
        _passphraseFeedback.value = _passphraseFeedback.value.copy(measured = measured)
    }

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

    /** Schedules the debounced strength preview for the passphrase being typed. */
    fun onPassphraseChanged(passphrase: String) {
        _passphraseFeedback.value = _passphraseFeedback.value.copy(rejectedAsWeak = false)
        passphraseMeter.onChanged(passphrase, passphraseStrengthChecker)
    }

    /** Drops the cached measurement when the passphrase step leaves the screen. */
    fun clearPassphraseFeedback() {
        passphraseMeter.clear()
        _passphraseFeedback.value = PassphraseFeedback()
    }

    fun recover(passphrase: String) {
        if (_state.value.inProgress || passphrase.isBlank()) return
        _state.value = RecoverVaultKeyState(inProgress = true)
        viewModelScope.launch {
            // A passphrase the user did not choose is still the only secret protecting a remote
            // copy that leaves the device, so recovery applies the same rules as creation. It is
            // measured before any source lookup so a refusal costs no network or bcrypt work.
            val measured = passphraseStrengthChecker.measure(passphrase)
            _passphraseFeedback.value = PassphraseFeedback(
                measured = MeasuredPassphrase(measured),
            )
            if (!VaultKeyPassphrasePolicy.isValid(passphrase, measured)) {
                _state.value = RecoverVaultKeyState()
                _passphraseFeedback.value = PassphraseFeedback(
                    measured = MeasuredPassphrase(measured),
                    rejectedAsWeak = true,
                )
                return@launch
            }
            recoverAfterPassphraseCheck(passphrase)
        }
    }

    /** Runs the recovery once the passphrase has cleared the policy; separated so it is testable. */
    internal suspend fun recoverAfterPassphraseCheck(passphrase: String) {
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
