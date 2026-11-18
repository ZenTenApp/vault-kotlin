package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Key
import kotlinx.serialization.Serializable

/**
 * The only sensitive record shape available to trusted sibling applications.
 *
 * Alongside the passphrase-protected SSH private key, sibling apps receive the key's label
 * so they can display or identify it. The label is not secret — it is already readable in
 * plaintext on the key's backup records — and it is the only key metadata exposed.
 * Sibling apps must obtain the passphrase from the user, and neither the key's Nostr
 * identity nor its private key is serialized.
 */
@Serializable
data class SharedAssociatedKey(
    val createdAt: String,
    val zentenKey: String,
    val keyName: String,
)

fun Key.toSharedAssociatedKey() = SharedAssociatedKey(
    createdAt = createdAt.toString(),
    zentenKey = encryptedKey,
    // A restored key can have no label; the wire field is non-null, so it becomes empty text.
    keyName = keyName?.trim().orEmpty(),
)