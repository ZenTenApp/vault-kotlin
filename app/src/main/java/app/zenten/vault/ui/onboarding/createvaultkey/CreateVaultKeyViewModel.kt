package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.SetupService
import app.zenten.vault.ui.accounts.LocalAccountBackupShare
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/**
 * Shared state for the multi-screen **Create VaultKey** wizard.
 *
 * Scoped to the wizard's navigation graph (see [rememberCreateVaultKeyViewModel])
 * rather than to a single screen, because the passphrase is typed on step 2 and
 * has to be compared against the re-entry typed on step 3. Passing it through a
 * navigation argument was deliberately avoided: route arguments are retained in
 * back-stack state and can be saved and restored by the system, which would put
 * the passphrase on disk.
 *
 * The passphrase lives in memory only until verification. After generation, the
 * complete account is committed to keystore-backed storage before navigation and
 * backup is attempted on every enabled source. Durable wizard progress then lets
 * app launch resume at the first unfinished post-generation step.
 */
@HiltViewModel
class CreateVaultKeyViewModel @Inject constructor(
    private val clock: Clock,
    private val setupService: SetupService,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
) : ViewModel() {
    /**
     * Passphrase chosen on step 2. Kept out of [state] so it can never be read
     * back by a composable or leak into a UI snapshot/log of the state object.
     */
    private var chosenPassphrase: String? = null

    private var secretCombinationsLoadStarted = false

    /** Verification input survives Back navigation while this wizard graph remains active. */
    val secretCombinationEntries = mutableStateListOf(*Array(24) { "" })

    var activeSecretCombinationSegment by mutableIntStateOf(0)
        private set

    private val _state = MutableStateFlow(CreateVaultKeyState())
    val state: StateFlow<CreateVaultKeyState> = _state.asStateFlow()

    /**
     * Records the passphrase chosen on step 2.
     *
     * Any previously generated key is discarded: if the user walked back and
     * changed the passphrase, a key encrypted with the old one must not survive.
     */
    fun choosePassphrase(passphrase: String): Boolean {
        if (!VaultKeyPassphrasePolicy.isValid(passphrase)) {
            // Do not leave an earlier valid secret available if a later choice is rejected.
            chosenPassphrase = null
            _state.value = CreateVaultKeyState()
            return false
        }
        chosenPassphrase = passphrase
        _state.value = CreateVaultKeyState()
        return true
    }

    /**
     * Compares the step-3 re-entry with the chosen passphrase and, on a match,
     * generates the VaultKey encrypted with that passphrase.
     *
     * A mismatch is reported through [CreateVaultKeyState.mismatch] and nothing
     * is generated, so a typo in an unrecoverable secret cannot be baked into a
     * key. Generation runs off the main thread because the configured bcrypt_pbkdf
     * work factor can make key derivation intentionally slow.
     */
    fun verifyAndGenerate(reEnteredPassphrase: String) {
        if (_state.value.generating) return
        val passphrase = chosenPassphrase
        if (passphrase == null) {
            // Only reachable if step 2 was skipped (e.g. process death restoring
            // straight into step 3): send the user back rather than guess.
            _state.value = CreateVaultKeyState(passphraseMissing = true)
            return
        }
        if (reEnteredPassphrase != passphrase) {
            _state.value = CreateVaultKeyState(mismatch = true)
            return
        }
        _state.value = CreateVaultKeyState(
            generating = true,
            progressStage = VaultKeyCreationStage.CREATING_KEY,
        )
        viewModelScope.launch {
            runCatching {
                val rounds = appStorage.sshKdfRounds.first()
                val key = withContext(Dispatchers.Default) {
                    SshKeyCodec.generateEncrypted(
                        // Microsecond-resolution id, matching the convention used
                        // for every other account created by the app.
                        createdAt = clock.millis() * 1_000L,
                        passphrase = passphrase.toCharArray(),
                        rounds = rounds,
                    )
                }
                _state.value = _state.value.copy(progressStage = VaultKeyCreationStage.DERIVING_NOSTR_KEY_PAIR)
                val (vaultKey, createFlowRelayIdentity, parsedSeed) = withContext(Dispatchers.Default) {
                    // Parsing is another bcrypt operation, so it must remain on the worker
                    // dispatcher along with every identity derived from the decrypted seed.
                    val parsed = SshKeyCodec.parse(key.privateKeyPem, passphrase.toCharArray())
                    val mnemonic = Seedify.toMnemonic(parsed.seed)
                    val vaultKey = Account(
                        createdAt = key.createdAt,
                        // Associated-account exports use canonical base64 without PEM wrappers
                        // or line breaks; store generated Vault Keys in that same format.
                        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(key.privateKeyPem),
                        zenTenPass = passphrase,
                        nostr = NostrKeyDerivation.derive(mnemonic).toPersistedKeyPair(),
                    )
                    val createFlowRelayIdentity = NostrKeyDerivation.derive(
                        Seedify.toBraveAccountMnemonic(parsed.seed),
                    ).toPersistedKeyPair()
                    Triple(vaultKey, createFlowRelayIdentity, parsed.seed)
                }
                _state.value = _state.value.copy(progressStage = VaultKeyCreationStage.DERIVING_SYNC_CODE)
                val secretCombinationIndices = withContext(Dispatchers.Default) {
                    Seedify.toBraveSyncWordIndices(parsedSeed)
                }
                Triple(vaultKey, createFlowRelayIdentity, secretCombinationIndices)
            }.mapCatching { (vaultKey, createFlowRelayIdentity, secretCombinationIndices) ->
                _state.value = _state.value.copy(progressStage = VaultKeyCreationStage.SYNCING_BACKUPS)
                // Local commit happens before any network call. The setup service records
                // BACKUP_INTRO even if one or more configured sources are unavailable.
                setupService.persistGeneratedVaultKeyAndBackup(vaultKey, createFlowRelayIdentity) { progress ->
                    _state.value = _state.value.copy(
                        progressStage = VaultKeyCreationStage.SYNCING_BACKUPS,
                        backupProgress = progress,
                    )
                }.getOrThrow()
                secretCombinationIndices
            }.onSuccess { secretCombinationIndices ->
                chosenPassphrase = null
                secretCombinationsLoadStarted = true
                _state.value = _state.value.copy(
                    generating = false,
                    keyGenerated = true,
                    navigateToBackup = true,
                    secretCombinationIndices = secretCombinationIndices,
                    secretCombinationsLoading = false,
                    progressStage = VaultKeyCreationStage.COMPLETE,
                )
            }.onFailure {
                // The message is intentionally dropped: it could echo key or
                // passphrase material into the UI.
                _state.value = CreateVaultKeyState(generationFailed = true)
            }
        }
    }

    /**
     * Acknowledges that the backup step has been shown.
     *
     * [CreateVaultKeyState.navigateToBackup] is a one-shot signal; clearing it
     * keeps the user from being bounced forward again if they navigate back to
     * the confirmation step.
     */
    fun onBackupStepShown() {
        if (!_state.value.navigateToBackup) return
        _state.value = _state.value.copy(navigateToBackup = false)
    }

    /** Clears a mismatch or failure message once the user edits the field again. */
    fun clearVerificationMessage() {
        if (!_state.value.mismatch && !_state.value.generationFailed) return
        _state.value = _state.value.copy(mismatch = false, generationFailed = false)
    }

    fun updateSecretCombinationEntry(index: Int, value: String) {
        secretCombinationEntries[index] = value
    }

    fun advanceSecretCombinationEntry() {
        if (activeSecretCombinationSegment < secretCombinationEntries.lastIndex) {
            activeSecretCombinationSegment++
        }
    }

    /**
     * Builds the Vault Key's self-describing export text. It shares the associated-account
     * local-backup format so exported copies read the same way, but uses the fixed "VaultKey"
     * display name because the Vault Key has none.
     */
    fun vaultKeyForExport(): String? = secureStorage.getVaultKey()?.let { vaultKey ->
        runCatching { LocalAccountBackupShare.vaultKeyExportText(vaultKey) }.getOrNull()
    }

    /** Marks confirmation as resumable only after Android accepted the targeted export intent. */
    fun recordExportStarted() {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.CONFIRM_BACKUP)
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
            appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.SECRET_COMBINATIONS)
        }
    }

    /** Makes the celebration screen resumable before leaving recovery-code verification. */
    fun recordSecretCombinationsVerified(onRecorded: () -> Unit) {
        viewModelScope.launch {
            appStorage.setCreateVaultKeyStep(CreateVaultKeyStep.CONGRATULATIONS)
            onRecorded()
        }
    }

    /** Finishes onboarding before opening Accounts so app restart follows the same destination. */
    fun completeGeneratedVaultKeySetup(onCompleted: () -> Unit) {
        if (_state.value.advancing) return
        _state.value = _state.value.copy(advancing = true)
        viewModelScope.launch {
            appStorage.setSetupComplete()
            secretCombinationEntries.fill("")
            _state.value = _state.value.copy(
                advancing = false,
                secretCombinationIndices = null,
            )
            onCompleted()
        }
    }

    /**
     * The normal create flow already populated the indices while its seed was in memory.
     * Only a restored process reaches the stored-key decryption fallback below.
     */
    fun loadSecretCombinationIndices() {
        if (secretCombinationsLoadStarted) return
        secretCombinationsLoadStarted = true
        _state.value = _state.value.copy(secretCombinationsLoading = true)
        viewModelScope.launch {
            val indices = withContext(Dispatchers.Default) {
                secureStorage.getVaultKey()?.let { vaultKey ->
                    runCatching {
                        val passphrase = vaultKey.zenTenPass
                        val seed = SshKeyCodec.parse(vaultKey.zenTenKey, passphrase.toCharArray()).seed
                        Seedify.toBraveSyncWordIndices(seed)
                    }.getOrNull()
                }
            }
            _state.value = _state.value.copy(
                secretCombinationIndices = indices,
                secretCombinationsLoading = false,
            )
        }
    }

    /** Validates and compares normalized key material without retaining clipboard text in UI state. */
    fun confirmPastedVaultKey(pastedKey: String): BackupConfirmationResult {
        val normalized = runCatching {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pastedKey)
        }.getOrElse { return BackupConfirmationResult.INVALID }
        val stored = secureStorage.getVaultKey()?.zenTenKey
            ?.let { runCatching { SshKeyCodec.normalizeToSingleLineBase64(it) }.getOrNull() }
            ?: return BackupConfirmationResult.UNAVAILABLE
        return if (normalized == stored) BackupConfirmationResult.MATCHED else BackupConfirmationResult.MISMATCHED
    }

}

/**
 * UI-visible state of the **Create VaultKey** wizard.
 *
 * Deliberately contains no secret material — only flags the screens need in
 * order to enable actions, show messages, and advance.
 */
data class CreateVaultKeyState(
    /** True while the VaultKey is being generated; the action stays inert. */
    val generating: Boolean = false,
    /** Current non-secret operation shown on the dedicated creation screen. */
    val progressStage: VaultKeyCreationStage? = null,
    /** Source-level progress while the generated account is synchronized. */
    val backupProgress: AccountConsolidationEngine.VaultKeyBackupProgress? = null,
    /** True when the two passphrase entries differed. */
    val mismatch: Boolean = false,
    /** True when key generation failed and the user can retry. */
    val generationFailed: Boolean = false,
    /** True when no passphrase was captured, so the user must return to step 2. */
    val passphraseMissing: Boolean = false,
    /** True once a VaultKey has been generated and committed to encrypted storage. */
    val keyGenerated: Boolean = false,
    /** One-shot signal to advance to the backup step; cleared by [CreateVaultKeyViewModel.onBackupStepShown]. */
    val navigateToBackup: Boolean = false,
    val advancing: Boolean = false,
    val navigateToConfirmation: Boolean = false,
    /** Cached only in memory; null after loading means derivation failed. */
    val secretCombinationIndices: List<Int>? = null,
    val secretCombinationsLoading: Boolean = true,
)

enum class VaultKeyCreationStage {
    CREATING_KEY,
    DERIVING_NOSTR_KEY_PAIR,
    DERIVING_SYNC_CODE,
    SYNCING_BACKUPS,
    COMPLETE,
}

enum class BackupConfirmationResult {
    MATCHED,
    INVALID,
    MISMATCHED,
    UNAVAILABLE,
}
