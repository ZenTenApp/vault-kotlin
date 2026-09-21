package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateMasterKeyStep
import app.zenten.vault.data.secure.SecureStorageService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles durable progress and secret-safe validation for the MasterKey backup steps. */
@HiltViewModel
class CreateMasterKeyBackupViewModel @Inject constructor(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateMasterKeyBackupState())
    val state: StateFlow<CreateMasterKeyBackupState> = _state.asStateFlow()

    /** Matches associated-account local backup: raw single-line base64, never PEM text. */
    fun masterKeyForExport(): String? = secureStorage.getMasterAccount()?.zenTenKey?.let { stored ->
        runCatching { SshKeyCodec.normalizeToSingleLineBase64(stored) }.getOrNull()
    }

    /** Marks confirmation as resumable only after Android accepted the targeted export intent. */
    fun recordExportStarted() {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setCreateMasterKeyStep(CreateMasterKeyStep.CONFIRM_BACKUP)
            _state.value = _state.value.copy(advancing = false, navigateToConfirmation = true)
        }
    }

    fun onConfirmationShown() {
        if (_state.value.navigateToConfirmation) {
            _state.value = _state.value.copy(navigateToConfirmation = false)
        }
    }

    /** Validates and compares normalized key material without retaining clipboard text in UI state. */
    fun confirmPastedMasterKey(pastedKey: String): BackupConfirmationResult {
        val normalized = runCatching {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
        }.getOrElse { return BackupConfirmationResult.INVALID }
        val stored = secureStorage.getMasterAccount()?.zenTenKey
            ?.let { runCatching { SshKeyCodec.normalizeToSingleLineBase64(it) }.getOrNull() }
            ?: return BackupConfirmationResult.UNAVAILABLE
        return if (normalized == stored) BackupConfirmationResult.MATCHED else BackupConfirmationResult.MISMATCHED
    }
}

data class CreateMasterKeyBackupState(
    val advancing: Boolean = false,
    val navigateToConfirmation: Boolean = false,
)

enum class BackupConfirmationResult {
    MATCHED,
    INVALID,
    MISMATCHED,
    UNAVAILABLE,
}
