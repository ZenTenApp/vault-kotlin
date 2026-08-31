package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.ProfileService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalAccountBackupViewModel @Inject constructor(
    private val accountService: AccountService,
    private val appStorage: AppStorageService,
    private val profileService: ProfileService,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalAccountBackupState())
    val state: StateFlow<LocalAccountBackupState> = _state.asStateFlow()

    fun load(accountId: Long) {
        if (_state.value.accountId == accountId) return
        _state.value = LocalAccountBackupState(accountId = accountId, account = accountService.getAccount(accountId))
    }

    fun complete() {
        val accountId = _state.value.accountId ?: return
        if (!_state.value.exportStarted || _state.value.completing) return
        _state.value = _state.value.copy(completing = true)
        viewModelScope.launch {
            appStorage.markAccountLocallyBackedUp(accountId)
            val account = _state.value.account
            if (account != null) runCatching { profileService.publishAssociatedAccountProfile(account) }
            _state.value = _state.value.copy(completed = true)
        }
    }

    fun recordExportStarted() {
        _state.value = _state.value.copy(exportStarted = true)
    }
}

data class LocalAccountBackupState(
    val accountId: Long? = null,
    val account: Account? = null,
    val exportStarted: Boolean = false,
    val completing: Boolean = false,
    val completed: Boolean = false,
)
