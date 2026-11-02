package app.zenten.vault.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.VaultKeySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

    val state: StateFlow<VaultUnlockGateState> = combine(
        secureStorage.vaultKeyUpdates.map { it != null },
        vaultKeySession.isUnlocked,
        verifying,
        error,
    ) { hasVaultKey, unlocked, isVerifying, hasError ->
        when {
            unlocked -> VaultUnlockGateState.Unlocked
            !hasVaultKey -> VaultUnlockGateState.Onboarding
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
