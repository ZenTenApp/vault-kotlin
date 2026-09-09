package app.zenten.vault.ui.availability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen gate shown instead of navigation until all backup safety prerequisites are met. */
@Composable
fun AppBlockedScreen(
    blockers: Set<AppAccessBlocker>,
    isChecking: Boolean,
    onRetry: () -> Unit,
) {
    val hasInternetProblem = AppAccessBlocker.INTERNET_UNAVAILABLE in blockers
    val hasTimeProblem = AppAccessBlocker.DEVICE_TIME_INCORRECT in blockers

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (hasTimeProblem) "Device time is incorrect" else "ZenTen Vault is unavailable",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (hasInternetProblem) {
            Text("No verified internet connection", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect to the internet and complete any Wi-Fi sign-in page. The vault remains locked while the connection cannot reach the internet.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (hasInternetProblem && hasTimeProblem) Spacer(Modifier.height(20.dp))
        if (hasTimeProblem) {
            Text("Device time does not match trusted server time", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                "Correct your device date and time. An incorrect clock can invalidate backup authentication.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, enabled = !isChecking) {
            Text(if (isChecking) "Checking…" else "Check again")
        }
    }
}
