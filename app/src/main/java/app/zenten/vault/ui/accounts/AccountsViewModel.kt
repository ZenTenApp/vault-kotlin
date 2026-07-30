package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.SetupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountService: AccountService,
    private val setupService: SetupService,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()
    private var recoveryMessageDismissal: Job? = null

    init {
        refresh()
        recoverFromBackup()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = false, accounts = accountService.getAccounts())
        }
    }

    fun recoverFromBackup() {
        if (_state.value.recoveryInProgress) return
        recoveryMessageDismissal?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(recoveryInProgress = true, recoveryMessage = "Connecting to backup relays…")
            setupService.recoverExistingAccounts()
                .onSuccess { result ->
                    _state.value = _state.value.copy(recoveryInProgress = false, recoveryMessage = null)
                    result.recovered.takeIf { it.isNotEmpty() }?.let { recovered ->
                        showRecoveryMessage("Recovered ${recovered.size} account(s).")
                    }
                    refresh()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(recoveryInProgress = false, recoveryMessage = null)
                    showRecoveryMessage("Recovery failed: ${error.message ?: "Unable to query backup relays."}")
                }
        }
    }

    private fun showRecoveryMessage(message: String) {
        recoveryMessageDismissal?.cancel()
        _state.value = _state.value.copy(recoveryMessage = message)
        recoveryMessageDismissal = viewModelScope.launch {
            delay(RECOVERY_MESSAGE_DURATION_MS)
            _state.value = _state.value.copy(recoveryMessage = null)
        }
    }

    private companion object {
        const val RECOVERY_MESSAGE_DURATION_MS = 5_000L
    }
}

data class AccountsState(
    val loading: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val accounts: List<Account> = emptyList(),
)
