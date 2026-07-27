package com.zenten.vault.network.nostr

/** App-owned Nostr event boundary. Timestamps are Nostr epoch seconds, not account IDs. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

data class PublishResult(
    val eventId: String,
    val successfulRelays: Set<String>,
    val failedRelays: Set<String>,
    val attempts: Int,
) {
    val acknowledged: Boolean get() = successfulRelays.isNotEmpty()
}
