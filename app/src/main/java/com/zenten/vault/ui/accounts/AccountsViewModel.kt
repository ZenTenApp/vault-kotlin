package com.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenten.vault.data.model.Account
import com.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(private val accountService: AccountService) : ViewModel() {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = AccountsState(accounts = accountService.getAccounts())
        }
    }

    fun delete(id: Long) {
        accountService.deleteSubAccount(id)
        refresh()
    }

    fun reset(onComplete: () -> Unit) = viewModelScope.launch {
        accountService.resetVault()
        onComplete()
    }
}

data class AccountsState(val loading: Boolean = false, val accounts: List<Account> = emptyList())
