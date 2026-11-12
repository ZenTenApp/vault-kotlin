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
import app.zenten.vault.domain.StrandedVaultResetService
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the stranded-vault screen.
 *
 * [working] is a real pending state rather than a fire-and-forget call: the action writes to
 * storage, and the user must not be able to start it twice while the first attempt runs.
 */
data class StrandedVaultState(
    val working: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class StrandedVaultViewModel @Inject constructor(
    private val resetService: StrandedVaultResetService,
) : ViewModel() {
    private val _state = MutableStateFlow(StrandedVaultState())
    val state: StateFlow<StrandedVaultState> = _state.asStateFlow()

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
        _state.value = StrandedVaultState(working = true)
        viewModelScope.launch {
            val result = runCatching { resetService.reset() }
            _state.value = StrandedVaultState(working = false, failed = result.isFailure)
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
 * Recovering the VaultKey is the only outcome that restores the user's data, so this screen's single
 * action leads there — to **Get started**, where **Recover VaultKey** is already a first-class
 * choice alongside creating a new one. Set-aside records stay on disk but remain unreadable here.
 *
 * A dedicated in-place recovery flow is deliberately not offered. The recovery screens live in
 * the authenticated navigation graph, which cannot mount while the gate is reporting an unreadable
 * vault; duplicating that flow at the gate would mean two implementations of Secret Combinations
 * entry and passphrase handling, and the second copy is exactly where a safety control would drift.
 */
@Composable
fun StrandedVaultScreen(viewModel: StrandedVaultViewModel) {
    val state by viewModel.state.collectAsState()

    VaultScreenScaffold(
        title = stringResource(R.string.stranded_vault_title),
        // There is no valid destination behind this screen: it is shown instead of the vault, so
        // Back would only return to an unlock gate that can never succeed.
        onBack = null,
        content = {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.stranded_vault_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
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
                VaultPillButton(
                    label = stringResource(R.string.stranded_vault_action_records_kept_continue),
                    onClick = viewModel::setAside,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.working,
                )
            }
        },
    )
}
