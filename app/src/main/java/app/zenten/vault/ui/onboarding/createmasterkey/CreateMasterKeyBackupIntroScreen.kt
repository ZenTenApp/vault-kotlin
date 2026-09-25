package app.zenten.vault.ui.onboarding.createmasterkey

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.accounts.LocalAccountBackupShare
import app.zenten.vault.ui.components.VaultPillButton

/** Step 4: export the generated MasterKey through the local-backup target picker. */
@Composable
fun CreateMasterKeyBackupIntroScreen(
    navController: NavHostController,
    viewModel: CreateMasterKeyBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var targets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var exportError by remember { mutableStateOf<Int?>(null) }

    // Generation is irreversible; users may only go back after reaching confirmation.
    BackHandler(enabled = true) {}
    ScreenshotProtection()

    LaunchedEffect(state.navigateToConfirmation) {
        if (state.navigateToConfirmation) {
            viewModel.onConfirmationShown()
            navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_BACKUP)
        }
    }

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_backup_intro_title),
        heroIcon = R.drawable.hero_icon_create_backup,
        heroIconDescription = stringResource(R.string.create_master_key_backup_intro_icon_description),
        onBack = null,
        content = {
            Spacer(Modifier.height(12.dp))
            CreateMasterKeyDescription(stringResource(R.string.create_master_key_backup_intro_body1))
            Spacer(Modifier.height(12.dp))
            CreateMasterKeyDescription(stringResource(R.string.create_master_key_backup_intro_body2))
            exportError?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            CreateMasterKeyWarningRow(centered = true)
            Spacer(Modifier.height(16.dp))
            VaultPillButton(
                label = stringResource(R.string.create_master_key_backup_intro_confirm),
                enabled = !state.advancing,
                onClick = {
                    exportError = null
                    if (viewModel.masterKeyForExport() == null) {
                        exportError = R.string.create_master_key_backup_unavailable
                    } else {
                        targets = LocalAccountBackupShare.availableTargets(context.packageManager)
                    }
                },
            )
        },
    )

    targets?.let { availableTargets ->
        BackupExportTargetDialog(
            targets = availableTargets,
            onDismiss = { targets = null },
            onTargetSelected = { target ->
                val masterKey = viewModel.masterKeyForExport()
                if (masterKey == null) {
                    targets = null
                    exportError = R.string.create_master_key_backup_unavailable
                    return@BackupExportTargetDialog
                }
                try {
                    context.startActivity(LocalAccountBackupShare.targetedSendIntent(masterKey, target))
                    targets = null
                    viewModel.recordExportStarted()
                } catch (_: ActivityNotFoundException) {
                    targets = null
                    exportError = R.string.create_master_key_backup_target_missing
                } catch (_: SecurityException) {
                    targets = null
                    exportError = R.string.create_master_key_backup_target_denied
                }
            },
        )
    }
}
