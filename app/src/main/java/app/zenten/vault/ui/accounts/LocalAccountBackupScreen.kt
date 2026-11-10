package app.zenten.vault.ui.accounts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.PasteError
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultScreenScaffold

/**
 * Step 1 of the mandatory associated-account backup: send the encrypted key to a
 * trusted app.
 *
 * This is a two-screen flow. This screen only performs the export; once Android
 * accepts the target intent the user continues to [LocalAccountBackupConfirmScreen]
 * to paste the saved key back. System Back is blocked because the export cannot be
 * skipped before the account may be used.
 */
@Composable
fun LocalAccountBackupExportScreen(
    navController: NavHostController,
    accountId: Long,
    viewModel: LocalAccountBackupViewModel = hiltViewModel(),
) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    // The export is required before the account can be used, so Back is inert.
    BackHandler { }

    VaultScreenScaffold(
        title = "Export Account",
        onBack = null,
        hero = {
            ExportHero(
                drawableId = R.drawable.hero_icon_create_backup,
                description = stringResource(R.string.create_vault_key_backup_intro_icon_description),
            )
        },
        content = {
            Spacer(Modifier.height(12.dp))
            ExportDescription("Export this account's encrypted OpenSSH private key to a trusted notes app or another app. This is required before continuing.")
            if (account == null) {
                Spacer(Modifier.height(12.dp))
                Text("Account not found", color = MaterialTheme.colorScheme.error)
            }
            shareError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            VaultPillButton(
                label = "Export to Notes",
                enabled = account != null,
                onClick = {
                    shareError = null
                    targets = LocalAccountBackupShare.availableTargets(context.packageManager)
                },
            )
        },
    )

    targets?.let { availableTargets ->
        account?.let { exportAccount ->
            ExportTargetDialog(
                targets = availableTargets,
                onDismiss = { targets = null },
                onTargetSelected = { target ->
                    try {
                        context.startActivity(
                            LocalAccountBackupShare.targetedSendIntent(
                                LocalAccountBackupShare.accountExportText(exportAccount),
                                target,
                            ),
                        )
                        targets = null
                        navController.navigate(VaultDestinations.localAccountBackupConfirm(exportAccount.createdAt)) {
                            launchSingleTop = true
                        }
                    } catch (_: ActivityNotFoundException) {
                        targets = null
                        shareError = "That app is no longer available. Choose another app."
                    } catch (_: SecurityException) {
                        targets = null
                        shareError = "Android did not allow this app to receive the export. Choose another app."
                    }
                },
            )
        }
    }
}

/**
 * Step 2 of the mandatory associated-account backup: paste the exported key back.
 *
 * The paste proves the user kept a readable copy before the account is marked as
 * backed up. Back returns to the export screen so the key can be sent again if the
 * user did not receive it.
 */
@Composable
fun LocalAccountBackupConfirmScreen(
    navController: NavHostController,
    accountId: Long,
    viewModel: LocalAccountBackupViewModel = hiltViewModel(),
) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var confirmError by remember { mutableStateOf<PasteError?>(null) }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    VaultScreenScaffold(
        title = "Confirm Backup",
        onBack = { navController.popBackStack() },
        hero = {
            ExportHero(
                drawableId = R.drawable.hero_icon_confirm_backup,
                description = stringResource(R.string.create_vault_key_confirm_backup_icon_description),
            )
        },
        content = {
            Spacer(Modifier.height(12.dp))
            ExportDescription("Copy the saved key back from the app you sent it to, then paste it below so Vault can confirm you kept it.")
            confirmError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it.render(), color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            VaultPillButton(
                label = if (state.completing) "Confirming..." else "Paste Account Key",
                enabled = state.account != null && !state.completing,
                onClick = {
                    confirmError = null
                    val pastedKey = runCatching {
                        clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                    }.getOrElse {
                        confirmError = PasteError(R.string.local_account_confirm_backup_read_error)
                        return@VaultPillButton
                    }
                    // Clear the clipboard immediately so the sensitive export is not left behind.
                    val clipboardCleared = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }.isSuccess
                    if (!clipboardCleared) {
                        confirmError = PasteError(R.string.local_account_confirm_backup_clear_error)
                        return@VaultPillButton
                    }
                    if (pastedKey.isBlank()) {
                        confirmError = PasteError(R.string.local_account_confirm_backup_empty_error)
                        return@VaultPillButton
                    }
                    when (val result = viewModel.confirmPastedAccountKey(pastedKey)) {
                        AccountBackupConfirmationResult.MATCHED -> viewModel.complete()
                        AccountBackupConfirmationResult.INVALID ->
                            confirmError = PasteError(R.string.local_account_confirm_backup_invalid_error)
                        is AccountBackupConfirmationResult.UnsupportedKdfRounds ->
                            // A structurally valid key whose declared KDF cost is out of band: the
                            // generic "unsupported key" wording would misdescribe it.
                            confirmError = PasteError(
                                R.string.local_account_confirm_backup_kdf_rounds_error,
                                result.declaredRounds,
                            )
                        AccountBackupConfirmationResult.MISMATCHED ->
                            confirmError = PasteError(R.string.local_account_confirm_backup_mismatch_error)
                        AccountBackupConfirmationResult.UNAVAILABLE ->
                            confirmError = PasteError(R.string.local_account_confirm_backup_unavailable_error)
                    }
                },
            )
        },
    )

    LaunchedEffect(state.completed) {
        if (state.completed) {
            navController.navigate(VaultDestinations.ACCOUNTS) {
                // Pops both the export and confirm steps so Back from Accounts cannot re-enter them.
                popUpTo(VaultDestinations.LOCAL_ACCOUNT_BACKUP_EXPORT) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}

/** Centered supporting copy shared by the account backup steps. */
@Composable
private fun ExportDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Account-backup hero illustration.
 *
 * The asset is multi-tone line art that owns its colors, so it is drawn with [Image]
 * (never tinted by the theme) at the same 132dp footprint the wizard reserves.
 */
@Composable
private fun ExportHero(@DrawableRes drawableId: Int, description: String) {
    Image(
        painter = painterResource(drawableId),
        contentDescription = description,
        modifier = Modifier.size(132.dp),
    )
}

@Composable
internal fun ExportTargetDialog(
    targets: List<LocalAccountBackupShare.ShareTarget>,
    onDismiss: () -> Unit,
    onTargetSelected: (LocalAccountBackupShare.ShareTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Account") },
        text = {
            if (targets.isEmpty()) {
                Text("No installed app can receive text exports.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(targets, key = { "${it.packageName}/${it.activityName}" }) { target ->
                        TextButton(
                            onClick = { onTargetSelected(target) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AndroidView(
                                    factory = { ImageView(it) },
                                    update = { imageView ->
                                        imageView.setImageDrawable(
                                            target.resolveInfo.loadIcon(imageView.context.packageManager),
                                        )
                                    },
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(target.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
