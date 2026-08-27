package app.zenten.vault.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.ui.VaultDestinations

@Composable
fun GetStartedScreen(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to ZenTen Vault", style = MaterialTheme.typography.headlineMedium)
        Text("Import an existing Ed25519 OpenSSH private key to create your master account.", modifier = Modifier.padding(top = 12.dp))
        Button(
            onClick = { navController.navigate(VaultDestinations.GET_STARTED_SSH) },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Import SSH Key") }
    }
}
