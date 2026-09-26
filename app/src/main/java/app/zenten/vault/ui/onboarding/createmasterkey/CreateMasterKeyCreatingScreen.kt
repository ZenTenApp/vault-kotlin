package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations

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
                viewModel.onBackupStepShown()
                navController.navigate(VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO) {
                    popUpTo(VaultDestinations.CREATE_MASTER_KEY_CREATING) { inclusive = true }
                }
            }
            state.generationFailed -> navController.popBackStack()
        }
    }

    val progress = state.backupProgress
    val stageText = when (state.progressStage) {
        MasterKeyCreationStage.CREATING_KEY -> stringResource(R.string.create_master_key_creating_key)
        MasterKeyCreationStage.SAVING_KEY -> stringResource(R.string.create_master_key_saving_key)
        MasterKeyCreationStage.SYNCING_BACKUPS -> if (progress == null) {
            stringResource(R.string.create_master_key_syncing_backups)
        } else {
            stringResource(
                R.string.create_master_key_syncing_backup_source,
                progress.sourceLabel,
                progress.completedSources,
                progress.totalSources,
            )
        }
        null -> stringResource(R.string.create_master_key_creating_key)
    }

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_creating_title),
        heroIcon = R.drawable.hero_icon_passphrase,
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
            LinearProgressIndicator(
                progress = {
                    progress?.let {
                        if (it.totalSources == 0) 0f
                        else it.completedSources.toFloat() / it.totalSources
                    } ?: 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stageText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = {},
    )
}
