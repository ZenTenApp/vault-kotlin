package app.zenten.vault.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AppLockedScreen(
    isAuthenticating: Boolean,
    onUnlock: () -> Unit,
) {
    UnlockFrame(
        title = "ZenTen Vault is locked",
        message = "Use your device PIN, pattern, password, or strong biometric to continue.",
    ) {
        Button(onClick = onUnlock, enabled = !isAuthenticating) {
            Text(if (isAuthenticating) "Unlocking…" else "Unlock")
        }
    }
}

@Composable
fun DeviceLockRequiredScreen(
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    UnlockFrame(
        title = "Set up a device screen lock",
        message = "ZenTen Vault requires a PIN, pattern, password, or supported biometric lock before it can be used.",
    ) {
        Button(onClick = onOpenSettings) { Text("Open security settings") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
    }
}

@Composable
private fun UnlockFrame(
    title: String,
    message: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        actions()
    }
}
