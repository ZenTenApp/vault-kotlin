package app.zenten.vault.network.nostr

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirmDetailed
import app.zenten.vault.network.nostr.NostrEventCodec.toAppEvent
import app.zenten.vault.network.nostr.NostrEventCodec.toQuartzEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One-shot relay transport backed by Quartz's Nostr client and OkHttp websocket implementation.
 * This owns our quorum/retry policy; Quartz owns wire protocol, OK handling, REQ/EVENT/EOSE.
 */
interface NostrRelayGateway {
    /** Individually-addressable relays used by backup reconciliation. */
    val relayUrls: List<String> get() = emptyList()
    suspend fun publish(event: NostrEvent): PublishResult
    suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent>

    suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Result<Unit> = runCatching {
        check(publish(event).acknowledged)
    }

    /** Failure is distinct from a successfully checked relay with no matching events. */
    suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int): Result<List<NostrEvent>> =
        runCatching { fetchByAuthorAndKind(authorPubkey, kind) }
}

class NostrRelayService(
    override val relayUrls: List<String> = DEFAULT_RELAYS,
    private val clientFactory: (CoroutineScope) -> NostrClient = { scope -> newQuartzClient(scope) },
    private val timeoutMillis: Long = ATTEMPT_TIMEOUT_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : NostrRelayGateway {
    override suspend fun publish(event: NostrEvent): PublishResult {
        require(NostrEventCodec.isValid(event)) { "Refusing to publish an invalid Nostr event" }
        val relays = normalizedRelays()
        require(relays.isNotEmpty()) { "At least one relay is required" }
        var successful = emptySet<String>()
        var attempts = 0

        while (attempts < maxAttempts && successful.isEmpty()) {
            attempts++
            val result = withClient { client ->
                withTimeoutOrNull(timeoutMillis) {
                    client.publishAndConfirmDetailed(event.toQuartzEvent(), relays, timeoutMillis)
                }.orEmpty()
            }
            successful = result.filterValues { it }.keys.map { it.url }.toSet()
            if (successful.isEmpty() && attempts < maxAttempts) delay(RETRY_DELAY_MILLIS)
        }
        return PublishResult(event.id, successful, relayUrls.toSet() - successful, attempts)
    }

    override suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Result<Unit> = runCatching {
        require(NostrEventCodec.isValid(event)) { "Refusing to publish an invalid Nostr event" }
        val relay = NormalizedRelayUrl(relayUrl)
        val accepted = withClient { client ->
            withTimeoutOrNull(timeoutMillis) {
                client.publishAndConfirmDetailed(event.toQuartzEvent(), setOf(relay), timeoutMillis)[relay] == true
            } ?: false
        }
        check(accepted) { "Relay did not acknowledge backup: $relayUrl" }
    }

    override suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int): Result<List<NostrEvent>> = runCatching {
        val relay = NormalizedRelayUrl(relayUrl)
        withClient { client ->
            val result = withTimeoutOrNull(timeoutMillis) {
                client.fetchAll(
                    subscriptionId = UUID.randomUUID().toString(),
                    filters = mapOf(relay to listOf(Filter(authors = listOf(authorPubkey), kinds = listOf(kind)))),
                    timeoutMs = timeoutMillis,
                )
            } ?: throw java.io.IOException("Timed out checking $relayUrl")
            result.map { it.toAppEvent() }.filter {
                it.kind == kind && it.pubkey == authorPubkey && NostrEventCodec.isValid(it)
            }.distinctBy { it.id }
        }
    }

    /** Fetches only locally-valid events with the requested author and kind. */
    override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> {
        val relays = normalizedRelays()
        if (relays.isEmpty()) return emptyList()
        val filter = Filter(authors = listOf(authorPubkey), kinds = listOf(kind))
        return withClient { client ->
            withTimeoutOrNull(timeoutMillis) {
                // The String overload treats its first argument as a *relay URL*, not a
                // subscription ID. Pass the complete relay-to-filter map so Quartz sends
                // the REQ to every configured relay under one generated subscription.
                client.fetchAll(
                    subscriptionId = UUID.randomUUID().toString(),
                    filters = relays.associateWith { listOf(filter) },
                    timeoutMs = timeoutMillis,
                )
                    .map { it.toAppEvent() }
                    .filter { it.kind == kind && it.pubkey == authorPubkey && NostrEventCodec.isValid(it) }
                    .distinctBy { it.id }
            }.orEmpty()
        }
    }

    private fun normalizedRelays(): Set<NormalizedRelayUrl> = relayUrls.map(::NormalizedRelayUrl).toSet()

    private suspend fun <T> withClient(block: suspend (NostrClient) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = clientFactory(scope)
        return try {
            client.connect()
            block(client)
        } finally {
            client.close()
        }
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
        )
        const val ATTEMPT_TIMEOUT_MILLIS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L

        private fun newQuartzClient(scope: CoroutineScope): NostrClient {
            val okHttp = OkHttpClient.Builder()
                .connectTimeout(ATTEMPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val sockets = BasicOkHttpWebSocket.Builder { okHttp }
            return NostrClient(sockets, scope)
        }
    }
}
