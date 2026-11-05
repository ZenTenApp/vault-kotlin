package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class LocalAccountBackupViewModel @Inject constructor(
    private val accountService: AccountService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalAccountBackupState())
    val state: StateFlow<LocalAccountBackupState> = _state.asStateFlow()

    fun load(accountId: Long) {
        if (_state.value.accountId == accountId) return
        _state.value = LocalAccountBackupState(accountId = accountId, account = accountService.getAccount(accountId))
    }

    /**
     * Marks the account's mandatory local backup as complete. Called from the Confirm Backup
     * screen, which is only reachable after the export target accepted the account key, so no
     * separate export flag is needed.
     */
    fun complete() {
        val accountId = _state.value.accountId ?: return
        if (_state.value.completing) return
        _state.value = _state.value.copy(completing = true)
        viewModelScope.launch {
            appStorage.markAccountLocallyBackedUp(accountId)
            _state.value = _state.value.copy(completed = true)
        }
    }

    /**
     * Validates the pasted export and compares its normalized key material with the stored
     * account. This proves the user kept a readable copy before the mandatory export step
     * can complete, mirroring the VaultKey Confirm Backup check.
     */
    fun confirmPastedAccountKey(pastedKey: String): AccountBackupConfirmationResult {
        val account = _state.value.account ?: return AccountBackupConfirmationResult.UNAVAILABLE
        val normalized = runCatching {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
        }.getOrElse { return AccountBackupConfirmationResult.INVALID }
        val stored = runCatching {
            SshKeyCodec.normalizeToSingleLineBase64(account.zenTenKey)
        }.getOrNull() ?: return AccountBackupConfirmationResult.UNAVAILABLE
        // Compare with a constant-time equality check: both sides are key material, and the
        // same pattern is used for MAC verification elsewhere in the vault.
        return if (MessageDigest.isEqual(normalized.toByteArray(), stored.toByteArray())) {
            AccountBackupConfirmationResult.MATCHED
        } else {
            AccountBackupConfirmationResult.MISMATCHED
        }
    }
}

/** Outcome of comparing a pasted account export against the stored encrypted key. */
enum class AccountBackupConfirmationResult {
    MATCHED,
    INVALID,
    MISMATCHED,
    UNAVAILABLE,
}

data class LocalAccountBackupState(
    val accountId: Long? = null,
    val account: Account? = null,
    val completing: Boolean = false,
    val completed: Boolean = false,
)
