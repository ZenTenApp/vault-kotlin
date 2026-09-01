package app.zenten.vault.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountProfile
import app.zenten.vault.ui.VaultDestinations
import coil.compose.rememberAsyncImagePainter
import java.text.DateFormat
import java.util.Base64
import java.util.Date

private val AccountCardColors = listOf(
    Color(0xFF164E63),
    Color(0xFF1E3A8A),
    Color(0xFF3730A3),
    Color(0xFF581C87),
    Color(0xFF831843),
    Color(0xFF7F1D1D),
    Color(0xFF7C2D12),
    Color(0xFF713F12),
    Color(0xFF365314),
    Color(0xFF14532D),
    Color(0xFF134E4A),
    Color(0xFF334155),
)

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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(VaultDestinations.MASTER_ACCOUNT) },
                    icon = {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                        )
                    },
                    text = { Text("Master account") },
                )
                FloatingActionButton(onClick = { navController.navigate(VaultDestinations.NEW_ACCOUNT) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add account")
                }
            }
        },
    ) { innerPadding ->
        when {
            state.loading && state.accounts.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.accounts.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 152.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = newestFirst(state.accounts),
                    key = { it.createdAt },
                ) { account ->
                    AccountCard(account, state.profiles[account.createdAt]) {
                        navController.navigate(VaultDestinations.accountDetail(account.createdAt))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account, profile: AccountProfile?, onClick: () -> Unit) {
    val foreground = Color.White
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accountCardColor(account.createdAt)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
    ) {
        Box {
            profile?.bannerUrl?.let { bannerUrl ->
                Image(
                    painter = rememberAsyncImagePainter(bannerUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = formatAccountId(account.createdAt),
                    color = foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
                profile?.displayName?.let { displayName ->
                    Text(
                        text = displayName,
                        color = foreground.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = accountFingerprint(account.pubkey),
                        color = foreground.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Text(
                        text = formatAccountCreatedAt(account.createdAt),
                        color = foreground.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun formatAccountId(accountId: Long): String =
    accountId.toString().chunked(4).joinToString(" ")

internal fun newestFirst(accounts: List<Account>): List<Account> =
    accounts.sortedByDescending { it.createdAt }

internal fun formatAccountCreatedAt(accountId: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(accountId / 1_000L))

internal fun accountFingerprint(publicKeyLine: String?): String {
    val fingerprint = publicKeyLine?.let { line ->
        runCatching {
            val encodedKey = line.trim().split(Regex("\\s+")).getOrNull(1) ?: error("Missing public key")
            val wire = Base64.getDecoder().decode(encodedKey)
            SshKeyCodec.fingerprint(wire.copyOfRange(wire.size - 32, wire.size))
        }.getOrNull()
    } ?: return "Fingerprint unavailable"
    return "${fingerprint.take(4)}...${fingerprint.takeLast(4)}"
}

internal fun accountCardColor(accountId: Long): Color {
    val colorIndex = Math.floorMod(accountId, AccountCardColors.size.toLong()).toInt()
    return AccountCardColors[colorIndex]
}
