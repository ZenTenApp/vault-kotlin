package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.Seedify
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

    /** Makes Secret Combinations the durable resume point after key verification succeeds. */
    fun recordSecretCombinationsShown() {
        viewModelScope.launch {
            appStorage.setCreateMasterKeyStep(CreateMasterKeyStep.SECRET_COMBINATIONS)
        }
    }

    /** Makes the celebration screen resumable before leaving recovery-code verification. */
    fun recordSecretCombinationsVerified(onRecorded: () -> Unit) {
        viewModelScope.launch {
            appStorage.setCreateMasterKeyStep(CreateMasterKeyStep.CONGRATULATIONS)
            onRecorded()
        }
    }

    /** Finishes onboarding before opening Accounts so app restart follows the same destination. */
    fun completeGeneratedMasterKeySetup(onCompleted: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setSetupComplete()
            _state.value = _state.value.copy(advancing = false)
            onCompleted()
        }
    }

    /** Returns non-secret word-list positions for the Brave recovery mnemonic shown by the UI. */
    fun secretCombinationIndices(): List<Int>? = secureStorage.getMasterAccount()?.let { master ->
        runCatching {
            val passphrase = requireNotNull(master.zenTenPass) { "Master passphrase is unavailable" }
            val seed = SshKeyCodec.parse(master.zenTenKey, passphrase.toCharArray()).seed
            Seedify.toBraveSyncWordIndices(seed)
        }.getOrNull()
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
