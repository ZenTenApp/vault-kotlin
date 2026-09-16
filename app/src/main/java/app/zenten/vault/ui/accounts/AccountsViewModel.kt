package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.AccountProfile
import app.zenten.vault.domain.AccountProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountService: AccountService,
    private val profileRepository: AccountProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                val accountIds = _state.value.accounts.mapTo(mutableSetOf()) { it.createdAt }
                _state.update { it.copy(profiles = profiles.filterKeys(accountIds::contains)) }
            }
        }
        viewModelScope.launch {
            accountService.accountUpdates.collect { accounts ->
                val accountIds = accounts.mapTo(mutableSetOf()) { it.createdAt }
                val profiles = profileRepository.profiles.value.filterKeys(accountIds::contains)
                _state.update { it.copy(loading = false, accounts = accounts, profiles = profiles) }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.accounts.isEmpty()) _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val accounts = accountService.getAccounts()
            profileRepository.loadCachedProfiles()
            val accountIds = accounts.mapTo(mutableSetOf()) { it.createdAt }
            val profilesFromCache = profileRepository.profiles.value.filterKeys(accountIds::contains)
            _state.value = AccountsState(loading = false, accounts = accounts, profiles = profilesFromCache)
            profileRepository.refresh(accounts)
        }
    }
}

data class AccountsState(
    val loading: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val accounts: List<Account> = emptyList(),
    val profiles: Map<Long, AccountProfile> = emptyMap(),
)
