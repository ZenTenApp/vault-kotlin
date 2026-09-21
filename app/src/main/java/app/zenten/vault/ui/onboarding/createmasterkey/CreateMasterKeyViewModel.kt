package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
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
 * Secrets therefore live only in this instance's memory and are dropped as soon
 * as the user leaves the wizard (the graph — and with it this ViewModel — is
 * cleared from the back stack). Nothing here is persisted yet; storing the
 * generated MasterKey and its backup material is a follow-up step.
 */
@HiltViewModel
class CreateMasterKeyViewModel @Inject constructor(
    private val clock: Clock,
) : ViewModel() {
    /**
     * Passphrase chosen on step 2. Kept out of [state] so it can never be read
     * back by a composable or leak into a UI snapshot/log of the state object.
     */
    private var chosenPassphrase: String? = null

    /**
     * MasterKey generated once the two passphrase entries matched.
     *
     * Also kept out of [state]; the follow-up backup steps will read it through
     * [generatedKey] when their behavior is specified.
     */
    private var generated: SshKeyCodec.GeneratedKey? = null

    private val _state = MutableStateFlow(CreateMasterKeyState())
    val state: StateFlow<CreateMasterKeyState> = _state.asStateFlow()

    /**
     * Records the passphrase chosen on step 2.
     *
     * Any previously generated key is discarded: if the user walked back and
     * changed the passphrase, a key encrypted with the old one must not survive.
     */
    fun choosePassphrase(passphrase: String) {
        chosenPassphrase = passphrase
        generated = null
        _state.value = CreateMasterKeyState()
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
        _state.value = CreateMasterKeyState(generating = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    SshKeyCodec.generateEncrypted(
                        // Microsecond-resolution id, matching the convention used
                        // for every other account created by the app.
                        createdAt = clock.millis() * 1_000L,
                        passphrase = passphrase.toCharArray(),
                    )
                }
            }.onSuccess { key ->
                generated = key
                _state.value = CreateMasterKeyState(keyGenerated = true, navigateToBackup = true)
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

    /** MasterKey generated by this wizard, or `null` if verification has not succeeded yet. */
    internal fun generatedKey(): SshKeyCodec.GeneratedKey? = generated
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
    /** True when the two passphrase entries differed. */
    val mismatch: Boolean = false,
    /** True when key generation failed and the user can retry. */
    val generationFailed: Boolean = false,
    /** True when no passphrase was captured, so the user must return to step 2. */
    val passphraseMissing: Boolean = false,
    /** True once a MasterKey exists in memory for the rest of the wizard. */
    val keyGenerated: Boolean = false,
    /** One-shot signal to advance to the backup step; cleared by [CreateMasterKeyViewModel.onBackupStepShown]. */
    val navigateToBackup: Boolean = false,
)
