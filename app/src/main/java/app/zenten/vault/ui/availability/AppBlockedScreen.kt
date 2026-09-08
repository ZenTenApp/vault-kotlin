package app.zenten.vault.ui.availability

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen gate shown instead of navigation until all backup safety prerequisites are met. */
@Composable
fun AppBlockedScreen(blockers: Set<AppAccessBlocker>, onRetry: () -> Unit) {
    val context = LocalContext.current
    val hasInternetProblem = AppAccessBlocker.INTERNET_UNAVAILABLE in blockers
    val hasTimeProblem = AppAccessBlocker.AUTOMATIC_TIME_DISABLED in blockers

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ZenTen Vault is unavailable", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
            Text("Automatic date and time are off", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                "Turn on automatic date and time. Incorrect device time can invalidate backup authentication.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) }) {
                Text("Open date and time settings")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Check again") }
    }
}
