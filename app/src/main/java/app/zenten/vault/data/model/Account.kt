package app.zenten.vault.data.model

import kotlinx.serialization.Serializable

/** Sensitive vault record. [createdAt] is the canonical account ID in microseconds. */
@Serializable
data class Account(
    val createdAt: Long,
    val zenTenKey: String,
    val zenTenPass: String? = null,
    val pubkey: String? = null,
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
