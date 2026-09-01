package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.data.model.Account
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import java.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Publishes the public Nostr identity for an associated account after its local backup completes. */
class ProfileService(
    private val relayGateway: NostrRelayGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
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

    companion object {
        const val PROFILE_KIND = 0
        val PROFILE_RELAYS = listOf("wss://user.kindpag.es", "wss://purplepag.es")
    }
}
