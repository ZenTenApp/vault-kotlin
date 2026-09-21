package app.zenten.vault.domain

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileServiceTest {
    @Test
    fun publishes_name_and_display_name_with_blank_optional_profile_fields() = runTest {
        val gateway = RecordingGateway()
        val keys = KeyPair()
        val account = account(keys).copy(accountName = "Personal laptop")

        ProfileService(gateway, Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC))
            .publishAssociatedAccountProfile(account)

        val displayName = "Personal laptop"
        val profileEvent = gateway.events.single { it.kind == ProfileService.PROFILE_KIND }
        assertEquals(
            listOf(
                listOf("name", account.createdAt.toString()),
                listOf("display_name", displayName),
                listOf("banner", ""),
                listOf("picture", ""),
                listOf("about", ""),
            ),
            profileEvent.tags,
        )
        assertEquals(
            "{\"name\":\"${account.createdAt}\",\"display_name\":\"$displayName\",\"banner\":\"\",\"picture\":\"\",\"about\":\"\"}",
            profileEvent.content,
        )
        assertEquals(ProfileService.PROFILE_RELAYS + ProfileService.PROFILE_RELAYS, gateway.publishedRelays)
    }

    @Test
    fun publishes_dm_relay_list_to_every_profile_relay() = runTest {
        val gateway = RecordingGateway()

        ProfileService(gateway, Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC))
            .publishAssociatedAccountProfile(account(KeyPair()))

        val dmRelayList = gateway.events.single { it.kind == ProfileService.DM_RELAY_LIST_KIND }
        assertEquals("", dmRelayList.content)
        assertEquals(ProfileService.DM_RELAYS.map { listOf("relay", it) }, dmRelayList.tags)
        assertEquals(ProfileService.PROFILE_RELAYS, gateway.relaysByEventId[dmRelayList.id])
    }

    @Test
    fun publication_fails_when_any_profile_relay_does_not_acknowledge() = runTest {
        val gateway = RecordingGateway().apply { failingRelay = ProfileService.PROFILE_RELAYS.last() }

        val result = runCatching { ProfileService(gateway).publishAssociatedAccountProfile(account(KeyPair())) }

        assertTrue(result.isFailure)
    }

    @Test
    fun fetches_the_newest_valid_nonblank_display_name() = runTest {
        val account = account(KeyPair())
        val gateway = RecordingGateway().apply {
            events += listOf(
                profileEvent(account.nostr.pubkey, 100L, "{\"display_name\":\"Old\"}"),
                profileEvent(account.nostr.pubkey, 300L, "not JSON"),
                profileEvent(account.nostr.pubkey, 200L, "{\"display_name\":\"  New profile  \"}"),
            )
        }

        val profile = ProfileService(gateway).fetchAssociatedAccountProfile(account)

        assertEquals("New profile", profile?.displayName)
        assertEquals(200L, profile?.createdAtSeconds)
    }

    @Test
    fun blank_display_name_is_returned_as_missing_for_card_fallback() = runTest {
        val account = account(KeyPair())
        val gateway = RecordingGateway().apply {
            events += profileEvent(account.nostr.pubkey, 100L, "{\"display_name\":\"  \"}")
        }

        assertNull(ProfileService(gateway).fetchAssociatedAccountProfile(account)?.displayName)
    }

    @Test
    fun fetches_all_account_profiles_with_one_query_per_relay() = runTest {
        val first = account(KeyPair(), 1L)
        val second = account(KeyPair(), 2L)
        val gateway = RecordingGateway().apply {
            events += listOf(
                profileEvent(first.nostr.pubkey, 100L, "{\"display_name\":\"First\"}"),
                profileEvent(second.nostr.pubkey, 200L, "{\"display_name\":\"Second\"}"),
            )
        }

        val profiles = ProfileService(gateway).fetchAssociatedAccountProfiles(listOf(first, second))

        assertEquals("First", profiles[first.createdAt]?.displayName)
        assertEquals("Second", profiles[second.createdAt]?.displayName)
        assertEquals(ProfileService.PROFILE_RELAYS.toSet(), gateway.fetchedRelays.toSet())
        assertEquals(ProfileService.PROFILE_RELAYS.size, gateway.fetchedRelays.size)
        assertEquals(setOf(first.nostr.pubkey, second.nostr.pubkey), gateway.requestedAuthors)
    }

    private class RecordingGateway : NostrRelayGateway {
        val events = mutableListOf<NostrEvent>()
        val publishedRelays = mutableListOf<String>()
        val relaysByEventId = mutableMapOf<String, MutableList<String>>()
        val fetchedRelays = mutableListOf<String>()
        var requestedAuthors = emptySet<String>()
        var failingRelay: String? = null

        override suspend fun publish(event: NostrEvent) = PublishResult(event.id, emptySet(), emptySet(), 1)

        override suspend fun publishAuthenticatedToRelay(
            relayUrl: String,
            event: NostrEvent,
            privateKey: ByteArray,
        ): Result<Unit> {
            publishedRelays += relayUrl
            if (event !in events) events += event
            relaysByEventId.getOrPut(event.id) { mutableListOf() } += relayUrl
            return if (relayUrl == failingRelay) Result.failure(IllegalStateException("rejected")) else Result.success(Unit)
        }

        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> = events

        override suspend fun fetchByRelay(
            relayUrl: String,
            authorPubkey: String,
            kind: Int,
        ): Result<List<NostrEvent>> = Result.success(events)

        override suspend fun fetchByRelay(
            relayUrl: String,
            authorPubkeys: Set<String>,
            kind: Int,
        ): Result<List<NostrEvent>> {
            fetchedRelays += relayUrl
            requestedAuthors = authorPubkeys
            return Result.success(events.filter { it.pubkey in authorPubkeys })
        }
    }

    private fun account(keys: KeyPair, accountId: Long = 1_700_000_012_345_678L) = Account(
        createdAt = accountId,
        zenTenKey = "key",
        nostr = NostrKeyPair(keys.pubKey.hex(), keys.privKey!!.hex()),
    )

    private fun profileEvent(pubkey: String, createdAt: Long, content: String) = NostrEvent(
        id = "id-$createdAt-$content",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = ProfileService.PROFILE_KIND,
        tags = emptyList(),
        content = content,
        sig = "signature",
    )

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
