package app.zenten.vault.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.zenten.vault.data.model.Account
import app.zenten.vault.ui.VaultDestinations

@Composable
fun AccountsListScreen(navController: NavHostController, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(VaultDestinations.NEW_ACCOUNT) }) {
                Text("+")
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("My Accounts", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { navController.navigate(VaultDestinations.MASTER_ACCOUNT) }) { Text("Master Account") }
            }
            Text("Your generated SSH accounts", style = MaterialTheme.typography.bodyMedium)
            if (state.recoveryInProgress) {
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(state.recoveryMessage ?: "Recovering accounts from backup…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                state.recoveryMessage?.let { message ->
                    Text(message, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.loading) Text("Loading…", modifier = Modifier.padding(top = 24.dp))
            else if (state.accounts.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
                    Text("Create a new SSH account to get started.")
                    Button(onClick = { navController.navigate(VaultDestinations.NEW_ACCOUNT) }, modifier = Modifier.padding(top = 16.dp)) { Text("Add Account") }
                }
            } else LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts, key = { it.createdAt }) { account ->
                    AccountCard(account) { navController.navigate(VaultDestinations.accountDetail(account.createdAt)) }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            account.createdAt.toString(),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
