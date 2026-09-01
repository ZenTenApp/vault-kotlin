package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CachedAccountProfile
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.AccountProfile
import app.zenten.vault.domain.ProfileService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountService: AccountService,
    private val appStorage: AppStorageService,
    private val profileService: ProfileService,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()
    private val profileRefreshStarted = mutableSetOf<Long>()

    init { refresh() }

    fun refresh() {
        if (_state.value.accounts.isEmpty()) _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val accounts = accountService.getAccounts()
            val cachedProfiles = appStorage.getCachedAccountProfiles()
            val accountIds = accounts.mapTo(mutableSetOf()) { it.createdAt }
            val profilesFromCache = cachedProfiles.filter { it.accountId in accountIds }.associate { cached ->
                cached.accountId to AccountProfile(cached.displayName, cached.bannerUrl, cached.createdAtSeconds ?: 0L)
            }
            _state.value = AccountsState(loading = false, accounts = accounts, profiles = profilesFromCache)

            val accountsToRefresh = accounts.filter { account ->
                profileRefreshStarted.add(account.createdAt)
            }
            val fetchedProfiles = coroutineScope {
                accountsToRefresh.map { account ->
                    async { account.createdAt to profileService.fetchAssociatedAccountProfile(account) }
                }.awaitAll()
            }
            val cachedByAccountId = cachedProfiles.associateBy { it.accountId }
            val newerProfiles = fetchedProfiles.mapNotNull { (accountId, profile) ->
                profile?.takeIf { it.createdAtSeconds > (cachedByAccountId[accountId]?.createdAtSeconds ?: Long.MIN_VALUE) }
                    ?.let { accountId to it }
            }
            val updatedCache = newerProfiles.map { (accountId, profile) ->
                CachedAccountProfile(accountId, profile.displayName, profile.bannerUrl, profile.createdAtSeconds)
            } + fetchedProfiles.filter { (accountId, profile) ->
                profile == null && accountId !in cachedByAccountId
            }.map { (accountId, _) -> CachedAccountProfile(accountId) }
            if (updatedCache.isNotEmpty()) appStorage.upsertCachedAccountProfiles(updatedCache)
            val refreshedProfiles = newerProfiles.toMap()
            _state.value = _state.value.copy(profiles = _state.value.profiles + refreshedProfiles)
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
