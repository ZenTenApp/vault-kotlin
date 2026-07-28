package com.zenten.vault.domain

import com.zenten.vault.crypto.NostrKeyDerivation
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.model.NostrKeyPair
import com.zenten.vault.network.nostr.NostrEvent
import com.zenten.vault.network.nostr.NostrRelayGateway
import com.zenten.vault.network.nostr.PublishResult
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
        assertEquals(Account(20, "key"), recovery.decodeSubAccount(valid, master))
        assertNull(recovery.decodeSubAccount(invalid, master))
    }

    private fun storage(): com.zenten.vault.data.secure.SecureStorageService = mockk(relaxed = true)

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
