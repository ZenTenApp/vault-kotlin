package app.zenten.vault.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.zenten.vault.data.kv.AccountBackupStatus
import app.zenten.vault.data.kv.BackupCoverageState
import app.zenten.vault.domain.BackupSyncService

@Composable
fun BackupStatusScreen(viewModel: BackupStatusViewModel = hiltViewModel()) {
    val statuses by viewModel.statuses.collectAsState()
    val syncing = viewModel.syncing
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Backup Status", style = MaterialTheme.typography.headlineMedium)
                Text("Coverage across every backup destination", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = viewModel::syncNow, enabled = !syncing) { Text("Sync now") }
        }
        if (syncing) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Reconciling backups…")
        }
        if (statuses.isEmpty() && !syncing) Text("No backup status yet. Tap Sync now to check coverage.", Modifier.padding(top = 24.dp))
        LazyColumn(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(statuses, key = { it.accountId }) { StatusCard(it) }
        }
    }
}

@Composable
private fun StatusCard(status: AccountBackupStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (status.accountId == BackupSyncService.MASTER_ID) "Master account" else status.accountId,
                style = MaterialTheme.typography.titleMedium)
            status.destinations.forEach { destination ->
                val marker = when (destination.state) {
                    BackupCoverageState.PRESENT -> "✓"
                    BackupCoverageState.MISSING -> "○"
                    BackupCoverageState.UNKNOWN -> "?"
                    BackupCoverageState.REPAIR_FAILED -> "!"
                }
                Text("$marker ${destination.label}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
