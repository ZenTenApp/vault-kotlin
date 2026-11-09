package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.theme.LocalExtendedColors

/** Shows the irreversible local creation and remote synchronization work after verification. */
@Composable
fun CreateVaultKeyCreatingScreen(navController: NavHostController) {
    val viewModel = rememberCreateVaultKeyViewModel(navController)
    val state by viewModel.state.collectAsState()

    // Leaving midway could expose an earlier wizard step after the account was committed.
    BackHandler(enabled = true) {}

    LaunchedEffect(state.navigateToBackup, state.generationFailed) {
        when {
            state.navigateToBackup -> {
                delay(2_000)
                viewModel.onBackupStepShown()
                navController.navigate(VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO) {
                    popUpTo(VaultDestinations.CREATE_VAULT_KEY_CREATING) { inclusive = true }
                }
            }
            state.generationFailed -> navController.popBackStack()
        }
    }

    val progress = state.backupProgress
    val creatingText = stringResource(R.string.create_vault_key_creating_key)
    val seedText = stringResource(R.string.create_vault_key_deriving_seed)
    val syncCodeText = stringResource(R.string.create_vault_key_deriving_sync_code)
    val relayTexts = progress?.sources
        ?.filter { it.type == BackupSourceType.NOSTR_RELAY }
        ?.mapIndexed { index, _ -> stringResource(R.string.create_vault_key_backup_relay, index + 1) }
        .orEmpty()
    val syncServerTexts = progress?.sources
        ?.filter { it.type == BackupSourceType.BRAVE_SYNC }
        ?.mapIndexed { index, _ -> stringResource(R.string.create_vault_key_backup_sync_server, index + 1) }
        .orEmpty()
    val terminalLines = vaultKeyCreationTerminalLines(
        stage = state.progressStage,
        progress = progress,
        creatingText = creatingText,
        seedText = seedText,
        syncCodeText = syncCodeText,
        relayTexts = relayTexts,
        syncServerTexts = syncServerTexts,
    )
    val complete = state.progressStage == VaultKeyCreationStage.COMPLETE

    CreateVaultKeyScaffold(
        title = stringResource(R.string.create_vault_key_creating_title),
        heroIcon = R.drawable.hero_icon_create_vaultkey,
        heroIconDescription = stringResource(R.string.create_vault_key_passphrase_icon_description),
        onBack = null,
        content = {
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_vault_key_creating_warning),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            DecodeRevealTerminal(
                lines = terminalLines,
                accessibilityStatus = if (complete) {
                    stringResource(R.string.create_vault_key_created_successfully)
                } else {
                    stringResource(R.string.create_vault_key_operation_in_progress)
                },
            )
            if (complete) {
                Spacer(Modifier.height(24.dp))
                VaultKeyCreationSuccess(
                    text = stringResource(R.string.create_vault_key_created_successfully),
                )
            }
        },
        actions = {},
    )
}

internal fun vaultKeyCreationTerminalLines(
    stage: VaultKeyCreationStage?,
    progress: AccountConsolidationEngine.VaultKeyBackupProgress?,
    creatingText: String,
    seedText: String,
    syncCodeText: String,
    relayTexts: List<String>,
    syncServerTexts: List<String>,
): List<DecodeRevealLine> = when (stage) {
        VaultKeyCreationStage.DERIVING_VAULT_KEY_SEED -> listOf(
            DecodeRevealLine(creatingText, active = false),
            DecodeRevealLine(seedText, active = true),
        )
        VaultKeyCreationStage.DERIVING_SYNC_CODE -> listOf(
            DecodeRevealLine(creatingText, active = false),
            DecodeRevealLine(seedText, active = false),
            DecodeRevealLine(syncCodeText, active = true),
        )
        VaultKeyCreationStage.SYNCING_BACKUPS, VaultKeyCreationStage.COMPLETE -> buildList {
            add(DecodeRevealLine(creatingText, active = false))
            add(DecodeRevealLine(seedText, active = false))
            add(DecodeRevealLine(syncCodeText, active = false))
            val sourceLines = backupSourceLines(progress, relayTexts, syncServerTexts)
            sourceLines.forEachIndexed { index, text ->
                val completed = stage == VaultKeyCreationStage.COMPLETE || index < (progress?.completedSources ?: 0)
                val active = stage == VaultKeyCreationStage.SYNCING_BACKUPS &&
                    index == progress?.activeSourceIndex && !completed
                if (completed || active) add(DecodeRevealLine(text, active = active))
            }
        }
        VaultKeyCreationStage.CREATING_KEY, null -> listOf(
            DecodeRevealLine(creatingText, active = true),
        )
    }

internal fun backupSourceLines(
    progress: AccountConsolidationEngine.VaultKeyBackupProgress?,
    relayTexts: List<String>,
    syncServerTexts: List<String>,
): List<String> {
    var relayIndex = 0
    var syncIndex = 0
    return progress?.sources.orEmpty().map { source ->
        when (source.type) {
            BackupSourceType.NOSTR_RELAY -> relayTexts[relayIndex++]
            BackupSourceType.BRAVE_SYNC -> syncServerTexts[syncIndex++]
        }
    }
}

@Composable
private fun VaultKeyCreationSuccess(text: String) {
    val success = LocalExtendedColors.current.success.color
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .border(2.dp, success, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = success,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = success,
            textAlign = TextAlign.Center,
        )
    }
}
