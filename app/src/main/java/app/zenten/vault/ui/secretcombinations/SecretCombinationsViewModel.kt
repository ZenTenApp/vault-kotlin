package app.zenten.vault.ui.secretcombinations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.SecretCombinationsViewStep
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.VaultKeySession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared state for the Settings-initiated **Secret Combinations** review flow.
 *
 * The flow lets a user write down the VaultKey's 24 BIP-39 word positions again after setup.
 * It is gated behind the VaultKey passphrase, and once that gate is passed the flow cannot be
 * cancelled: the display and verification steps both block system Back, and [AppStorageService]
 * records the active step so a process restart reopens the unfinished destination.
 *
 * Transcribed values live only in this graph-scoped ViewModel, matching the create wizard, so
 * they never reach a navigation argument or disk. The persisted step marker contains no secrets.
 */
@HiltViewModel
class SecretCombinationsViewModel @Inject constructor(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val accountService: AccountService,
    private val vaultKeySession: VaultKeySession,
) : ViewModel() {
    private val _state = MutableStateFlow(SecretCombinationsState())
    val state: StateFlow<SecretCombinationsState> = _state.asStateFlow()

    /** Verification input survives recomposition and step changes while this graph is alive. */
    val secretCombinationEntries = mutableStateListOf(*Array(24) { "" })

    var activeSecretCombinationSegment by mutableIntStateOf(0)
        private set

    /**
     * Guards the one-time decryption/derivation that produces the displayed values.
     *
     * The VaultKey is parsed with its retained passphrase, so this is expensive enough
     * (bcrypt_pbkdf) that it must run once per graph and off the main thread.
     */
    private var secretCombinationsLoadStarted = false

    /**
     * Verifies the gate passphrase and, on a match, durably enters the flow.
     *
     * Persisting [SecretCombinationsViewStep.SHOW] before reporting success means a process
     * kill immediately after verification still reopens the flow instead of leaking back to
     * Settings. A wrong passphrase reports an inline error and leaves the flow unentered.
     */
    fun submitPassphrase(passphrase: String) {
        if (_state.value.verifying || _state.value.passphraseVerified) return
        _state.value = _state.value.copy(verifying = true, passphraseError = false)
        viewModelScope.launch {
            val verified = accountService.verifyVaultKeyPassphrase(passphrase).isSuccess
            if (verified) {
                // The gate is a real decryption now, so the same passphrase can unlock the
                // session used to derive the displayed combinations.
                vaultKeySession.establish(passphrase.toCharArray())
                appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.SHOW)
                _state.value = _state.value.copy(verifying = false, passphraseVerified = true)
            } else {
                _state.value = _state.value.copy(verifying = false, passphraseError = true)
            }
        }
    }

    /** Drops the passphrase error once the user edits the field. */
    fun clearPassphraseError() {
        if (!_state.value.passphraseError) return
        _state.value = _state.value.copy(passphraseError = false)
    }

    /**
     * Records the display step again when the user goes Back from verification to review the
     * combinations, so a restart during the review reopens the display rather than verification.
     */
    fun startShow(onRecorded: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.SHOW)
            _state.value = _state.value.copy(advancing = false)
            onRecorded()
        }
    }

    /** Records verification as the durable step before the caller navigates there. */
    fun startConfirm(onRecorded: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.CONFIRM)
            _state.value = _state.value.copy(advancing = false)
            onRecorded()
        }
    }

    /**
     * Clears the flow marker and in-memory values once every segment matched.
     *
     * Clearing before the success screen means a later restart is a normal launch: the flow is
     * finished, so it must not be resumed.
     */
    fun completeFlow(onCompleted: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.clearSecretCombinationsViewStep()
            secretCombinationEntries.fill("")
            activeSecretCombinationSegment = 0
            onCompleted()
        }
    }

    /**
     * Clears the flow marker when the stored VaultKey cannot be read.
     *
     * Without this the locked flow would trap the user on an error screen they can neither
     * complete nor leave. There is nothing to transcribe in that case, so returning to Settings
     * is safe; the non-abortable rule applies only while combinations are actually shown.
     */
    fun exitUnavailableFlow(onExited: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.clearSecretCombinationsViewStep()
            onExited()
        }
    }

    /** Derives the 24 BIP-39 positions from the stored VaultKey exactly once per graph. */
    fun loadSecretCombinationIndices() {
        if (secretCombinationsLoadStarted) return
        secretCombinationsLoadStarted = true
        _state.value = _state.value.copy(secretCombinationsLoading = true)
        viewModelScope.launch {
            val indices = withContext(Dispatchers.Default) {
                secureStorage.getVaultKey()?.let { vaultKey ->
                    runCatching {
                        val passphrase = vaultKeySession.snapshot() ?: return@let null
                        try {
                            val seed = SshKeyCodec.parse(vaultKey.zenTenKey, passphrase).seed
                            Seedify.toVaultKeyBackupWordIndices(seed)
                        } finally {
                            passphrase.fill('\u0000')
                        }
                    }.getOrNull()
                }
            }
            _state.value = _state.value.copy(
                secretCombinationIndices = indices,
                secretCombinationsLoading = false,
            )
        }
    }

    fun updateSecretCombinationEntry(index: Int, value: String) {
        secretCombinationEntries[index] = value
    }

    fun advanceSecretCombinationEntry() {
        if (activeSecretCombinationSegment < secretCombinationEntries.lastIndex) {
            activeSecretCombinationSegment++
        }
    }
}

/** UI-visible state of the **Secret Combinations** review flow; never holds secret material. */
data class SecretCombinationsState(
    /** True while the gate passphrase is being checked. */
    val verifying: Boolean = false,
    /** True once the passphrase matched; the caller then advances into the locked flow. */
    val passphraseVerified: Boolean = false,
    /** True when the entered passphrase did not match the stored VaultKey. */
    val passphraseError: Boolean = false,
    /** True while the displayed positions are being derived from the stored VaultKey. */
    val secretCombinationsLoading: Boolean = true,
    /** Cached only in memory; null after loading means derivation failed. */
    val secretCombinationIndices: List<Int>? = null,
    /** True while a durable step transition or the final clear is in flight. */
    val advancing: Boolean = false,
)