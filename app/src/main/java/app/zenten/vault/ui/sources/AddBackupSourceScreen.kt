package app.zenten.vault.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBackupSourceScreen(
    navController: NavHostController,
    viewModel: AddBackupSourceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.saved) {
        if (state.saved) navController.popBackStack()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Add Nostr relay") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Display name (optional)") },
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = "Nostr relay",
                onValueChange = {},
                readOnly = true,
                label = { Text("Source type") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::setEndpoint,
                label = { Text("Endpoint URL") },
                placeholder = { Text("wss://relay.example") },
                singleLine = true,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.saving) {
                        viewModel.setAdvancedExpanded(!state.advancedExpanded)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Advanced settings", style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (state.advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (state.advancedExpanded) "Collapse advanced settings" else "Expand advanced settings",
                )
            }
            if (state.advancedExpanded) {
                SettingSwitch(
                    title = "Count toward setup quorum",
                    checked = state.countsTowardQuorum,
                    enabled = !state.saving,
                    onCheckedChange = viewModel::setCountsTowardQuorum,
                )
                SettingSwitch(
                    title = "Use for account recovery",
                    checked = state.useForRecovery,
                    enabled = !state.saving,
                    onCheckedChange = viewModel::setUseForRecovery,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.saving) CircularProgressIndicator() else Text("Add relay")
            }
            OutlinedButton(
                onClick = { navController.popBackStack() },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
