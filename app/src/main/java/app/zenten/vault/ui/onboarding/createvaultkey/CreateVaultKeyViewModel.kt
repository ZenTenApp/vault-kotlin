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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
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
    private val passphraseStrengthChecker: PassphraseStrengthChecker,
) : ViewModel() {
    /**
     * Passphrase chosen on step 2. Kept out of [state] so it can never be read
     * back by a composable or leak into a UI snapshot/log of the state object.
     */
    private var chosenPassphrase: String? = null

    /**
     * Latest measured guessability of the passphrase being typed.
     *
     * Null means "no measurement for the current input yet", which the screen shows
     * differently from a measurement that came back weak. Holds no secret material:
     * only the score and the guess-count magnitude.
     */
    private val _passphraseStrength = MutableStateFlow<PassphraseStrength?>(null)
    val passphraseStrength: StateFlow<PassphraseStrength?> = _passphraseStrength.asStateFlow()

    /** Pending debounced measurement; replaced on every keystroke. */
    private var passphraseStrengthJob: Job? = null

    private var secretCombinationsLoadStarted = false

    /** Verification input survives Back navigation while this wizard graph remains active. */
    val secretCombinationEntries = mutableStateListOf(*Array(24) { "" })

    var activeSecretCombinationSegment by mutableIntStateOf(0)
        private set

    private val _state = MutableStateFlow(CreateVaultKeyState())
    val state: StateFlow<CreateVaultKeyState> = _state.asStateFlow()

    private val _pasteGuard = MutableStateFlow(VaultKeyPasteGuard())

    /**
     * Failed-paste counter and active block for **Confirm Backup**.
     *
     * Held separately from [state] because [choosePassphrase] resets [state] to a
     * fresh value when the user returns to an earlier step, which must not wipe an
     * in-progress lockout. Mirrors the values persisted in [AppStorageService] so a
     * process restart cannot shake off the wait.
     */
    val pasteGuard: StateFlow<VaultKeyPasteGuard> = _pasteGuard.asStateFlow()

    init {
        viewModelScope.launch {
            val attempts = appStorage.vaultKeyPasteAttempts.first()
            val blockedUntil = appStorage.vaultKeyPasteBlockedUntilMillis.first()
                // A deadline that already passed keeps the attempt count but no longer blocks.
                ?.takeIf { it > clock.millis() }
            _pasteGuard.value = VaultKeyPasteGuard(attempts, blockedUntil)
        }
    }

    /**
     * Schedules a debounced strength measurement for the passphrase being typed.
     *
     * The quiet period keeps the estimator from running its pattern search once per
     * keystroke. The length rule is applied first because it is free, and every input
     * below the floor is rejected regardless of how well it would score.
     */
    fun onPassphraseChanged(passphrase: String) {
        passphraseStrengthJob?.cancel()
        if (!VaultKeyPassphrasePolicy.hasMinimumLength(passphrase)) {
            _passphraseStrength.value = null
            return
        }
        passphraseStrengthJob = viewModelScope.launch {
            delay(PASSPHRASE_STRENGTH_DEBOUNCE_MILLIS)
            _passphraseStrength.value = passphraseStrengthChecker.measure(passphrase)
        }
    }

    /**
     * Drops the cached measurement when the passphrase step leaves the screen.
     *
     * Without this, returning to the step via Back would show strength feedback for
     * text that is no longer in the field.
     */
    fun clearPassphraseStrength() {
        passphraseStrengthJob?.cancel()
        passphraseStrengthJob = null
        _passphraseStrength.value = null
    }

    /**
     * Records the passphrase chosen on step 2.
     *
     * Strength is re-measured here instead of trusting the screen's debounced preview,
     * because the user can edit the field and confirm faster than the preview updates.
     * Both rules are enforced in the ViewModel so acceptance never depends on UI state.
     *
     * Any previously generated key is discarded: if the user walked back and
     * changed the passphrase, a key encrypted with the old one must not survive.
     */
    suspend fun choosePassphrase(passphrase: String): Boolean {
        val strength = if (VaultKeyPassphrasePolicy.hasMinimumLength(passphrase)) {
            passphraseStrengthChecker.measure(passphrase)
        } else {
            // No measurement needed for input the length rule already rejects.
            null
        }
        if (!VaultKeyPassphrasePolicy.isValid(passphrase, strength)) {
            // Publish the fresh result so the checklist explains the refusal, rather
            // than leaving stale success feedback on screen.
            _passphraseStrength.value = strength
            // Do not leave an earlier valid secret available if a later choice is rejected.
            chosenPassphrase = null
            _state.value = CreateVaultKeyState()
            return false
        }
        chosenPassphrase = passphrase
        clearPassphraseStrength()
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
                        Seedify.toVaultKeyBackupMnemonic(parsed.seed),
                    ).toPersistedKeyPair()
                    Triple(vaultKey, createFlowRelayIdentity, parsed.seed)
                }
                _state.value = _state.value.copy(progressStage = VaultKeyCreationStage.DERIVING_SYNC_CODE)
                val secretCombinationIndices = withContext(Dispatchers.Default) {
                    Seedify.toVaultKeyBackupWordIndices(parsedSeed)
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
                        Seedify.toVaultKeyBackupWordIndices(seed)
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
        // Constant-time equality keeps this key-material comparison consistent with MAC checks.
        return if (MessageDigest.isEqual(normalized.toByteArray(), stored.toByteArray())) {
            // A correct confirmation ends the escalation; a later re-entry starts fresh.
            resetVaultKeyPasteGuard()
            BackupConfirmationResult.MATCHED
        } else {
            BackupConfirmationResult.MISMATCHED
        }
    }

    /**
     * Records an unsupported or non-matching **Confirm Backup** paste.
     *
     * The first three failures block the wizard for 1, 2, then 3 minutes. The
     * fourth returns [VaultKeyPasteFailure.ReturnToExport] so the caller can send
     * the user back to **Create Backup** to export again, and resets the counter
     * because a fresh export begins a new cycle. Only validation and match
     * failures count; a clipboard or storage problem is not a wrong-key attempt.
     */
    fun registerFailedVaultKeyPaste(): VaultKeyPasteFailure {
        val attempts = _pasteGuard.value.attempts + 1
        if (attempts >= MAX_VAULT_KEY_PASTE_ATTEMPTS) {
            resetVaultKeyPasteGuard()
            return VaultKeyPasteFailure.ReturnToExport
        }
        // Escalate the wait with each failed attempt: 1 minute, then 2, then 3.
        val blockedUntil = clock.millis() + attempts * VAULT_KEY_PASTE_LOCKOUT_STEP_MILLIS
        _pasteGuard.value = VaultKeyPasteGuard(attempts, blockedUntil)
        viewModelScope.launch { appStorage.setVaultKeyPasteGuard(attempts, blockedUntil) }
        return VaultKeyPasteFailure.Blocked(blockedUntil)
    }

    /**
     * Drops an expired block once the countdown reaches zero while keeping the
     * failed-attempt count, so the next failure escalates instead of restarting.
     */
    fun onPasteLockoutExpired() {
        val guard = _pasteGuard.value
        val until = guard.blockedUntilMillis ?: return
        if (until > clock.millis()) return
        _pasteGuard.value = guard.copy(blockedUntilMillis = null)
        viewModelScope.launch { appStorage.setVaultKeyPasteGuard(guard.attempts, null) }
    }

    /** Clears the failed-paste counter and any active block. */
    fun resetVaultKeyPasteGuard() {
        if (_pasteGuard.value == VaultKeyPasteGuard()) return
        _pasteGuard.value = VaultKeyPasteGuard()
        viewModelScope.launch { appStorage.setVaultKeyPasteGuard(0, null) }
    }

    companion object {
        /** Failed Confirm Backup pastes before the wizard returns to **Create Backup**. */
        const val MAX_VAULT_KEY_PASTE_ATTEMPTS = 4
        /** Each failed paste extends the lockout by one more minute. */
        const val VAULT_KEY_PASTE_LOCKOUT_STEP_MILLIS = 60_000L
        /**
         * Quiet period before measuring the passphrase being typed.
         *
         * Long enough to absorb normal typing and cover the estimator's own
         * millisecond-scale cost, short enough that the Confirm action still appears
         * to react to the last keystroke.
         */
        const val PASSPHRASE_STRENGTH_DEBOUNCE_MILLIS = 250L
    }

}

/** Persisted failed-paste counter and block deadline for **Confirm Backup**. */
data class VaultKeyPasteGuard(
    val attempts: Int = 0,
    val blockedUntilMillis: Long? = null,
)

/** What the caller should do after a failed **Confirm Backup** paste. */
sealed interface VaultKeyPasteFailure {
    /** The wizard is blocked until this wall-clock time. */
    data class Blocked(val untilMillis: Long) : VaultKeyPasteFailure
    /** Too many failures: return to **Create Backup** to export a fresh key. */
    data object ReturnToExport : VaultKeyPasteFailure
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
