package app.zenten.vault.network.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.AuthMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirmDetailed
import app.zenten.vault.network.nostr.NostrEventCodec.toAppEvent
import app.zenten.vault.network.nostr.NostrEventCodec.toQuartzEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            val result = withClient(authPrivateKey) { client, monitor ->
                val acknowledgements = withTimeoutOrNull(timeoutMillis) {
                    client.publishAndConfirmDetailed(event.toQuartzEvent(), relays, quartzTimeoutSeconds())
                }.orEmpty()
                val observed = monitor.acceptedRelays(event.id)
                acknowledgements.filterValues { it }.keys.map { it.url }.toSet() + observed
            }
            successful = result
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
        var attempts = 0
        withClient(authPrivateKey) { client, monitor ->
            var accepted = false
            // Keep one acknowledgement monitor and the same authenticated connection for every
            // retry. Quartz can resend the EVENT while handling AUTH; its OK may arrive between
            // publishAndConfirmDetailed calls and must not be lost.
            while (!accepted && attempts < maxAttempts) {
                attempts++
                accepted = withTimeoutOrNull(timeoutMillis) {
                    client.publishAndConfirmDetailed(event.toQuartzEvent(), setOf(relay), quartzTimeoutSeconds())[relay] == true
                } ?: false
                if (!accepted && monitor.authenticationWasRequested(relay)) {
                    monitor.awaitAuthentication(relay, timeoutMillis)
                    accepted = monitor.accepted(relay, event.id)
                }
            }
            if (!accepted) throw monitor.failureFor(relay, event.id)
        }
    }

    override suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int): Result<List<NostrEvent>> =
        fetchByRelay(relayUrl, authorPubkey, kind, null)

    override suspend fun fetchAuthenticatedByRelay(relayUrl: String, authorPubkey: String, kind: Int, privateKey: ByteArray): Result<List<NostrEvent>> =
        fetchByRelay(relayUrl, authorPubkey, kind, privateKey)

    private suspend fun fetchByRelay(relayUrl: String, authorPubkey: String, kind: Int, authPrivateKey: ByteArray?): Result<List<NostrEvent>> = runCatching {
        val relay = NormalizedRelayUrl(relayUrl)
        withClient(authPrivateKey) { client, monitor ->
            val filters = mapOf(relay to listOf(Filter(authors = listOf(authorPubkey), kinds = listOf(kind))))
            val keepAliveSubscriptionId = UUID.randomUUID().toString()
            if (authPrivateKey != null) {
                // fetchAll always unsubscribes when CLOSED arrives. Keep another request in
                // Quartz's pool so it cannot remove the relay and disconnect the challenged socket.
                client.subscribe(keepAliveSubscriptionId, filters)
            }
            try {
                suspend fun fetch() = withTimeoutOrNull(timeoutMillis) {
                    client.fetchAll(
                        subscriptionId = UUID.randomUUID().toString(),
                        filters = filters,
                        timeoutMs = timeoutMillis,
                    )
                } ?: throw SocketTimeoutException("Timed out checking $relayUrl")

                var result = fetch()
                // A protected relay can answer the first REQ with CLOSED auth-required while also
                // issuing AUTH. Wait for its OK and issue a fresh REQ on the pinned connection.
                if (result.isEmpty() && monitor.authenticationWasRequested(relay)) {
                    monitor.awaitAuthentication(relay, timeoutMillis)
                    result = fetch()
                }
                if (result.isEmpty()) {
                    monitor.authenticationFailureOrNull(relay)?.let { throw it }
                    monitor.currentConnectionFailure(relay)?.let { throw it }
                }
                result.map { it.toAppEvent() }.filter {
                    it.kind == kind && it.pubkey == authorPubkey && NostrEventCodec.isValid(it)
                }.distinctBy { it.id }
            } finally {
                if (authPrivateKey != null) client.unsubscribe(keepAliveSubscriptionId)
            }
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
        return withClient(authPrivateKey) { client, _ ->
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

    private fun quartzTimeoutSeconds(): Long = maxOf(1L, (timeoutMillis + 999L) / 1_000L)

    private suspend fun <T> withClient(authPrivateKey: ByteArray? = null, block: suspend (NostrClient, RelayOperationMonitor) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = clientFactory(scope)
        val monitor = RelayOperationMonitor()
        client.addConnectionListener(monitor)
        val authenticator = authPrivateKey?.let { privateKey ->
            val signer = NostrSignerInternal(KeyPair(privKey = privateKey))
            RelayAuthenticator(client, scope) { template ->
                // The callback is suspend and RelayAuthenticator already invokes it in its auth
                // scope. Do not nest runBlocking: signing must not block relay message dispatch.
                val signed = signer.sign<RelayAuthEvent>(template.createdAt, template.kind, template.tags, template.content)
                monitor.recordAuthEvent(signed.id)
                listOf(signed)
            }
        }
        return try {
            block(client, monitor)
        } finally {
            authenticator?.destroy()
            client.removeConnectionListener(monitor)
            client.close()
            scope.cancel()
        }
    }

    private class RelayOperationMonitor : RelayConnectionListener {
        private data class EventResponse(val relay: NormalizedRelayUrl, val success: Boolean, val message: String)

        private val authEventIds = ConcurrentHashMap.newKeySet<String>()
        private val authChallengedRelays = ConcurrentHashMap.newKeySet<NormalizedRelayUrl>()
        private val authRequiredRelays = ConcurrentHashMap.newKeySet<NormalizedRelayUrl>()
        private val authRejectedRelays = ConcurrentHashMap.newKeySet<NormalizedRelayUrl>()
        private val authResults = ConcurrentHashMap<NormalizedRelayUrl, CompletableDeferred<Boolean>>()
        private val responses = ConcurrentHashMap<String, ConcurrentLinkedQueue<EventResponse>>()
        private val connectionFailures = ConcurrentHashMap<NormalizedRelayUrl, AtomicReference<String?>>()

        fun recordAuthEvent(eventId: String) {
            authEventIds += eventId
        }

        override fun onConnected(relay: IRelayClient, pingMillis: Int, compressed: Boolean) {
            connectionFailures.getOrPut(relay.url) { AtomicReference() }.set(null)
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            connectionFailures.getOrPut(relay.url) { AtomicReference() }.set(errorMessage)
        }

        override fun onDisconnected(relay: IRelayClient) {
            connectionFailures.getOrPut(relay.url) { AtomicReference() }.compareAndSet(null, "Relay disconnected")
        }

        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            when (msg) {
                is AuthMessage -> authChallengedRelays += relay.url
                is ClosedMessage -> if (isAuthRequired(msg.message)) {
                    authRequiredRelays += relay.url
                }
                is OkMessage -> {
                    if (msg.eventId in authEventIds) {
                        if (!msg.success) authRejectedRelays += relay.url
                        authResults.getOrPut(relay.url) { CompletableDeferred() }.complete(msg.success)
                    } else {
                        if (isAuthRequired(msg.message)) authRequiredRelays += relay.url
                        responses.getOrPut(msg.eventId) { ConcurrentLinkedQueue() }
                            .add(EventResponse(relay.url, msg.success, msg.message))
                    }
                }
            }
        }

        fun authenticationWasRequested(relay: NormalizedRelayUrl): Boolean =
            relay in authChallengedRelays || relay in authRequiredRelays

        suspend fun awaitAuthentication(relay: NormalizedRelayUrl, timeoutMillis: Long) {
            val accepted = withTimeoutOrNull(timeoutMillis) {
                authResults.getOrPut(relay) { CompletableDeferred() }.await()
            } ?: throw SocketTimeoutException("Timed out waiting for relay authentication: ${relay.url}")
            if (!accepted) throw authenticationFailure(relay)
        }

        fun authenticationFailureOrNull(relay: NormalizedRelayUrl): SecurityException? = when {
            relay in authRejectedRelays -> authenticationFailure(relay)
            else -> null
        }

        fun authenticationFailure(relay: NormalizedRelayUrl) =
            SecurityException("Relay authentication failed: ${relay.url}")

        fun acceptedRelays(eventId: String): Set<String> = responses[eventId].orEmpty()
            .filter { it.success || isIdempotentDuplicate(eventId, it.message) }
            .mapTo(mutableSetOf()) { it.relay.url }

        fun accepted(relay: NormalizedRelayUrl, eventId: String): Boolean = responses[eventId].orEmpty()
            .any { it.relay == relay && (it.success || isIdempotentDuplicate(eventId, it.message)) }

        fun currentConnectionFailure(relay: NormalizedRelayUrl): IOException? =
            connectionFailures[relay]?.get()?.let { IOException("Relay connection failed: ${relay.url}") }

        fun failureFor(relay: NormalizedRelayUrl, eventId: String): Throwable {
            val relayResponses = responses[eventId].orEmpty().filter { it.relay == relay }
            if (relay in authRejectedRelays) {
                return authenticationFailure(relay)
            }
            if (relayResponses.isNotEmpty()) return IllegalStateException("Relay rejected event: ${relay.url}")
            currentConnectionFailure(relay)?.let { return it }
            return SocketTimeoutException("Timed out waiting for relay acknowledgement: ${relay.url}")
        }
    }

    companion object {
        internal fun isAuthRequired(message: String): Boolean =
            message.trim().startsWith("auth-required:", ignoreCase = true)

        internal fun isIdempotentDuplicate(eventId: String, message: String): Boolean {
            val normalized = message.trim().lowercase()
            return normalized.contains("already exists") &&
                Regex("(?<![0-9a-f])${Regex.escape(eventId.lowercase())}(?![0-9a-f])").containsMatchIn(normalized)
        }

        val DEFAULT_RELAYS = listOf(
            "wss://appdata1.zentext.me",
            "wss://appdata2.nostr.box",
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
