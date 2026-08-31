package app.zenten.vault.ui.newaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

@Composable
fun NewAccountScreen(navController: NavHostController, viewModel: NewAccountViewModel = hiltViewModel()) {
    var masterPassphrase by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(state.created) {
        state.created?.let { account ->
            navController.navigate(VaultDestinations.localAccountBackupExport(account.createdAt)) {
                popUpTo(VaultDestinations.NEW_ACCOUNT) { inclusive = true }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Create new SSH account", style = MaterialTheme.typography.headlineMedium)
        Text("A fresh Ed25519 OpenSSH key pair will be generated and backed up to relays. If the relay backup is unavailable, it will retry automatically.")
        OutlinedTextField(
            value = masterPassphrase,
            onValueChange = { masterPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Master account passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.creating,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.create(masterPassphrase) },
            modifier = Modifier.fillMaxWidth(),
            enabled = masterPassphrase.isNotBlank() && !state.creating,
        ) {
            if (state.creating) CircularProgressIndicator() else Text("Create account")
        }
        Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth(), enabled = !state.creating) { Text("Cancel") }
    }
}
