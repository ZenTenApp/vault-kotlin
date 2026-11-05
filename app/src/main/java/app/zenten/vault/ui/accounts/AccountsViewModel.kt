package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountService: AccountService,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accountService.accountUpdates.collect { accounts ->
                _state.update { it.copy(loading = false, accounts = accounts) }
            }
        }
        refresh()
    }

    /** Reloads the local inventory; names come with the stored accounts, so no network is needed. */
    fun refresh() {
        if (_state.value.accounts.isEmpty()) _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = AccountsState(loading = false, accounts = accountService.getAccounts())
        }
    }
}

data class AccountsState(
    val loading: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val accounts: List<Account> = emptyList(),
)