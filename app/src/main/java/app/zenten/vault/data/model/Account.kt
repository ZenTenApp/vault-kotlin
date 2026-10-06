package app.zenten.vault.data.model

import kotlinx.serialization.Serializable

/**
 * Sensitive vault record. [createdAt] is the canonical account ID in microseconds.
 *
 * The SSH public key and Key fingerprint are deliberately not stored: both are derived on demand
 * from [zenTenKey]'s unencrypted OpenSSH header, so the passphrase is never needed to show them.
 */
@Serializable
data class Account(
    val createdAt: Long,
    val zenTenKey: String,
    val zenTenPass: String,
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
