package app.zenten.vault.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zenten.vault.data.model.Account
import app.zenten.vault.ui.components.VaultCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountCard(
    account: Account,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // The compact list view shows only the date; the detail view keeps the full date and time.
    dateOnly: Boolean = false,
) {
    val cardModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    VaultCard(modifier = cardModifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The name is mandatory for newly created accounts; only a restored record without a
            // label can leave it empty. That card still reserves the name row's height so cards
            // stay vertically aligned, but shows neither the avatar nor any identifier in its place.
            val name = accountCardIdentity(account)
            if (name.isEmpty()) {
                Spacer(Modifier.height(NAME_ROW_HEIGHT))
            } else {
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
                        text = name,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                accountIdChunks(account.createdAt).forEach { chunk ->
                    Text(
                        text = chunk,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = "Created on " +
                    if (dateOnly) formatAccountCreatedDate(account.createdAt) else formatAccountCreatedAt(account.createdAt),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Height of the card's name row, including the avatar icon's default 24dp size.
 *
 * A card whose account has no name renders a spacer of this height instead of the row, so the
 * creation ID and date below line up with named cards in the same list.
 */
private val NAME_ROW_HEIGHT = 24.dp

internal fun accountIdChunks(accountId: Long): List<String> = accountId.toString().chunked(4)

internal fun formatAccountId(accountId: Long): String = accountIdChunks(accountId).joinToString(" ")

/**
 * Picks the name a card shows: the account's own stored name, or an empty string when it has none.
 *
 * The name is restored alongside the key from a backup record (a `label` tag on relays or a note
 * on Brave Sync). The shortened npub is deliberately never used as a fallback: an associated
 * account must not be presented as a Nostr identity. A restored record without a label therefore
 * renders no name at all.
 */
internal fun accountCardIdentity(account: Account): String =
    account.accountName?.trim().orEmpty()

internal fun formatAccountCreatedAt(accountId: Long): String =
    SimpleDateFormat("EEEE, MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(accountId / 1_000L))

internal fun formatAccountCreatedDate(accountId: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(accountId / 1_000L))
