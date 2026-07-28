package com.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenten.vault.data.model.Account
import com.zenten.vault.domain.AccountService
import com.zenten.vault.domain.SetupService
import dagger.hilt.android.lifecycle.HiltViewModel
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
        viewModelScope.launch {
            _state.value = _state.value.copy(recoveryInProgress = true, recoveryMessage = "Connecting to backup relays…")
            setupService.recoverExistingAccounts()
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        recoveryInProgress = false,
                        recoveryMessage = when {
                            result.recovered.isNotEmpty() -> "Recovered ${result.recovered.size} account(s)."
                            result.discoveredEventCount == 0 -> "No backup events found on the configured relays."
                            else -> "Found ${result.discoveredEventCount} backup event(s), but none could be decrypted or validated."
                        },
                    )
                    refresh()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        recoveryInProgress = false,
                        recoveryMessage = "Recovery failed: ${error.message ?: "Unable to query backup relays."}",
                    )
                }
        }
    }
}

data class AccountsState(
    val loading: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val accounts: List<Account> = emptyList(),
)
