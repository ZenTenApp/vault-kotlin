package com.zenten.vault.ui.newaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController

@Composable
fun NewAccountScreen(navController: NavHostController, viewModel: NewAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.created) { if (state.created != null) navController.popBackStack() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Create new SSH account", style = MaterialTheme.typography.headlineMedium)
        Text("A fresh Ed25519 OpenSSH key pair will be generated and backed up to relays. If the relay backup is unavailable, it will retry automatically.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = viewModel::create, modifier = Modifier.fillMaxWidth(), enabled = !state.creating) {
            if (state.creating) CircularProgressIndicator() else Text("Create account")
        }
        Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth(), enabled = !state.creating) { Text("Cancel") }
    }
}
