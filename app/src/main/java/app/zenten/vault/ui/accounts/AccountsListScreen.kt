package app.zenten.vault.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.zenten.vault.data.model.Account
import app.zenten.vault.ui.VaultDestinations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            FloatingActionButton(
                onClick = { navController.navigate(VaultDestinations.NEW_ACCOUNT) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = { navController.navigate(VaultDestinations.MASTER_ACCOUNT) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                Text(
                    text = "Master account",
                    modifier = Modifier.padding(start = 10.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            when {
                state.loading && state.accounts.isEmpty() -> Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.accounts.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    accountsByMonth(state.accounts).forEach { (month, accounts) ->
                        item(key = month) { MonthSeparator(month) }
                        items(items = accounts, key = { it.createdAt }) { account ->
                            AccountCard(
                                account = account,
                                displayName = state.profiles[account.createdAt]?.displayName,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { navController.navigate(VaultDestinations.accountDetail(account.createdAt)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSeparator(month: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = month,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

internal fun newestFirst(accounts: List<Account>): List<Account> =
    accounts.sortedByDescending { it.createdAt }

internal fun accountsByMonth(accounts: List<Account>): List<Pair<String, List<Account>>> =
    newestFirst(accounts).groupBy { account -> formatAccountMonth(account.createdAt) }.toList()

internal fun formatAccountMonth(accountId: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(accountId / 1_000L))
