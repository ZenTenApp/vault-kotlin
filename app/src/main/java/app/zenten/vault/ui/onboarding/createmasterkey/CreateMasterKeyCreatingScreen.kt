package app.zenten.vault.ui.onboarding.createmasterkey

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
fun CreateMasterKeyCreatingScreen(navController: NavHostController) {
    val viewModel = rememberCreateMasterKeyViewModel(navController)
    val state by viewModel.state.collectAsState()

    // Leaving midway could expose an earlier wizard step after the account was committed.
    BackHandler(enabled = true) {}
    ScreenshotProtection()

    LaunchedEffect(state.navigateToBackup, state.generationFailed) {
        when {
            state.navigateToBackup -> {
                delay(2_000)
                viewModel.onBackupStepShown()
                navController.navigate(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO) {
                    popUpTo(VaultDestinations.CREATE_MASTER_KEY_CREATING) { inclusive = true }
                }
            }
            state.generationFailed -> navController.popBackStack()
        }
    }

    val progress = state.backupProgress
    val creatingText = stringResource(R.string.create_master_key_creating_key)
    val nostrText = stringResource(R.string.create_master_key_deriving_nostr)
    val syncCodeText = stringResource(R.string.create_master_key_deriving_sync_code)
    val relayTexts = progress?.sources
        ?.filter { it.type == BackupSourceType.NOSTR_RELAY }
        ?.mapIndexed { index, _ -> stringResource(R.string.create_master_key_backup_relay, index + 1) }
        .orEmpty()
    val syncServerTexts = progress?.sources
        ?.filter { it.type == BackupSourceType.BRAVE_SYNC }
        ?.mapIndexed { index, _ -> stringResource(R.string.create_master_key_backup_sync_server, index + 1) }
        .orEmpty()
    val terminalLines = masterKeyCreationTerminalLines(
        stage = state.progressStage,
        progress = progress,
        creatingText = creatingText,
        nostrText = nostrText,
        syncCodeText = syncCodeText,
        relayTexts = relayTexts,
        syncServerTexts = syncServerTexts,
    )
    val complete = state.progressStage == MasterKeyCreationStage.COMPLETE

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_creating_title),
        heroIcon = R.drawable.hero_icon_create_masterkey,
        heroIconDescription = stringResource(R.string.create_master_key_passphrase_icon_description),
        onBack = null,
        content = {
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.create_master_key_creating_warning),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            DecodeRevealTerminal(
                lines = terminalLines,
                accessibilityStatus = if (complete) {
                    stringResource(R.string.create_master_key_created_successfully)
                } else {
                    stringResource(R.string.create_master_key_operation_in_progress)
                },
            )
            if (complete) {
                Spacer(Modifier.height(24.dp))
                MasterKeyCreationSuccess(
                    text = stringResource(R.string.create_master_key_created_successfully),
                )
            }
        },
        actions = {},
    )
}

internal fun masterKeyCreationTerminalLines(
    stage: MasterKeyCreationStage?,
    progress: AccountConsolidationEngine.MasterBackupProgress?,
    creatingText: String,
    nostrText: String,
    syncCodeText: String,
    relayTexts: List<String>,
    syncServerTexts: List<String>,
): List<DecodeRevealLine> = when (stage) {
        MasterKeyCreationStage.DERIVING_NOSTR_KEY_PAIR -> listOf(
            DecodeRevealLine(creatingText, active = false),
            DecodeRevealLine(nostrText, active = true),
        )
        MasterKeyCreationStage.DERIVING_SYNC_CODE -> listOf(
            DecodeRevealLine(creatingText, active = false),
            DecodeRevealLine(nostrText, active = false),
            DecodeRevealLine(syncCodeText, active = true),
        )
        MasterKeyCreationStage.SYNCING_BACKUPS, MasterKeyCreationStage.COMPLETE -> buildList {
            add(DecodeRevealLine(creatingText, active = false))
            add(DecodeRevealLine(nostrText, active = false))
            add(DecodeRevealLine(syncCodeText, active = false))
            val sourceLines = backupSourceLines(progress, relayTexts, syncServerTexts)
            sourceLines.forEachIndexed { index, text ->
                val completed = stage == MasterKeyCreationStage.COMPLETE || index < (progress?.completedSources ?: 0)
                val active = stage == MasterKeyCreationStage.SYNCING_BACKUPS &&
                    index == progress?.activeSourceIndex && !completed
                if (completed || active) add(DecodeRevealLine(text, active = active))
            }
        }
        MasterKeyCreationStage.CREATING_KEY, null -> listOf(
            DecodeRevealLine(creatingText, active = true),
        )
    }

internal fun backupSourceLines(
    progress: AccountConsolidationEngine.MasterBackupProgress?,
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
private fun MasterKeyCreationSuccess(text: String) {
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
