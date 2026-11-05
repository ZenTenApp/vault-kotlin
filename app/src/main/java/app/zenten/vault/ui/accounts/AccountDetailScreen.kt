package app.zenten.vault.ui.accounts

import android.content.ActivityNotFoundException
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.ui.components.VaultCard
import app.zenten.vault.ui.components.VaultScreenScaffold
import app.zenten.vault.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch

/**
 * The two voluntary export destinations offered on account details. Both release the same
 * self-describing encrypted-key text, so both are gated behind the same passphrase dialog.
 */
private enum class AccountExportAction { NOTES, CLIPBOARD }

@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long, viewModel: AccountDetailViewModel = hiltViewModel()) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    // Target picker shown while the user chooses where to send the account export.
    var exportTargets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    // Confirmation that the encrypted key was placed on the clipboard, shown after that export.
    var exportCopied by remember { mutableStateOf(false) }
    // Which export the pending passphrase confirmation should run once the user confirms.
    var pendingExportAction by remember { mutableStateOf<AccountExportAction?>(null) }
    // Passphrase confirmation shown before any export action runs.
    var confirmExportPassphrase by remember { mutableStateOf(false) }
    if (account == null) {
        // Degenerate state: keep the same title, Back link, and frame as the loaded screen.
        VaultScreenScaffold(
            title = "Account Details",
            onBack = navController::navigateUp,
            content = {
                Spacer(Modifier.height(16.dp))
                Text("Account not found", color = MaterialTheme.colorScheme.error)
            },
            actions = {},
        )
        return
    }
    VaultScreenScaffold(
        title = "Account Details",
        onBack = navController::navigateUp,
        content = {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountCard(
                account = account,
                modifier = Modifier.fillMaxWidth(),
            )
            // The associated account's Nostr key pair stays internal: no npub is shown, and the
            // SSH public-key line carries no comment instead of linking to that identity.
            SshPublicKeyCard(publicKey = formatSshPublicKey(account.zenTenKey, comment = ""))
            SshKeyCard(
                fingerprint = formatSshFingerprint(account.zenTenKey),
                kdfRounds = formatKdfRounds(account.zenTenKey),
            )
            Button(
                onClick = {
                    exportError = null
                    exportCopied = false
                    pendingExportAction = AccountExportAction.NOTES
                    confirmExportPassphrase = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export Account to Notes") }
            Button(
                onClick = {
                    exportError = null
                    exportCopied = false
                    pendingExportAction = AccountExportAction.CLIPBOARD
                    confirmExportPassphrase = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export Account to Clipboard") }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (exportCopied) {
                Text(
                    text = "Encrypted account key copied to the clipboard.",
                    color = LocalExtendedColors.current.success.color,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Backup status",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.sources.forEach { SourceStatusRow(it) }
                Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth()) { Text("Consolidate backup") }
            }
        },
        actions = {},
    )
    if (confirmExportPassphrase) {
        ExportPassphraseDialog(
            message = "Re-enter the passphrase that protects this account before exporting its encrypted key.",
            onDismiss = {
                confirmExportPassphrase = false
                pendingExportAction = null
            },
            onConfirmed = { passphrase ->
                if (viewModel.verifyExportPassphrase(passphrase)) {
                    // Run only the export the user tapped; the passphrase gate is shared so the
                    // destination is remembered while the dialog is open.
                    when (pendingExportAction) {
                        AccountExportAction.NOTES ->
                            exportTargets = LocalAccountBackupShare.availableTargets(context.packageManager)
                        AccountExportAction.CLIPBOARD -> coroutineScope.launch {
                            runCatching {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText(
                                        "Encrypted account key",
                                        LocalAccountBackupShare.accountKeyPemText(account),
                                    ).toClipEntry(),
                                )
                            }.onSuccess {
                                exportCopied = true
                            }.onFailure {
                                exportError = "Vault could not copy the account key to the clipboard. Try again."
                            }
                        }
                        null -> Unit
                    }
                    true
                } else {
                    false
                }
            },
        )
    }
    exportTargets?.let { availableTargets ->
        ExportTargetDialog(
            targets = availableTargets,
            onDismiss = { exportTargets = null },
            onTargetSelected = { target ->
                try {
                    context.startActivity(
                        LocalAccountBackupShare.targetedSendIntent(
                            LocalAccountBackupShare.accountExportText(account),
                            target,
                        ),
                    )
                    exportTargets = null
                } catch (_: ActivityNotFoundException) {
                    exportTargets = null
                    exportError = "That app is no longer available. Choose another app."
                } catch (_: SecurityException) {
                    exportTargets = null
                    exportError = "Android did not allow this app to receive the export. Choose another app."
                }
            },
        )
    }
}

@Composable
private fun SshPublicKeyCard(publicKey: String) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SSH public key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            CopyableIdentityValue(
                value = publicKey,
                clipboardLabel = "SSH public key",
                contentDescription = "Copy SSH public key",
                breakAnywhere = true,
            )
        }
    }
}

@Composable
private fun SshKeyCard(fingerprint: String, kdfRounds: String) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SSH key fingerprint",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            CopyableIdentityValue(
                value = fingerprint,
                clipboardLabel = "SSH key fingerprint",
                contentDescription = "Copy SSH key fingerprint",
            )
            Text(
                text = "KDF rounds: $kdfRounds",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CopyableIdentityValue(
    value: String,
    clipboardLabel: String,
    contentDescription: String,
    breakAnywhere: Boolean = false,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val isAvailable = value != "Unavailable"
    val copyValue = {
        coroutineScope.launch {
            clipboard.setClipEntry(ClipData.newPlainText(clipboardLabel, value).toClipEntry())
        }
        Unit
    }
    val copyIconId = "copyIcon"
    Text(
        text = buildAnnotatedString {
            if (breakAnywhere) {
                // Zero-width breaks after every character let the line breaker wrap at any
                // character instead of only spaces/slashes, so lines fill evenly.
                value.forEach { append(it.toString()); append('\u200B') }
            } else {
                append(value)
            }
            append(" ")
            appendInlineContent(copyIconId, alternateText = "copy")
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = isAvailable,
                onClickLabel = contentDescription,
                onClick = copyValue,
            ),
        style = MaterialTheme.typography.bodyLarge.copy(
            lineBreak = if (breakAnywhere) LineBreak.Simple else LineBreak.Unspecified,
        ),
        inlineContent = mapOf(
            copyIconId to InlineTextContent(
                placeholder = Placeholder(
                    width = 1.2.em,
                    height = 1.2.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ),
    )
}

@Composable
fun SourceStatusRow(source: BackupSourceRow) {
    val statusText = when (source.state) {
        AccountSourceBackupState.PRESENT, AccountSourceBackupState.WRITE_CONFIRMED -> source.lastSuccessfulBackupAtMillis?.let {
            "Backed up ${formatRelativeTime(it)}"
        } ?: "Backed up"
        AccountSourceBackupState.MISSING -> "Missing"
        AccountSourceBackupState.CHECKING -> "Checking"
        AccountSourceBackupState.BACKING_UP -> "Backing up"
        AccountSourceBackupState.BACKUP_FAILED -> source.error?.let { "Backup failed: ${formatBackupError(it)}" } ?: "Backup failed"
        AccountSourceBackupState.UNKNOWN -> "Unavailable / unknown"
    }
    val (statusIcon, statusColor) = backupStatusStyle(source.state)
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = source.endpoint,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun backupStatusStyle(state: AccountSourceBackupState): Pair<ImageVector, Color> = when (state) {
    AccountSourceBackupState.PRESENT,
    AccountSourceBackupState.WRITE_CONFIRMED,
    -> Icons.Outlined.CheckCircle to LocalExtendedColors.current.success.color
    AccountSourceBackupState.BACKUP_FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    AccountSourceBackupState.MISSING -> Icons.Outlined.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
    AccountSourceBackupState.CHECKING,
    AccountSourceBackupState.BACKING_UP,
    -> Icons.Outlined.Sync to MaterialTheme.colorScheme.primary
    AccountSourceBackupState.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun formatSshPublicKey(privateKey: String?, comment: String): String =
    privateKey?.let {
        runCatching { SshKeyCodec.publicKeyLine(SshKeyCodec.publicKeyFromEncryptedPrivateKey(it), comment) }
            .getOrNull()
    } ?: "Unavailable"

internal fun formatSshFingerprint(privateKey: String?): String =
    privateKey?.let { runCatching { SshKeyCodec.fingerprintFromEncryptedPrivateKey(it) }.getOrNull() }
        ?: "Unavailable"

internal fun formatKdfRounds(privateKey: String?): String =
    privateKey?.let { runCatching { SshKeyCodec.bcryptKdfRounds(it).toString() }.getOrNull() }
        ?: "Unavailable"

internal fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val elapsedMinutes = ((nowMillis - timestampMillis).coerceAtLeast(0) / 60_000).toInt()
    return when {
        elapsedMinutes < 1 -> "just now"
        elapsedMinutes < 60 -> "$elapsedMinutes min ago"
        elapsedMinutes < 24 * 60 -> "${elapsedMinutes / 60}h ago"
        elapsedMinutes < 7 * 24 * 60 -> "${elapsedMinutes / (24 * 60)}d ago"
        elapsedMinutes < 4 * 7 * 24 * 60 -> "${elapsedMinutes / (7 * 24 * 60)}w ago"
        elapsedMinutes < 12 * 4 * 7 * 24 * 60 -> "${elapsedMinutes / (4 * 7 * 24 * 60)}m ago"
        else -> "${elapsedMinutes / (12 * 4 * 7 * 24 * 60)}y ago"
    }
}

private fun formatBackupError(error: app.zenten.vault.data.kv.BackupErrorCategory): String =
    error.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
