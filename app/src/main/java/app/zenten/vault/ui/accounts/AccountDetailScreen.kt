package app.zenten.vault.ui.accounts

import android.content.ActivityNotFoundException
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import app.zenten.vault.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long, viewModel: AccountDetailViewModel = hiltViewModel()) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    val context = LocalContext.current
    // Target picker shown while the user chooses where to send the account export.
    var exportTargets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    // Passphrase confirmation shown before the export target picker opens.
    var confirmExportPassphrase by remember { mutableStateOf(false) }
    if (account == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Account not found")
            TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
        }
        return
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Account details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to accounts",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountCard(
                account = account,
                displayName = state.profile?.displayName,
                modifier = Modifier.fillMaxWidth(),
            )
            val npub = formatNpub(account.nostr.pubkey)
            NostrIdentityCard(npub = npub)
            SshPublicKeyCard(publicKey = formatSshPublicKey(account.zenTenKey, npub))
            SshKeyCard(
                fingerprint = formatSshFingerprint(account.zenTenKey),
                kdfRounds = formatKdfRounds(account.zenTenKey),
            )
            Button(
                onClick = {
                    exportError = null
                    confirmExportPassphrase = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export account") }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Backup status",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.sources.forEach { SourceStatusRow(it) }
            Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth()) { Text("Consolidate backup") }
        }
    }
    if (confirmExportPassphrase) {
        ExportPassphraseDialog(
            message = "Re-enter the passphrase that protects this account before exporting its encrypted key.",
            onDismiss = { confirmExportPassphrase = false },
            onConfirmed = { passphrase ->
                if (viewModel.verifyExportPassphrase(passphrase)) {
                    exportTargets = LocalAccountBackupShare.availableTargets(context.packageManager)
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
private fun NostrIdentityCard(npub: String) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "ID on NOSTR protocol",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            CopyableIdentityValue(
                value = npub,
                clipboardLabel = "Nostr public key",
                contentDescription = "Copy npub",
            )
        }
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
