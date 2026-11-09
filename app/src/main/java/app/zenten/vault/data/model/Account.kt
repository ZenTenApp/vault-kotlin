package app.zenten.vault.data.model

import kotlinx.serialization.Serializable

/**
 * Sensitive vault record. [createdAt] is the canonical account ID in microseconds.
 *
 * Only the passphrase-encrypted OpenSSH key is stored; the passphrase itself is never persisted.
 * It is entered once per unlocked session and held in memory by
 * [app.zenten.vault.domain.VaultKeySession].
 *
 * Every value derived from the seed inside [zenTenKey] — the SSH public key, its fingerprint, and
 * the Nostr identity that signs and encrypts this account's backup records — is derived on demand
 * by the flow that needs it. None is persisted: a derived value stored beside its source can drift
 * from it, and a stored Nostr private key would sit under Keystore-only encryption rather than the
 * passphrase protecting the SSH container.
 */
@Serializable
data class Account(
    val createdAt: Long,
    val zenTenKey: String,
    val accountName: String? = null,
)

@Serializable
data class AppServerConfig(
    val relays: List<String>,
)
