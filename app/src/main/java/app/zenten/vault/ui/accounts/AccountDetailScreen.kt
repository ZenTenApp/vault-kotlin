package app.zenten.vault.ui.accounts

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.compose.ui.text.AnnotatedString
import app.zenten.vault.crypto.SshKeyCodec

@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long, viewModel: AccountDetailViewModel = hiltViewModel()) {
    val account = remember(accountId) { viewModel.account(accountId) }
    var showPrivate by remember { mutableStateOf(false) }
    var confirmReveal by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    if (account == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Account not found")
            TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(account.createdAt.toString(), style = MaterialTheme.typography.headlineSmall)
        Text("SSH public key", modifier = Modifier.padding(top = 24.dp))
        Text(account.pubkey ?: "Unavailable", style = MaterialTheme.typography.bodySmall)
        account.pubkey?.let { publicLine ->
            val fingerprint = runCatching {
                val wire = java.util.Base64.getDecoder().decode(publicLine.split(" ")[1])
                SshKeyCodec.fingerprint(wire.copyOfRange(wire.size - 32, wire.size))
            }.getOrNull()
            Text("Fingerprint: ${fingerprint ?: "Unavailable"}", modifier = Modifier.padding(top = 12.dp))
        }
        Button(onClick = { confirmReveal = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("Show private key") }
        TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
    if (confirmReveal) AlertDialog(
        onDismissRequest = { confirmReveal = false },
        title = { Text("Show private key?") },
        text = { Text("Anyone who sees this key can access this SSH account.") },
        confirmButton = { TextButton(onClick = { confirmReveal = false; showPrivate = true }) { Text("Show") } },
        dismissButton = { TextButton(onClick = { confirmReveal = false }) { Text("Cancel") } },
    )
    if (showPrivate) PrivateKeyDialog(account.zenTenKey, onCopy = { clipboard.setText(AnnotatedString(account.zenTenKey)) }, onDismiss = { showPrivate = false })
}

@Composable
private fun PrivateKeyDialog(key: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private key") },
        text = { Text(key, style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
