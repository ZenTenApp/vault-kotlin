package app.zenten.vault.ui.accounts

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.domain.hexToBytes
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import java.text.DateFormat
import java.util.Date

@Composable
fun AccountDetailScreen(navController: NavHostController, accountId: Long, viewModel: AccountDetailViewModel = hiltViewModel()) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
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
        Text("Nostr public key", modifier = Modifier.padding(top = 24.dp))
        SelectionContainer {
            Text(
                runCatching { account.nostr.pubkey.hexToBytes().toNpub() }.getOrNull() ?: "Unavailable",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { confirmReveal = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("Show private key") }
        Text("Backup sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        state.sources.forEach { SourceStatusRow(it) }
        Button(onClick = viewModel::consolidate, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Consolidate backup") }
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
fun SourceStatusRow(source: BackupSourceRow) {
    val backedUp = source.state == AccountSourceBackupState.PRESENT || source.state == AccountSourceBackupState.WRITE_CONFIRMED
    val stateText = when (source.state) {
        AccountSourceBackupState.PRESENT, AccountSourceBackupState.WRITE_CONFIRMED -> "Backed up"
        AccountSourceBackupState.MISSING -> "Missing"
        AccountSourceBackupState.CHECKING -> "Checking"
        AccountSourceBackupState.BACKING_UP -> "Backing up"
        AccountSourceBackupState.BACKUP_FAILED -> "Backup failed"
        AccountSourceBackupState.UNKNOWN -> "Unavailable / unknown"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(source.label, style = MaterialTheme.typography.titleSmall)
        Text(source.endpoint, style = MaterialTheme.typography.bodySmall)
        Text(stateText)
        if (backedUp) {
            source.lastSuccessfulBackupAtMillis?.let {
                Text("Last backed up: ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            source.lastCheckedAtMillis?.let {
                Text("Last checked: ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
            }
        }
        source.error?.let { Text("Issue: ${it.name.lowercase().replace('_', ' ')}", color = MaterialTheme.colorScheme.error) }
    }
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
