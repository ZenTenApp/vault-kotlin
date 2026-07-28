package com.zenten.vault.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zenten.vault.crypto.NostrKeyDerivation
import com.zenten.vault.data.kv.AppStorageService
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.model.NostrKeyPair
import com.zenten.vault.data.secure.SecureStorageService
import com.zenten.vault.network.nostr.NostrEvent
import com.zenten.vault.network.nostr.NostrRelayGateway
import com.zenten.vault.network.nostr.PublishResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RelayBackupRetryServiceTest {
    private val keyPair = NostrKeyDerivation.derive(
        "alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady",
    ).let { NostrKeyPair(it.publicKey.hex(), it.privateKey.hex()) }
    private val master = Account(1, "master", nostr = keyPair)

    @Test
    fun successful_retry_removes_only_acknowledged_ids() = runTest {
        fixture { storage, app, gateway, retry ->
            every { storage.getMasterAccount() } returns master
            every { storage.getAccountById(10) } returns Account(10, "one")
            every { storage.getAccountById(20) } returns Account(20, "two")
            app.setFailedBackupAccountIds(listOf(10, 20))
            gateway.results += true
            gateway.results += false

            val summary = retry.retryFailedBackups()

            assertEquals(RelayBackupRetryService.RetrySummary(2, 1, 0), summary)
            assertEquals(listOf(20L), app.failedBackupAccountIds.first())
            assertEquals(2, gateway.published.size)
            assertTrue(gateway.published.all { it.kind == BackupService.SUB_ACCOUNT_BACKUP_KIND })
        }
    }

    @Test
    fun missing_local_account_is_pruned_without_publish() = runTest {
        fixture { storage, app, gateway, retry ->
            every { storage.getMasterAccount() } returns master
            every { storage.getAccountById(10) } returns null
            app.setFailedBackupAccountIds(listOf(10))

            val summary = retry.retryFailedBackups()

            assertEquals(RelayBackupRetryService.RetrySummary(0, 0, 1), summary)
            assertEquals(emptyList<Long>(), app.failedBackupAccountIds.first())
            assertTrue(gateway.published.isEmpty())
        }
    }

    @Test
    fun unavailable_master_leaves_retry_ids_untouched() = runTest {
        fixture { storage, app, gateway, retry ->
            every { storage.getMasterAccount() } returns null
            app.setFailedBackupAccountIds(listOf(10))

            assertEquals(RelayBackupRetryService.RetrySummary(0, 0, 0), retry.retryFailedBackups())
            assertEquals(listOf(10L), app.failedBackupAccountIds.first())
            assertTrue(gateway.published.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (
        SecureStorageService, AppStorageService, FakeGateway, RelayBackupRetryService,
    ) -> Unit) {
        val directory = Files.createTempDirectory("vault-retry").toFile()
        try {
            val app = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            val storage = mockk<SecureStorageService>()
            val gateway = FakeGateway()
            val backup = BackupService(gateway, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            block(storage, app, gateway, RelayBackupRetryService(storage, app, backup))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeGateway : NostrRelayGateway {
        val published = mutableListOf<NostrEvent>()
        val results = ArrayDeque<Boolean>()
        override suspend fun publish(event: NostrEvent): PublishResult {
            published += event
            val acknowledged = results.removeFirstOrNull() ?: false
            return PublishResult(event.id, if (acknowledged) setOf("relay") else emptySet(), emptySet(), 1)
        }
        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int) = emptyList<NostrEvent>()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
