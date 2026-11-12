package app.zenten.vault.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.secure.SecureRecordState
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.data.secure.UnreadableReason
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.VaultKeySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides what the app shows after device authentication.
 *
 * A stored VaultKey means the user must enter its passphrase once per session before the vault
 * opens; a fresh install goes straight to onboarding, which collects the passphrase itself.
 *
 * The stored record is observed rather than read once, so completing the create wizard or
 * recovering a VaultKey immediately flips the gate without a restart.
 */
sealed interface VaultUnlockGateState {
    /** Storage has not answered yet. */
    data object Loading : VaultUnlockGateState
    /** No VaultKey is stored yet, so the onboarding flow owns the passphrase. */
    data object Onboarding : VaultUnlockGateState

    /**
     * The vault is stored but cannot be opened on this device.
     *
     * Distinct from both [Onboarding] and [PassphraseRequired] because neither describes it. The
     * user is not a new user (there are records on this device), and asking for the passphrase
     * cannot help (the records are wrapped in a key that no longer exists, so no passphrase will
     * ever open them here). Reporting this as [Onboarding] is what previously showed a migrated
     * user an empty vault; reporting it as [PassphraseRequired] would loop them through failed
     * unlocks forever. The only accurate response is to offer recovery.
     *
     * [reason] describes the VaultKey record when it is itself the unreadable one, and
     * [UnreadableReason.KeystoreKeyMissing] when only account records are unreadable — a partial
     * transfer where the VaultKey record did not arrive. Both mean the same thing to the user: the
     * local ciphertext is unopenable and recovery has to come from outside this device.
     */
    data class VaultUnreadable(val reason: UnreadableReason) : VaultUnlockGateState
    /** A VaultKey is stored and the session is unlocked. */
    data object Unlocked : VaultUnlockGateState
    /** A VaultKey is stored and the session is locked; ask for the passphrase. */
    data class PassphraseRequired(
        val verifying: Boolean = false,
        val error: Boolean = false,
    ) : VaultUnlockGateState
}

@HiltViewModel
class VaultUnlockGateViewModel @Inject constructor(
    secureStorage: SecureStorageService,
    private val vaultKeySession: VaultKeySession,
    private val scheduler: BackupWorkScheduler,
) : ViewModel() {
    private val verifying = MutableStateFlow(false)
    private val error = MutableStateFlow(false)

    /**
     * Any stored record that cannot be read on this device, observed so the gate can leave the
     * recovery screen as soon as the user sets the unreadable records aside.
     */
    private val hasUnreadableRecords = secureStorage.unreadableRecords

    val state: StateFlow<VaultUnlockGateState> = combine(
        secureStorage.vaultKeyState,
        hasUnreadableRecords,
        vaultKeySession.isUnlocked,
        verifying,
        error,
    ) { vaultKey, unreadable, unlocked, isVerifying, hasError ->
        when {
            // Checked before the session state: no unlock can ever succeed while the wrapping key is
            // gone, so reporting `Unlocked` here would be a lie that only surfaces once some later
            // screen fails to find its records.
            vaultKey is SecureRecordState.Unreadable ->
                VaultUnlockGateState.VaultUnreadable(vaultKey.reason)
            // A partial transfer can leave account ciphertext behind without a VaultKey record. The
            // VaultKey looks absent, but this is not a fresh install: onboarding would silently
            // orphan the unreadable accounts on disk.
            unreadable && vaultKey is SecureRecordState.Absent ->
                VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing)
            unlocked -> VaultUnlockGateState.Unlocked
            vaultKey is SecureRecordState.Absent -> VaultUnlockGateState.Onboarding
            else -> VaultUnlockGateState.PassphraseRequired(isVerifying, hasError)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUnlockGateState.Loading)

    /** Verifies the passphrase against the stored key and starts the post-unlock backup cycle. */
    fun submitPassphrase(passphrase: String) {
        if (verifying.value || passphrase.isBlank()) return
        verifying.value = true
        error.value = false
        viewModelScope.launch {
            val result = vaultKeySession.unlock(passphrase.toCharArray())
            verifying.value = false
            if (result.isSuccess) {
                // Consolidation can only run while the session is unlocked, so it is triggered
                // here rather than by periodic work.
                scheduler.scheduleStartupConsolidation()
            } else {
                error.value = true
            }
        }
    }

    fun clearError() {
        error.value = false
    }

    /** Drops the in-memory passphrase when the app returns to a non-unlocked lifecycle state. */
    suspend fun lockSession() = vaultKeySession.lock()
}
