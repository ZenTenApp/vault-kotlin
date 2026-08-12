package app.zenten.vault.network.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirmDetailed
import app.zenten.vault.network.nostr.NostrEventCodec.toAppEvent
import app.zenten.vault.network.nostr.NostrEventCodec.toQuartzEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /**
     * Uses this account's private key only to answer a relay's NIP-42 challenge. It is never
     * sent to the relay, and is deliberately optional for relays that do not require auth.
     */
    suspend fun publishAuthenticated(event: NostrEvent, privateKey: ByteArray): PublishResult = publish(event)

    suspend fun fetchAuthenticatedByAuthorAndKind(authorPubkey: String, kind: Int, privateKey: ByteArray): List<NostrEvent> =
        fetchByAuthorAndKind(authorPubkey, kind)

    suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Result<Unit> = runCatching {
        check(publish(event).acknowledged)
    }

    suspend fun publishAuthenticatedToRelay(relayUrl: String, event: NostrEvent, privateKey: ByteArray): Result<Unit> =
        publishToRelay(relayUrl, event)

    /** Failure is distinct from a successfully checked relay with no matching events. */
    suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int): Result<List<NostrEvent>> =
        runCatching { fetchByAuthorAndKind(authorPubkey, kind) }

    suspend fun fetchAuthenticatedByRelay(relayUrl: String, authorPubkey: String, kind: Int, privateKey: ByteArray): Result<List<NostrEvent>> =
        fetchByRelay(relayUrl, authorPubkey, kind)
}

class NostrRelayService(
    override val relayUrls: List<String> = DEFAULT_RELAYS,
    private val clientFactory: (CoroutineScope) -> NostrClient = { scope -> newQuartzClient(scope) },
    private val timeoutMillis: Long = ATTEMPT_TIMEOUT_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : NostrRelayGateway {
    override suspend fun publish(event: NostrEvent): PublishResult = publish(event, null)

    override suspend fun publishAuthenticated(event: NostrEvent, privateKey: ByteArray): PublishResult = publish(event, privateKey)

    private suspend fun publish(event: NostrEvent, authPrivateKey: ByteArray?): PublishResult {
        require(NostrEventCodec.isValid(event)) { "Refusing to publish an invalid Nostr event" }
        val relays = normalizedRelays()
        require(relays.isNotEmpty()) { "At least one relay is required" }
        var successful = emptySet<String>()
        var attempts = 0

        while (attempts < maxAttempts && successful.isEmpty()) {
            attempts++
            val result = withClient(authPrivateKey) { client ->
                withTimeoutOrNull(timeoutMillis) {
                    client.publishAndConfirmDetailed(event.toQuartzEvent(), relays, timeoutMillis)
                }.orEmpty()
            }
            successful = result.filterValues { it }.keys.map { it.url }.toSet()
            if (successful.isEmpty() && attempts < maxAttempts) delay(RETRY_DELAY_MILLIS)
        }
        return PublishResult(event.id, successful, relayUrls.toSet() - successful, attempts)
    }

    override suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Result<Unit> = publishToRelay(relayUrl, event, null)

    override suspend fun publishAuthenticatedToRelay(relayUrl: String, event: NostrEvent, privateKey: ByteArray): Result<Unit> =
        publishToRelay(relayUrl, event, privateKey)

    private suspend fun publishToRelay(relayUrl: String, event: NostrEvent, authPrivateKey: ByteArray?): Result<Unit> = runCatching {
        require(NostrEventCodec.isValid(event)) { "Refusing to publish an invalid Nostr event" }
        val relay = NormalizedRelayUrl(relayUrl)
        val accepted = withClient(authPrivateKey) { client ->
            var accepted = false
            var attempt = 0
            // Keep the same authenticated connection for retries. A protected relay may reject
            // the first EVENT while its NIP-42 challenge is being signed; RelayAuthenticator
            // records the AUTH OK and Quartz resynchronizes filters on this same connection.
            while (!accepted && attempt < maxAttempts) {
                attempt++
                accepted = withTimeoutOrNull(timeoutMillis) {
                    client.publishAndConfirmDetailed(event.toQuartzEvent(), setOf(relay), timeoutMillis)[relay] == true
                } ?: false
                if (!accepted && attempt < maxAttempts) delay(AUTH_RETRY_DELAY_MILLIS)
            }
            accepted
        }
        if (!accepted) {
            throw SecurityException("Relay authentication or acknowledgement failed: $relayUrl")
        }
    }

    override suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int): Result<List<NostrEvent>> =
        fetchByRelay(relayUrl, authorPubkey, kind, null)

    override suspend fun fetchAuthenticatedByRelay(relayUrl: String, authorPubkey: String, kind: Int, privateKey: ByteArray): Result<List<NostrEvent>> =
        fetchByRelay(relayUrl, authorPubkey, kind, privateKey)

    private suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int, authPrivateKey: ByteArray?): Result<List<NostrEvent>> = runCatching {
        val relay = NormalizedRelayUrl(relayUrl)
        withClient(authPrivateKey) { client ->
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
    override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> =
        fetchByAuthorAndKind(authorPubkey, kind, null)

    override suspend fun fetchAuthenticatedByAuthorAndKind(authorPubkey: String, kind: Int, privateKey: ByteArray): List<NostrEvent> =
        fetchByAuthorAndKind(authorPubkey, kind, privateKey)

    private suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int, authPrivateKey: ByteArray?): List<NostrEvent> {
        val relays = normalizedRelays()
        if (relays.isEmpty()) return emptyList()
        val filter = Filter(authors = listOf(authorPubkey), kinds = listOf(kind))
        return withClient(authPrivateKey) { client ->
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

    private suspend fun <T> withClient(authPrivateKey: ByteArray? = null, block: suspend (NostrClient) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = clientFactory(scope)
        // Quartz queues the initial EVENT/REQ from onConnected. Relay AUTH commonly arrives
        // immediately afterwards; dispatch its signer undispatched so the AUTH is sent before
        // the queued unauthenticated operation can be rejected and removed from the outbox.
        val authScope = authPrivateKey?.let { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
        val authenticator = authPrivateKey?.let { privateKey ->
            val signer = NostrSignerInternal(KeyPair(privKey = privateKey))
            RelayAuthenticator(client, requireNotNull(authScope)) { template ->
                // The callback is suspend and RelayAuthenticator already invokes it in its auth
                // scope. Do not nest runBlocking: signing must not block relay message dispatch.
                listOf(signer.sign(template.createdAt, template.kind, template.tags, template.content))
            }
        }
        return try {
            client.connect()
            // Quartz synchronizes the outbox as soon as the socket opens, whereas an auth relay
            // sends AUTH at the same instant. Give that challenge priority: an EVENT sent first
            // can be rejected and discarded before Quartz receives the successful AUTH OK.
            if (authPrivateKey != null) delay(AUTH_CHALLENGE_GRACE_MILLIS)
            block(client)
        } finally {
            authenticator?.destroy()
            authScope?.cancel()
            client.close()
        }
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://appdata1.zentext.me",
            "wss://appdata2.nostr.box",
        )
        const val ATTEMPT_TIMEOUT_MILLIS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L
        private const val AUTH_CHALLENGE_GRACE_MILLIS = 250L
        private const val AUTH_RETRY_DELAY_MILLIS = 500L

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
