package app.zenten.vault.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.ui.components.VaultCard
import app.zenten.vault.ui.theme.VaultColors

@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long, viewModel: AccountDetailViewModel = hiltViewModel()) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    if (account == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Account not found")
            TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Account details", style = MaterialTheme.typography.headlineMedium)
        AccountCard(
            account = account,
            displayName = state.profile?.displayName,
            modifier = Modifier.fillMaxWidth(),
        )
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
    -> Icons.Outlined.CheckCircle to VaultColors.Success
    AccountSourceBackupState.BACKUP_FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    AccountSourceBackupState.MISSING -> Icons.Outlined.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
    AccountSourceBackupState.CHECKING,
    AccountSourceBackupState.BACKING_UP,
    -> Icons.Outlined.Sync to MaterialTheme.colorScheme.primary
    AccountSourceBackupState.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
}

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
