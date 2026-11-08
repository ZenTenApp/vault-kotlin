package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Account
import kotlinx.serialization.Serializable

/**
 * The only sensitive record shape available to trusted sibling applications.
 *
 * Alongside the passphrase-protected SSH private key, sibling apps receive the account's label
 * so they can display or identify it. The label is not secret — it is already readable in
 * plaintext on the account's backup records — and it is the only account metadata exposed.
 * Sibling apps must obtain the passphrase from the user, and neither the account's Nostr
 * identity nor its private key is serialized.
 */
@Serializable
data class SharedAssociatedAccount(
    val createdAt: String,
    val zentenKey: String,
    val accountName: String,
)

fun Account.toSharedAssociatedAccount() = SharedAssociatedAccount(
    createdAt = createdAt.toString(),
    zentenKey = zenTenKey,
    // A restored account can have no label; the wire field is non-null, so it becomes empty text.
    accountName = accountName?.trim().orEmpty(),
)