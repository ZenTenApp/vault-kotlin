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
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.data.kv.AccountSourceBackupState

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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = formatShortNpub(account.nostr.pubkey),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = formatAccountId(account.createdAt),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Text(
                    "Created on ${formatAccountCreatedAt(account.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Backup Status", style = MaterialTheme.typography.titleMedium)
        state.sources.forEach { SourceStatusRow(it) }
        Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth()) { Text("Consolidate backup") }
    }
}

@Composable
fun SourceStatusRow(source: BackupSourceRow) {
    val backedUp = source.state == AccountSourceBackupState.PRESENT || source.state == AccountSourceBackupState.WRITE_CONFIRMED
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(source.endpoint, style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        backedUp -> MaterialTheme.colorScheme.primary
                        source.error != null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
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
