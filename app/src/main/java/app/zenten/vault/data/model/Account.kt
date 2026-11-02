package app.zenten.vault.data.model

import kotlinx.serialization.Serializable

/**
 * Sensitive vault record. [createdAt] is the canonical account ID in microseconds.
 *
 * Only the passphrase-encrypted OpenSSH key is stored; the passphrase itself is never persisted.
 * It is entered once per unlocked session and held in memory by
 * [app.zenten.vault.domain.VaultKeySession]. The SSH public key and Key fingerprint are likewise
 * derived on demand from [zenTenKey]'s unencrypted OpenSSH header.
 */
@Serializable
data class Account(
    val createdAt: Long,
    val zenTenKey: String,
    val nostr: NostrKeyPair,
    val accountName: String? = null,
)

@Serializable
data class NostrKeyPair(
    val pubkey: String,
    val privkey: String,
)

@Serializable
data class AppServerConfig(
    val relays: List<String>,
)
