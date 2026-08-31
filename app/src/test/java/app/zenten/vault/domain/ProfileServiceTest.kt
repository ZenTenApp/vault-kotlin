package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileServiceTest {
    @Test
    fun publishes_a_deterministic_kind_zero_profile_for_the_account() = runTest {
        val gateway = RecordingGateway()
        val accountId = 1_700_000_012_345_678L
        val passphrase = "associated-passphrase"
        val generated = SshKeyCodec.generateEncrypted(accountId, passphrase.toCharArray())
        val account = Account(createdAt = accountId, zenTenKey = generated.privateKeyPem, zenTenPass = passphrase, nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

        ProfileService(gateway, Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC))
            .publishAssociatedAccountProfile(account)

        val event = gateway.event!!
        assertEquals(0, event.kind)
        assertEquals(1_700_000_000L, event.createdAt)
        assertEquals(NostrKeyDerivation.derive(Seedify.fromSshKey(account.zenTenKey, passphrase.toCharArray())).publicKey.hex(), event.pubkey)
        assertEquals(
            listOf(
                listOf("name", "1700000012345678"),
                listOf("display_name", "1700000012345678"),
                listOf("banner", "https://nostr.box/banner/56.svg"),
                listOf("picture", "https://nostr.box/picture/18.svg"),
                listOf("about", ""),
            ),
            event.tags,
        )
        assertEquals(
            "{\"name\":\"1700000012345678\",\"display_name\":\"1700000012345678\",\"banner\":\"https://nostr.box/banner/56.svg\",\"picture\":\"https://nostr.box/picture/18.svg\",\"about\":\"\"}",
            event.content,
        )
        assertTrue(event.sig.isNotBlank())
        assertEquals(ProfileService.PROFILE_RELAYS, gateway.relays)
    }

    private class RecordingGateway : NostrRelayGateway {
        var event: NostrEvent? = null
        val relays = mutableListOf<String>()

        override suspend fun publish(event: NostrEvent) = PublishResult(event.id, emptySet(), emptySet(), 1)
        override suspend fun publishAuthenticatedToRelay(relayUrl: String, event: NostrEvent, privateKey: ByteArray): Result<Unit> {
            relays += relayUrl
            this.event = event
            return Result.success(Unit)
        }
        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> = emptyList()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
