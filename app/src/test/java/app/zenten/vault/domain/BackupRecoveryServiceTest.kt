package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupRecoveryServiceTest {
    private val keys = NostrKeyDerivation.derive(
        "alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady",
    )
    private val master = Account(
        createdAt = 1,
        zenTenKey = "master-key",
        nostr = NostrKeyPair(keys.publicKey.hex(), keys.privateKey.hex()),
    )
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)
    private val gateway = FakeRelayGateway()
    private val service = BackupService(gateway, clock)

    @Test
    fun master_backup_has_37801_lowercase_masterkey_and_only_d_tag() = runTest {
        service.backupMaster(master)
        val event = gateway.published.single()

        assertEquals(37_801, event.kind)
        assertEquals(listOf(listOf("d", "masterkey")), event.tags)
        assertEquals(1_700_000_000, event.createdAt)
        assertTrue(event.content.startsWith("A")) // NIP-44 v2 payload version
    }

    @Test
    fun sub_backup_has_37802_birthdate_d_tag_and_can_be_decoded() = runTest {
        val sub = Account(1_700_000_000_123_456L, "sub-key")
        service.backupSubAccount(master, sub)
        val event = gateway.published.single()

        assertEquals(37_802, event.kind)
        assertEquals(listOf(listOf("d", "1700000000123456")), event.tags)
        assertEquals(sub, RecoveryService(gateway, storage()).decodeSubAccount(event, master))
    }

    @Test
    fun recovery_only_queries_37802_and_rejects_invalid_d_tag() = runTest {
        val valid = service.buildBackupEvent(master, Account(20, "key"), "20", 37_802)
        val invalid = valid.copy(tags = listOf(listOf("d", "not-a-timestamp")))
        gateway.fetched = listOf(valid, invalid)
        val recovery = RecoveryService(gateway, storage())

        val result = recovery.discoverSubAccounts(master)
        assertEquals(37_802, gateway.requestedKind)
        assertEquals(listOf(Account(20, "key")), result.recovered)
        assertEquals(listOf(invalid.id), result.skippedEventIds)
        assertEquals(2, result.discoveredEventCount)
        assertEquals(Account(20, "key"), recovery.decodeSubAccount(valid, master))
        assertNull(recovery.decodeSubAccount(invalid, master))
    }

    @Test
    fun recovery_discovers_and_persists_existing_sub_accounts_after_reinstall() = runTest {
        val sub = Account(20, "key")
        gateway.fetched = listOf(service.buildBackupEvent(master, sub, "20", 37_802))
        val storage = storage()
        io.mockk.every { storage.getMasterAccount() } returns master
        val appStorage = mockk<AppStorageService>(relaxed = true)
        val recovery = RecoveryService(gateway, storage)
        val setup = SetupService(storage, appStorage, service, recovery)

        val result = setup.recoverExistingAccounts().getOrThrow()

        assertEquals(listOf(sub), result.recovered)
        assertEquals(1, result.discoveredEventCount)
        coVerify { storage.addAccount(sub) }
        assertEquals(37_802, gateway.requestedKind)
    }

    private fun storage(): SecureStorageService = mockk(relaxed = true)

    private class FakeRelayGateway : NostrRelayGateway {
        val published = mutableListOf<NostrEvent>()
        var fetched = emptyList<NostrEvent>()
        var requestedKind: Int? = null
        override suspend fun publish(event: NostrEvent): PublishResult {
            published += event
            return PublishResult(event.id, setOf("test"), emptySet(), 1)
        }
        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> {
            requestedKind = kind
            return fetched
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
