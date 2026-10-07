package app.zenten.vault.ui.accounts

import android.content.ActivityNotFoundException
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
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
    var targets by remember { mutableStateOf<List<LocalAccountBackupShare.ShareTarget>?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    BackHandler { }
    SecureWindow()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Export account", style = MaterialTheme.typography.headlineMedium)
        Text("Export this account's encrypted OpenSSH private key to a trusted notes app or another app. This is required before continuing.")
        if (account == null) Text("Account not found", color = MaterialTheme.colorScheme.error)
        else Button(
            onClick = {
                shareError = null
                targets = LocalAccountBackupShare.availableTargets(context.packageManager)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Choose an app to save key") }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.exportStarted) {
            Text("After saving the key, confirm below. Android cannot verify that the receiving app retained it.")
            Button(
                onClick = viewModel::complete,
                enabled = !state.completing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.completing) "Confirming..." else "I saved my encrypted private key") }
        }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.LOCAL_ACCOUNT_BACKUP_EXPORT) { inclusive = true }
            launchSingleTop = true
        }
    }
    targets?.let { availableTargets ->
        account?.let { exportAccount ->
            ExportTargetDialog(
                targets = availableTargets,
                onDismiss = { targets = null },
                onTargetSelected = { target ->
                    try {
                        context.startActivity(
                            LocalAccountBackupShare.targetedSendIntent(
                                LocalAccountBackupShare.accountExportText(exportAccount),
                                target,
                            ),
                        )
                        targets = null
                        viewModel.recordExportStarted()
                    } catch (_: ActivityNotFoundException) {
                        targets = null
                        shareError = "That app is no longer available. Choose another app."
                    } catch (_: SecurityException) {
                        targets = null
                        shareError = "Android did not allow this app to receive the export. Choose another app."
                    }
                },
            )
        }
    }
}

@Composable
private fun ExportTargetDialog(
    targets: List<LocalAccountBackupShare.ShareTarget>,
    onDismiss: () -> Unit,
    onTargetSelected: (LocalAccountBackupShare.ShareTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save encrypted key with") },
        text = {
            if (targets.isEmpty()) {
                Text("No installed app can receive text exports.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(targets, key = { "${it.packageName}/${it.activityName}" }) { target ->
                        TextButton(
                            onClick = { onTargetSelected(target) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AndroidView(
                                    factory = { ImageView(it) },
                                    update = { imageView ->
                                        imageView.setImageDrawable(
                                            target.resolveInfo.loadIcon(imageView.context.packageManager),
                                        )
                                    },
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(target.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SecureWindow() = Unit
