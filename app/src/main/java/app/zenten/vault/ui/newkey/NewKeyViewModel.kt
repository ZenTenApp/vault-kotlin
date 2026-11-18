package app.zenten.vault.ui.newkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Key
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.domain.KeyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewKeyViewModel @Inject constructor(
    private val keyService: KeyService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(NewKeyState())
    val state: StateFlow<NewKeyState> = _state.asStateFlow()

    /** Guards a running check so double taps cannot start parallel bcrypt derivations. */
    fun verifyPassphrase(vaultKeyPassphrase: String) {
        if (_state.value.verifying) return
        _state.value = _state.value.copy(verifying = true, error = null)
        viewModelScope.launch {
            // The verifier dispatches the multi-second bcrypt work to Dispatchers.Default, so
            // this launch can stay on Main and report the outcome safely to the state flow.
            keyService.verifyVaultKeyPassphrase(vaultKeyPassphrase)
                .onSuccess { _state.value = NewKeyState(passphraseVerified = true) }
                .onFailure { _state.value = NewKeyState(error = it.message ?: "Could not verify passphrase") }
        }
    }

    fun create(vaultKeyPassphrase: String, keyName: String) {
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            keyService.createAssociatedKey(vaultKeyPassphrase, keyName)
                .onSuccess {
                    appStorage.requireKeyLocalBackup(it.createdAt)
                    _state.value = NewKeyState(created = it)
                }
                .onFailure {
                    _state.value = NewKeyState(
                        passphraseVerified = true,
                        error = it.message ?: "Could not create key",
                    )
                }
        }
    }
}

data class NewKeyState(
    val passphraseVerified: Boolean = false,
    /** True while the VaultKey passphrase is being checked; the field and action stay inert. */
    val verifying: Boolean = false,
    val creating: Boolean = false,
    val created: Key? = null,
    val error: String? = null,
)
