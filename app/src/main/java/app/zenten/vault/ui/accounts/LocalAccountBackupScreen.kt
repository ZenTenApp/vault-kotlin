package app.zenten.vault.ui.accounts

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations

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
    BackHandler { }
    SecureWindow()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Export account", style = MaterialTheme.typography.headlineMedium)
        Text("Export this account's encrypted OpenSSH private key to Notes, Messages, or another app. This is required before continuing.")
        if (account == null) Text("Account not found", color = MaterialTheme.colorScheme.error)
        else Button(onClick = {
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SshKeyCodec.formatAsPem(account.zenTenKey)),
                "Export encrypted SSH private key",
            ))
            viewModel.complete()
        }, modifier = Modifier.fillMaxWidth()) { Text("Export to notes") }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.ACCOUNTS) { inclusive = true }
        }
    }
}

@Composable
private fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
