package app.zenten.vault.ui.vaultKey

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.BackupAccountId
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.SetupService
import app.zenten.vault.ui.accounts.BackupSourceRow
import app.zenten.vault.ui.accounts.ExportPassphraseDialog
import app.zenten.vault.ui.accounts.ExportTargetDialog
import app.zenten.vault.ui.accounts.LocalAccountBackupShare
import app.zenten.vault.ui.accounts.SourceStatusRow
import app.zenten.vault.ui.components.VaultScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultKeyDetailViewModel @Inject constructor(
    sourceRepository: BackupSourceRepository,
    appStorage: AppStorageService,
    private val setupService: SetupService,
    private val scheduler: BackupWorkScheduler,
    private val secureStorage: SecureStorageService,
) : ViewModel() {
    val state = combine(sourceRepository.sources, appStorage.accountSourceStatuses, appStorage.setupComplete) { sources, statuses, complete ->
        VaultKeyDetailState(
            setupComplete = complete,
            sources = sources.filter { it.enabled }.map { source ->
                val status = statuses.firstOrNull { it.accountId == BackupAccountId.VAULT_KEY_VALUE && it.sourceId == source.id }
                BackupSourceRow(
                    source.id, source.label, source.endpoint, source.type,
                    status?.state ?: AccountSourceBackupState.UNKNOWN,
                    status?.lastCheckedAtMillis,
                    status?.lastSuccessfulBackupAtMillis,
                    status?.errorCategory,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultKeyDetailState())

    fun retryVaultKey() { viewModelScope.launch { setupService.retryVaultKeyBackup() } }

    /** Whether a stored Vault Key exists, so the export action can be offered. */
    fun canExportVaultKey(): Boolean = secureStorage.getVaultKey() != null

    /**
     * Builds the Vault Key's self-describing export text, using the fixed "VaultKey" display
     * name. Returns null when the Vault Key is unavailable or [passphrase] does not decrypt it.
     *
     * The passphrase is needed because the export's npub line is derived on demand rather than
     * read from a stored identity. Decrypting here is also the passphrase check, so the export is
     * confirmed without a second, redundant bcrypt derivation.
     */
    fun vaultKeyForExport(passphrase: String): String? = secureStorage.getVaultKey()?.let { vaultKey ->
        // The caller already holds the passphrase as an immutable String, but zero the extra
        // char copy so this module does not leave another long-lived copy for the GC to find.
        val chars = passphrase.toCharArray()
        try {
            runCatching { LocalAccountBackupShare.vaultKeyExportText(vaultKey, chars) }.getOrNull()
        } finally {
            chars.fill('\u0000')
        }
    }

    /** Reconciles the Vault Key record only; associated accounts have their own actions. */
    fun consolidate() { scheduler.scheduleVaultKeyConsolidation() }
}

data class VaultKeyDetailState(
    val setupComplete: Boolean = false,
    val sources: List<BackupSourceRow> = emptyList(),
)

@Composable
fun VaultKeyDetailScreen(
    navController: NavHostController,
    viewModel: VaultKeyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Target picker shown while the user chooses where to send the VaultKey export.
    var exportTargets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    // Passphrase confirmation shown before the export target picker opens.
    var confirmExportPassphrase by remember { mutableStateOf(false) }
    // The export text is built once the passphrase is verified, because its npub line is derived
    // on demand rather than read from the account. Only this text is retained — never the
    // passphrase — and it is dropped as soon as the target picker closes.
    var exportText by remember { mutableStateOf<String?>(null) }
    VaultScreenScaffold(
        title = "VaultKey Account",
        onBack = navController::navigateUp,
        content = {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        exportError = null
                        if (!viewModel.canExportVaultKey()) {
                            exportError = "VaultKey is unavailable for export."
                        } else {
                            confirmExportPassphrase = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("Export VaultKey to Notes") }
                exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Backup Status", style = MaterialTheme.typography.titleMedium)
                state.sources.forEach { SourceStatusRow(it) }
                if (state.setupComplete) {
                    Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Consolidate VaultKey backup")
                    }
                } else {
                    Button(onClick = viewModel::retryVaultKey, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Retry Vault Key setup")
                    }
                }
            }
        },
        actions = {},
    )
    if (confirmExportPassphrase) {
        ExportPassphraseDialog(
            message = "Re-enter the passphrase that protects the VaultKey before exporting its encrypted key.",
            mismatchError = "That passphrase does not match this VaultKey.",
            onDismiss = { confirmExportPassphrase = false },
            onConfirmed = { passphrase ->
                // Deriving the text decrypts the stored key, so a null result means the
                // passphrase did not match and the export cannot be produced.
                val text = viewModel.vaultKeyForExport(passphrase)
                if (text == null) {
                    false
                } else {
                    exportText = text
                    exportTargets = LocalAccountBackupShare.availableTargets(context.packageManager)
                    true
                }
            },
        )
    }
    exportTargets?.let { availableTargets ->
        ExportTargetDialog(
            targets = availableTargets,
            onDismiss = {
                exportTargets = null
                // The export text is only kept alive for the action the user just confirmed.
                exportText = null
            },
            onTargetSelected = { target ->
                // Every exit below drops the retained export text, whether the export succeeded.
                val text = exportText
                exportTargets = null
                exportText = null
                if (text == null) {
                    exportError = "VaultKey is unavailable for export."
                    return@ExportTargetDialog
                }
                try {
                    context.startActivity(LocalAccountBackupShare.targetedSendIntent(text, target))
                } catch (_: ActivityNotFoundException) {
                    exportError = "That app is no longer available. Choose another app."
                } catch (_: SecurityException) {
                    exportError = "Android did not allow this app to receive the export. Choose another app."
                }
            },
        )
    }
}
