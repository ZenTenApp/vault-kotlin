package app.zenten.vault.domain

import app.zenten.vault.data.model.Account
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Publishes and retrieves the public Nostr identity of an associated account. */
class ProfileService(
    private val relayGateway: NostrRelayGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetchAssociatedAccountProfile(account: Account): AccountProfile? =
        fetchAssociatedAccountProfiles(listOf(account))[account.createdAt]

    suspend fun fetchAssociatedAccountProfiles(accounts: List<Account>): Map<Long, AccountProfile> {
        var profiles = emptyMap<Long, AccountProfile>()
        streamAssociatedAccountProfiles(accounts).collect { relayProfiles ->
            profiles = profiles + relayProfiles.filter { (accountId, profile) ->
                profile.createdAtSeconds > (profiles[accountId]?.createdAtSeconds ?: Long.MIN_VALUE)
            }
        }
        return profiles
    }

    /** Emits each successful relay response independently so slow relays do not delay UI updates. */
    fun streamAssociatedAccountProfiles(accounts: List<Account>): Flow<Map<Long, AccountProfile>> = channelFlow {
        if (accounts.isEmpty()) return@channelFlow
        val authorPubkeys = accounts.mapTo(mutableSetOf()) { it.nostr.pubkey }
        PROFILE_RELAYS.forEach { relayUrl ->
            launch {
                relayGateway.fetchByRelay(relayUrl, authorPubkeys, PROFILE_KIND).getOrNull()?.let { events ->
                    send(parseProfiles(accounts, events))
                }
            }
        }
    }

    suspend fun publishAssociatedAccountProfile(account: Account) {
        val privateKey = account.nostr.privateKey()
        val publicKey = account.nostr.publicKey()
        val event = NostrEventCodec.sign(
            privateKey = privateKey,
            publicKey = publicKey,
            kind = PROFILE_KIND,
            tags = profileTags(account),
            content = profileContent(account),
            createdAtSeconds = clock.instant().epochSecond,
        )
        val failures = PROFILE_RELAYS.map { relayUrl ->
            relayUrl to relayGateway.publishAuthenticatedToRelay(relayUrl, event, privateKey)
        }.filter { (_, result) -> result.isFailure }
        check(failures.isEmpty()) { "Profile publication failed for ${failures.joinToString { it.first }}" }
    }

    internal fun profileContent(account: Account): String = buildJsonObject {
        profileFields(account).forEach { (name, value) -> put(name, value) }
    }.toString()

    internal fun profileTags(account: Account): List<List<String>> =
        profileFields(account).map { (name, value) -> listOf(name, value) }

    private fun profileFields(account: Account): List<Pair<String, String>> {
        val npub = account.nostr.publicKey().toNpub()
        return listOf(
            "name" to account.createdAt.toString(),
            "display_name" to "${npub.take(4)}...${npub.takeLast(4)}",
            "banner" to "",
            "picture" to "",
            "about" to "",
        )
    }

    private fun parseProfile(event: NostrEvent): AccountProfile? = runCatching {
        val fields = Json.parseToJsonElement(event.content).jsonObject
        val displayName = (fields["display_name"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { null }
        AccountProfile(displayName = displayName, createdAtSeconds = event.createdAt)
    }.getOrNull()

    private fun parseProfiles(accounts: List<Account>, events: List<NostrEvent>): Map<Long, AccountProfile> {
        val eventsByAuthor = events.groupBy { it.pubkey }
        return accounts.mapNotNull { account ->
            eventsByAuthor[account.nostr.pubkey].orEmpty()
                .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id })
                .firstNotNullOfOrNull(::parseProfile)
                ?.let { account.createdAt to it }
        }.toMap()
    }

    companion object {
        const val PROFILE_KIND = 0
        val PROFILE_RELAYS = listOf("wss://user.kindpag.es", "wss://purplepag.es")
    }
}

data class AccountProfile(
    val displayName: String?,
    val createdAtSeconds: Long,
)

interface ProfilePublicationScheduler {
    fun scheduleProfilePublication(accountId: Long)
}
