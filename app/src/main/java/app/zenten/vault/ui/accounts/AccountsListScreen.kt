package app.zenten.vault.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.zenten.vault.data.model.Account
import app.zenten.vault.ui.VaultDestinations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Accounts",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                    )
                },
                actions = {
                    IconButton(onClick = { navController.navigate(VaultDestinations.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open settings",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading && state.accounts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.accounts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Create an account to get started.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 128.dp),
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
                                dateOnly = true,
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Button(
                    onClick = { navController.navigate(VaultDestinations.NEW_ACCOUNT) },
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .height(56.dp)
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(18.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create New Account",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
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
