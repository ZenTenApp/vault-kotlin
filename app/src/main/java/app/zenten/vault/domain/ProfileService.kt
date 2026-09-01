package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.data.model.Account
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import java.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Publishes the public Nostr identity for an associated account after its local backup completes. */
class ProfileService(
    private val relayGateway: NostrRelayGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetchAssociatedAccountProfile(account: Account): AccountProfile? = coroutineScope {
        val events = PROFILE_RELAYS.map { relayUrl ->
            async {
                relayUrl to relayGateway.fetchByRelay(relayUrl, account.nostr.pubkey, PROFILE_KIND)
            }
        }.awaitAll().flatMap { (_, result) ->
            result.getOrElse { emptyList() }
        }
        events
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id })
            .firstNotNullOfOrNull(::parseProfile)
    }

    suspend fun publishAssociatedAccountProfile(account: Account) {
        val passphrase = requireNotNull(account.zenTenPass) { "Associated-account passphrase is required to publish its profile" }
        val keys = NostrKeyDerivation.derive(Seedify.fromSshKey(account.zenTenKey, passphrase.toCharArray()))
        val event = NostrEventCodec.sign(
            privateKey = keys.privateKey,
            publicKey = keys.publicKey,
            kind = PROFILE_KIND,
            tags = profileTags(account.createdAt),
            content = profileContent(account.createdAt),
            createdAtSeconds = clock.instant().epochSecond,
        )
        PROFILE_RELAYS.forEach { relayGateway.publishAuthenticatedToRelay(it, event, keys.privateKey) }
    }

    internal fun profileContent(accountId: Long): String {
        val fields = profileFields(accountId)
        return buildJsonObject {
            fields.forEach { (name, value) -> put(name, value) }
        }.toString()
    }

    internal fun profileTags(accountId: Long): List<List<String>> =
        profileFields(accountId).map { (name, value) -> listOf(name, value) }

    private fun profileFields(accountId: Long): List<Pair<String, String>> {
        val name = accountId.toString()
        val suffix = name.takeLast(5)
        val banner = suffix.take(3).toInt() % 100
        val picture = suffix.takeLast(2).toInt() % 60
        return listOf(
            "name" to name,
            "display_name" to name,
            "banner" to "https://nostr.box/banner/$banner.svg",
            "picture" to "https://nostr.box/picture/$picture.svg",
            "about" to "",
        )
    }

    private fun parseProfile(event: NostrEvent): AccountProfile? = runCatching {
        val fields = Json.parseToJsonElement(event.content).jsonObject
        val displayName = (fields["display_name"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { null }
        val bannerUrl = (fields["banner"] as? JsonPrimitive)?.content?.trim()
            ?.takeIf { it.startsWith("https://") }
        AccountProfile(displayName = displayName, bannerUrl = bannerUrl, createdAtSeconds = event.createdAt)
    }.getOrNull()

    companion object {
        const val PROFILE_KIND = 0
        val PROFILE_RELAYS = listOf("wss://user.kindpag.es", "wss://purplepag.es")
    }
}

data class AccountProfile(
    val displayName: String?,
    val bannerUrl: String?,
    val createdAtSeconds: Long,
)
