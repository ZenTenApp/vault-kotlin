package app.zenten.vault.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.hexToBytes
import app.zenten.vault.ui.components.VaultCard
import app.zenten.vault.ui.theme.VaultColors
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountCard(
    account: Account,
    displayName: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    VaultCard(modifier = cardModifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = accountCardIdentity(displayName, account.nostr.pubkey),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                accountIdChunks(account.createdAt).forEach { chunk ->
                    Text(
                        text = chunk,
                        color = VaultColors.TextStrong,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = "Created on ${formatAccountCreatedAt(account.createdAt)}",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
            )
        }
    }
}

internal fun accountIdChunks(accountId: Long): List<String> = accountId.toString().chunked(4)

internal fun formatAccountId(accountId: Long): String = accountIdChunks(accountId).joinToString(" ")

internal fun formatNpub(publicKey: String): String =
    runCatching { publicKey.hexToBytes().toNpub() }
        .getOrNull()
        ?: "Unavailable"

internal fun formatShortNpub(publicKey: String): String =
    formatNpub(publicKey).let { npub ->
        if (npub == "Unavailable") npub else "${npub.take(4)}...${npub.takeLast(4)}"
    }

internal fun accountCardIdentity(displayName: String?, publicKey: String): String =
    displayName?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length > 16) "${it.take(16)}..." else it
    } ?: formatShortNpub(publicKey)

internal fun formatAccountCreatedAt(accountId: Long): String =
    SimpleDateFormat("EEEE, MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(accountId / 1_000L))
