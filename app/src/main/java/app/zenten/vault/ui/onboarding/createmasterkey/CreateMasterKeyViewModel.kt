package app.zenten.vault.ui.onboarding.createmasterkey

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
import app.zenten.vault.data.kv.CreateMasterKeyStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.SetupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/**
 * Shared state for the multi-screen **Create MasterKey** wizard.
 *
 * Scoped to the wizard's navigation graph (see [rememberCreateMasterKeyViewModel])
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
class CreateMasterKeyViewModel @Inject constructor(
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

    private val _state = MutableStateFlow(CreateMasterKeyState())
    val state: StateFlow<CreateMasterKeyState> = _state.asStateFlow()

    /**
     * Records the passphrase chosen on step 2.
     *
     * Any previously generated key is discarded: if the user walked back and
     * changed the passphrase, a key encrypted with the old one must not survive.
     */
    fun choosePassphrase(passphrase: String): Boolean {
        if (!MasterKeyPassphrasePolicy.isValid(passphrase)) {
            // Do not leave an earlier valid secret available if a later choice is rejected.
            chosenPassphrase = null
            _state.value = CreateMasterKeyState()
            return false
        }
        chosenPassphrase = passphrase
        _state.value = CreateMasterKeyState()
        return true
    }

    /**
     * Compares the step-3 re-entry with the chosen passphrase and, on a match,
     * generates the MasterKey encrypted with that passphrase.
     *
     * A mismatch is reported through [CreateMasterKeyState.mismatch] and nothing
     * is generated, so a typo in an unrecoverable secret cannot be baked into a
     * key. Generation runs off the main thread because bcrypt_pbkdf with
     * [SshKeyCodec.BCRYPT_PBKDF_ROUNDS] rounds is intentionally slow.
     */
    fun verifyAndGenerate(reEnteredPassphrase: String) {
        if (_state.value.generating) return
        val passphrase = chosenPassphrase
        if (passphrase == null) {
            // Only reachable if step 2 was skipped (e.g. process death restoring
            // straight into step 3): send the user back rather than guess.
            _state.value = CreateMasterKeyState(passphraseMissing = true)
            return
        }
        if (reEnteredPassphrase != passphrase) {
            _state.value = CreateMasterKeyState(mismatch = true)
            return
        }
        _state.value = CreateMasterKeyState(
            generating = true,
            progressStage = MasterKeyCreationStage.CREATING_KEY,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val key = SshKeyCodec.generateEncrypted(
                        // Microsecond-resolution id, matching the convention used
                        // for every other account created by the app.
                        createdAt = clock.millis() * 1_000L,
                        passphrase = passphrase.toCharArray(),
                    )
                    // Parsing is another bcrypt operation, so it must remain on the worker
                    // dispatcher along with every identity derived from the decrypted seed.
                    val parsed = SshKeyCodec.parse(key.privateKeyPem, passphrase.toCharArray())
                    val mnemonic = Seedify.toMnemonic(parsed.seed)
                    val master = Account(
                        createdAt = key.createdAt,
                        // Associated-account exports use canonical base64 without PEM wrappers
                        // or line breaks; store generated masters in that same format.
                        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(key.privateKeyPem),
                        zenTenPass = passphrase,
                        pubkey = key.publicKey,
                        nostr = NostrKeyDerivation.derive(mnemonic).toPersistedKeyPair(),
                    )
                    val createFlowRelayIdentity = NostrKeyDerivation.derive(
                        Seedify.toBraveAccountMnemonic(parsed.seed),
                    ).toPersistedKeyPair()
                    Triple(master, createFlowRelayIdentity, Seedify.toBraveSyncWordIndices(parsed.seed))
                }
            }.mapCatching { (master, createFlowRelayIdentity, secretCombinationIndices) ->
                _state.value = _state.value.copy(progressStage = MasterKeyCreationStage.SAVING_KEY)
                // Local commit happens before any network call. The setup service records
                // BACKUP_INTRO even if one or more configured sources are unavailable.
                setupService.persistGeneratedMasterAndBackup(master, createFlowRelayIdentity) { progress ->
                    _state.value = _state.value.copy(
                        progressStage = MasterKeyCreationStage.SYNCING_BACKUPS,
                        backupProgress = progress,
                    )
                }.getOrThrow()
                secretCombinationIndices
            }.onSuccess { secretCombinationIndices ->
                chosenPassphrase = null
                secretCombinationsLoadStarted = true
                _state.value = CreateMasterKeyState(
                    keyGenerated = true,
                    navigateToBackup = true,
                    secretCombinationIndices = secretCombinationIndices,
                    secretCombinationsLoading = false,
                )
            }.onFailure {
                // The message is intentionally dropped: it could echo key or
                // passphrase material into the UI.
                _state.value = CreateMasterKeyState(generationFailed = true)
            }
        }
    }

    /**
     * Acknowledges that the backup step has been shown.
     *
     * [CreateMasterKeyState.navigateToBackup] is a one-shot signal; clearing it
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
                secureStorage.getMasterAccount()?.let { master ->
                    runCatching {
                        val passphrase = requireNotNull(master.zenTenPass) { "Master passphrase is unavailable" }
                        val seed = SshKeyCodec.parse(master.zenTenKey, passphrase.toCharArray()).seed
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

/**
 * UI-visible state of the **Create MasterKey** wizard.
 *
 * Deliberately contains no secret material — only flags the screens need in
 * order to enable actions, show messages, and advance.
 */
data class CreateMasterKeyState(
    /** True while the MasterKey is being generated; the action stays inert. */
    val generating: Boolean = false,
    /** Current non-secret operation shown on the dedicated creation screen. */
    val progressStage: MasterKeyCreationStage? = null,
    /** Source-level progress while the generated account is synchronized. */
    val backupProgress: AccountConsolidationEngine.MasterBackupProgress? = null,
    /** True when the two passphrase entries differed. */
    val mismatch: Boolean = false,
    /** True when key generation failed and the user can retry. */
    val generationFailed: Boolean = false,
    /** True when no passphrase was captured, so the user must return to step 2. */
    val passphraseMissing: Boolean = false,
    /** True once a MasterKey has been generated and committed to encrypted storage. */
    val keyGenerated: Boolean = false,
    /** One-shot signal to advance to the backup step; cleared by [CreateMasterKeyViewModel.onBackupStepShown]. */
    val navigateToBackup: Boolean = false,
    val advancing: Boolean = false,
    val navigateToConfirmation: Boolean = false,
    /** Cached only in memory; null after loading means derivation failed. */
    val secretCombinationIndices: List<Int>? = null,
    val secretCombinationsLoading: Boolean = true,
)

enum class MasterKeyCreationStage {
    CREATING_KEY,
    SAVING_KEY,
    SYNCING_BACKUPS,
}

enum class BackupConfirmationResult {
    MATCHED,
    INVALID,
    MISMATCHED,
    UNAVAILABLE,
}
