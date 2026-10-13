package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Account
import kotlinx.serialization.Serializable

/**
 * The only sensitive record shape available to trusted sibling applications.
 *
 * Alongside the passphrase-protected SSH private key, sibling apps receive the account's public
 * Nostr key so they can address or display the account. Only public key material is exposed:
 * [Account.zenTenPass] and the Nostr private key are never serialized.
 */
@Serializable
data class SharedAssociatedAccount(
    val createdAt: String,
    val zentenKey: String,
    val nostrPubkey: String,
)

fun Account.toSharedAssociatedAccount() = SharedAssociatedAccount(
    createdAt = createdAt.toString(),
    zentenKey = zenTenKey,
    nostrPubkey = nostr.pubkey,
)