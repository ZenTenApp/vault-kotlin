package app.zenten.vault.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.ui.VaultDestinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSourcesScreen(
    navController: NavHostController,
    viewModel: BackupSourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup sources") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.sources, key = { it.id }) { source ->
                    BackupSourceCard(source)
                }
                item {
                    Button(
                        onClick = { navController.navigate(VaultDestinations.ADD_BACKUP_SOURCE) },
                        enabled = state.canAddNostr,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text("Add Nostr relay") }
                    if (!state.canAddNostr) {
                        Text(
                            "Maximum of 3 custom relays reached",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSourceCard(source: BackupSourceConfig) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(source.label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (source.type == BackupSourceType.NOSTR_RELAY) "Nostr relay" else "Brave Sync",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(source.endpoint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                if (source.isDefault) "Built-in source · Managed by ZenTen Vault" else "Custom source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!source.isDefault && (source.countsTowardQuorum || source.trustedForRestore)) {
                Text(
                    listOfNotNull(
                        "Setup quorum".takeIf { source.countsTowardQuorum },
                        "Account recovery".takeIf { source.restoreEnabled && source.trustedForRestore },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
