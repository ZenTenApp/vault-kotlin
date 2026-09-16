package app.zenten.vault.ui.newaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewAccountViewModel @Inject constructor(
    private val accountService: AccountService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(NewAccountState())
    val state: StateFlow<NewAccountState> = _state.asStateFlow()

    fun verifyPassphrase(masterPassphrase: String) {
        accountService.verifyMasterPassphrase(masterPassphrase)
            .onSuccess { _state.value = NewAccountState(passphraseVerified = true) }
            .onFailure { _state.value = NewAccountState(error = it.message ?: "Could not verify passphrase") }
    }

    fun create(masterPassphrase: String, accountName: String) {
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            accountService.createAssociatedAccount(masterPassphrase, accountName)
                .onSuccess {
                    appStorage.requireAccountLocalBackup(it.createdAt)
                    _state.value = NewAccountState(created = it)
                }
                .onFailure {
                    _state.value = NewAccountState(
                        passphraseVerified = true,
                        error = it.message ?: "Could not create account",
                    )
                }
        }
    }
}

data class NewAccountState(
    val passphraseVerified: Boolean = false,
    val creating: Boolean = false,
    val created: Account? = null,
    val error: String? = null,
)
