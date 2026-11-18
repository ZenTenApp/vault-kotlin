package app.zenten.vault.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Key
import app.zenten.vault.domain.KeyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class LocalKeyBackupViewModel @Inject constructor(
    private val keyService: KeyService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalKeyBackupState())
    val state: StateFlow<LocalKeyBackupState> = _state.asStateFlow()

    fun load(keyId: Long) {
        if (_state.value.keyId == keyId) return
        _state.value = LocalKeyBackupState(keyId = keyId, key = keyService.getKey(keyId))
    }

    /**
     * Marks the key's mandatory local backup as complete. Called from the Confirm Backup
     * screen, which is only reachable after the export target accepted the key, so no
     * separate export flag is needed.
     */
    fun complete() {
        val keyId = _state.value.keyId ?: return
        if (_state.value.completing) return
        _state.value = _state.value.copy(completing = true)
        viewModelScope.launch {
            appStorage.markKeyLocallyBackedUp(keyId)
            _state.value = _state.value.copy(completed = true)
        }
    }

    /**
     * Validates the pasted export and compares its normalized key material with the stored
     * key. This proves the user kept a readable copy before the mandatory export step
     * can complete, mirroring the VaultKey Confirm Backup check.
     */
    fun confirmPastedKey(pastedKey: String): KeyBackupConfirmationResult {
        val key = _state.value.key ?: return KeyBackupConfirmationResult.UNAVAILABLE
        val normalized = runCatching {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
        }.getOrElse { error ->
            return (error as? SshKeyCodec.UnsupportedKdfRoundsException)
                ?.let { KeyBackupConfirmationResult.UnsupportedKdfRounds(it.declaredRounds) }
                ?: KeyBackupConfirmationResult.INVALID
        }
        val stored = runCatching {
            SshKeyCodec.normalizeToSingleLineBase64(key.zenTenKey)
        }.getOrNull() ?: return KeyBackupConfirmationResult.UNAVAILABLE
        // Compare with a constant-time equality check: both sides are key material, and the
        // same pattern is used for MAC verification elsewhere in the vault.
        return if (MessageDigest.isEqual(normalized.toByteArray(), stored.toByteArray())) {
            KeyBackupConfirmationResult.MATCHED
        } else {
            KeyBackupConfirmationResult.MISMATCHED
        }
    }
}

/**
 * Outcome of comparing a pasted key export against the stored encrypted key.
 *
 * Sealed rather than an enum so an out-of-band work factor can carry its declared round count,
 * which is public container metadata, to the screen that explains the refusal.
 */
sealed interface KeyBackupConfirmationResult {
    data object MATCHED : KeyBackupConfirmationResult
    data object INVALID : KeyBackupConfirmationResult
    data object MISMATCHED : KeyBackupConfirmationResult
    data object UNAVAILABLE : KeyBackupConfirmationResult

    /** A valid container whose declared bcrypt work factor is outside the accepted band. */
    data class UnsupportedKdfRounds(val declaredRounds: Long) : KeyBackupConfirmationResult
}

data class LocalKeyBackupState(
    val keyId: Long? = null,
    val key: Key? = null,
    val completing: Boolean = false,
    val completed: Boolean = false,
)
