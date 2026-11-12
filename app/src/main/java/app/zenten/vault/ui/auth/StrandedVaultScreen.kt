package app.zenten.vault.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.R
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.data.secure.UnreadableReason
import app.zenten.vault.domain.StrandedVaultResetService
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State of the stranded-vault screen.
 *
 * [working] is a real pending state rather than a fire-and-forget call: the action writes to
 * storage, and the user must not be able to start it twice while the first attempt runs.
 *
 * [reason] is the cause reported by storage. It selects the explanation shown, because a missing
 * wrapping key and a damaged payload need different advice, and only [reason] decides whether
 * setting the records aside is offered at all.
 */
data class StrandedVaultState(
    val working: Boolean = false,
    val failed: Boolean = false,
    val reason: UnreadableReason? = null,
)

@HiltViewModel
class StrandedVaultViewModel @Inject constructor(
    private val resetService: StrandedVaultResetService,
    private val secureStorage: SecureStorageService,
) : ViewModel() {
    private val _state = MutableStateFlow(StrandedVaultState())
    val state: StateFlow<StrandedVaultState> = _state.asStateFlow()

    init {
        // The reason drives the copy, so it follows storage rather than being read once: a retry
        // that clears a transient Keystore failure must also clear the screen's explanation.
        viewModelScope.launch {
            secureStorage.unreadableRecords.collect { reason ->
                _state.value = _state.value.copy(reason = reason)
            }
        }
    }

    /**
     * Sets the unreadable vault aside so the onboarding and recovery flows become reachable.
     *
     * Nothing is navigated from here. The gate observes storage, so once the unreadable record is
     * quarantined and the routing flags are cleared, the gate reports `Onboarding` on its own and
     * this screen is replaced. That ordering means there is no moment where the screen is gone but
     * the vault is still blocking, and a failure is reported in place rather than dropped.
     */
    fun setAside() {
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, failed = false)
        viewModelScope.launch {
            val result = runCatching { resetService.reset() }
            _state.value = _state.value.copy(working = false, failed = result.isFailure)
        }
    }

    /**
     * Re-checks record health when the previous answer was that the Keystore could not be read.
     *
     * This is the only recovery for [UnreadableReason.KeystoreUnavailable]: nothing can be set
     * aside on that answer, so the user needs a way to ask again before the state is actionable.
     */
    fun retryCheck() {
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, failed = false)
        viewModelScope.launch {
            // The re-scan decrypts every record and queries the Keystore, so it belongs off the
            // main thread; the collector above then republishes the new reason.
            withContext(Dispatchers.Default) { secureStorage.refreshRecordHealth() }
            _state.value = _state.value.copy(working = false)
        }
    }
}

/**
 * Explains that stored records cannot be opened on this device, and clears the way to recovery.
 *
 * This replaces what previously happened when records arrived without their wrapping key: the gate
 * reported `Onboarding`, the launch router sent the user to an empty account list, and nothing
 * explained why their vault had disappeared. App data is not meant to transfer between devices at
 * all (`res/xml/data_extraction_rules.xml` excludes every domain), so this is the fallback for when
 * that declaration is not honoured, a transfer is partial, or records are damaged.
 *
 * The copy follows [StrandedVaultState.reason] rather than assuming one cause. Claiming the key
 * "stayed on the original device" is only true for [UnreadableReason.KeystoreKeyMissing]; a damaged
 * payload has its key right here, and a Keystore that could not be read may be perfectly healthy.
 * Describing the wrong cause sends the user looking for the wrong fix.
 *
 * Recovering the VaultKey is the only outcome that restores the user's data, so the primary action
 * leads there — to **Get started**, where **Recover VaultKey** is already a first-class choice
 * alongside creating a new one. Set-aside records stay on disk but remain unreadable here.
 *
 * Setting records aside is withheld when the cause is [UnreadableReason.KeystoreUnavailable]. That
 * answer means the device could not check, so the records may still be readable; the screen offers
 * a re-check instead. Nothing is ever set aside on an unknown state.
 *
 * A dedicated in-place recovery flow is deliberately not offered. The recovery screens live in
 * the authenticated navigation graph, which cannot mount while the gate is reporting an unreadable
 * vault; duplicating that flow at the gate would mean two implementations of Secret Combinations
 * entry and passphrase handling, and the second copy is exactly where a safety control would drift.
 */
@Composable
fun StrandedVaultScreen(viewModel: StrandedVaultViewModel) {
    val state by viewModel.state.collectAsState()
    val reason = state.reason

    VaultScreenScaffold(
        title = stringResource(R.string.stranded_vault_title),
        // This screen replaces the vault rather than sitting in front of it, so there is no
        // destination to return to; the flow forward is the only exit.
        onBack = null,
        content = {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    when (reason) {
                        UnreadableReason.PayloadInvalid -> R.string.stranded_vault_body_payload_invalid
                        UnreadableReason.KeystoreUnavailable -> R.string.stranded_vault_body_keystore_unavailable
                        // `null` is transient while the screen settles; it reads as the default
                        // explanation rather than as a fourth, contradictory message.
                        UnreadableReason.KeystoreKeyMissing, null -> R.string.stranded_vault_body
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            // The retention and recovery paragraphs only hold once the records are known to be
            // unreadable. On an unavailable Keystore they would contradict the message above,
            // which says the records may still be readable.
            if (reason != UnreadableReason.KeystoreUnavailable) {
                Text(
                    text = stringResource(R.string.stranded_vault_body_records_kept),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.stranded_vault_body_recover),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.failed) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.stranded_vault_action_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reason == UnreadableReason.KeystoreUnavailable) {
                    // The device could not read its Keystore, so nothing here is known to be
                    // unreadable. Offering to set records aside would act on an unknown, so the
                    // only action is to ask again.
                    VaultPillButton(
                        label = stringResource(R.string.stranded_vault_action_check_again),
                        onClick = viewModel::retryCheck,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.working,
                    )
                } else {
                    VaultPillButton(
                        label = stringResource(R.string.stranded_vault_action_records_kept_continue),
                        onClick = viewModel::setAside,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.working,
                    )
                }
            }
        },
    )
}
