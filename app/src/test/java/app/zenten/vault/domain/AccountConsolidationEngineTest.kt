package app.zenten.vault.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccountConsolidationEngineTest {
    @Test
    fun master_backup_attempts_all_sources_in_order_and_requires_both_type_quorums() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getAccounts() } returns emptyList()
            factory.sources.values.forEach { it.writeResult = Result.success(Unit) }

            val result = engine.backupMaster(master)

            assertTrue(result.quorumReached)
            assertEquals(DefaultBackupSources.all.flatMap { listOf("index:${it.id}:1", "write:${it.id}:masterkey") }, factory.calls)
        }
    }

    @Test
    fun master_backup_does_not_write_when_source_index_fails() = runTest {
        fixture { engine, _, _, factory ->
            val first = factory.sources.values.first()
            first.indexResult = Result.failure(java.io.IOException("offline"))

            val result = engine.backupMaster(master)

            assertTrue(first.config.id in result.failedSourceIds)
            assertTrue("write:${first.config.id}:masterkey" !in factory.calls)
        }
    }

    @Test
    fun failed_quorum_is_reported_after_all_sources_are_attempted() = runTest {
        fixture { engine, _, _, factory ->
            factory.sources.values.forEach { source ->
                source.writeResult = if (source.config.type == BackupSourceType.NOSTR_RELAY) Result.success(Unit)
                else Result.failure(IllegalStateException("rejected"))
            }

            val result = engine.backupMaster(master)

            assertTrue(result.relayQuorum)
            assertFalse(result.braveQuorum)
            assertEquals(8, factory.calls.size)
        }
    }

    @Test
    fun consolidation_does_not_write_accounts_already_indexed_at_a_source() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getAccounts() } returns listOf(Account(10, "local", "pass"))
            val allPresent = setOf(BackupAccountId.Master, BackupAccountId.associated(10))
            factory.sources.values.forEach { source ->
                source.indexes += SourceIndex(allPresent, emptyMap())
                source.indexes += SourceIndex(allPresent, emptyMap())
            }

            val result = engine.consolidate(ConsolidationTrigger.MANUAL, force = true)!!

            assertEquals(0, result.writes)
            assertTrue(factory.calls.none { it.startsWith("write:") })
        }
    }

    @Test
    fun consolidation_restores_all_sources_before_backing_up_and_verifies_once_after_writes() = runTest {
        fixture { engine, appStorage, storage, factory ->
            val restored = Account(20, "restored", "pass")
            val local = mutableListOf(Account(10, "local", "pass"))
            every { storage.getAccounts() } answers { local.toList() }
            every { storage.addAccount(any()) } answers { local += firstArg<Account>() }
            factory.sources.values.forEach { source ->
                source.indexes += SourceIndex(setOf(BackupAccountId.Master, BackupAccountId.associated(10)), emptyMap())
                source.indexes += SourceIndex(setOf(BackupAccountId.Master, BackupAccountId.associated(10), BackupAccountId.associated(20)), emptyMap())
            }
            val first = factory.sources.getValue(DefaultBackupSources.all.first().id)
            val candidate = FakeCandidate(BackupAccountId.associated(20))
            first.indexes.clear()
            first.indexes += SourceIndex(
                setOf(BackupAccountId.Master, BackupAccountId.associated(10), BackupAccountId.associated(20)),
                mapOf(BackupAccountId.associated(20) to listOf(candidate)),
            )
            first.indexes += first.indexes.first()
            first.restored[candidate] = restored

            val result = engine.consolidate(ConsolidationTrigger.MANUAL, force = true)!!

            assertEquals(1, result.restored)
            val firstWrite = factory.calls.indexOfFirst { it.startsWith("write:") }
            val fourthInitialIndex = factory.calls.indexOf("index:${DefaultBackupSources.all.last().id}:1")
            assertTrue(firstWrite > fourthInitialIndex)
            assertEquals(2, factory.sources.values.first().indexCount)
            assertTrue(factory.sources.values.all { it.indexCount == 2 })
            assertTrue(appStorage.accountSourceStatuses.first().any {
                it.accountId == "20" && it.state == AccountSourceBackupState.PRESENT
            })
        }
    }

    private suspend fun TestScope.fixture(
        block: suspend (AccountConsolidationEngine, AppStorageService, SecureStorageService, FakeFactory) -> Unit,
    ) {
        val directory = Files.createTempDirectory("consolidation").toFile()
        try {
            val appStorage = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            val storage = mockk<SecureStorageService>()
            every { storage.getMasterAccount() } returns master
            every { storage.getAccounts() } returns emptyList()
            every { storage.getAccountById(any()) } returns null
            every { storage.addAccount(any()) } just runs
            val factory = FakeFactory()
            val engine = AccountConsolidationEngine(
                BackupSourceRepository(appStorage), factory, storage, appStorage,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC),
            )
            block(engine, appStorage, storage, factory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeFactory : BackupSourceFactory {
        val calls = mutableListOf<String>()
        val sources = DefaultBackupSources.all.associate { it.id to FakeSource(it, calls) }
        override fun create(config: BackupSourceConfig): AccountBackupSource = sources.getValue(config.id)
    }

    private class FakeSource(
        override val config: BackupSourceConfig,
        private val calls: MutableList<String>,
    ) : AccountBackupSource {
        val indexes = ArrayDeque<SourceIndex>()
        val restored = mutableMapOf<RemoteAccountCandidate, Account>()
        var writeResult: Result<Unit> = Result.success(Unit)
        var indexResult: Result<SourceIndex>? = null
        var indexCount = 0

        override suspend fun index(master: Account): Result<SourceIndex> {
            indexCount++
            calls += "index:${config.id}:$indexCount"
            return indexResult ?: Result.success(indexes.removeFirstOrNull() ?: SourceIndex(emptySet(), emptyMap()))
        }

        override suspend fun restoreCandidate(master: Account, candidate: RemoteAccountCandidate): Result<Account> =
            restored[candidate]?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("invalid"))

        override suspend fun write(master: Account, accountId: BackupAccountId, account: Account): Result<Unit> {
            calls += "write:${config.id}:${accountId.value}"
            return writeResult
        }
    }

    private data class FakeCandidate(
        override val id: BackupAccountId,
        override val sortOrder: Long = 1,
    ) : RemoteAccountCandidate

    companion object {
        val master = Account(1, "master", "pass")
    }
}
