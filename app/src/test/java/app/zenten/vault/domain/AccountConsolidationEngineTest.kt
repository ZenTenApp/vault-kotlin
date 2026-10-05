package app.zenten.vault.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.data.kv.AccountSourceBackupState
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.ConsolidationTrigger
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.crypto.NostrKeyDerivation
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
    fun vault_key_backup_attempts_all_sources_in_order_and_requires_both_type_quorums() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getAccounts() } returns emptyList()
            factory.sources.values.forEach { it.writeResult = Result.success(Unit) }

            val result = engine.backupVaultKey(vaultKey)

            assertTrue(result.quorumReached)
            assertEquals(1, factory.runContextCreations)
            assertEquals(DefaultBackupSources.all.flatMap { listOf("index:${it.id}:1", "write:${it.id}:vaultkey") }, factory.calls)
        }
    }

    @Test
    fun vault_key_backup_does_not_write_when_source_index_fails() = runTest {
        fixture { engine, _, _, factory ->
            val first = factory.sources.values.first()
            first.indexResult = Result.failure(java.io.IOException("offline"))

            val result = engine.backupVaultKey(vaultKey)

            assertTrue(first.config.id in result.failedSourceIds)
            assertTrue("write:${first.config.id}:vaultkey" !in factory.calls)
        }
    }

    @Test
    fun failed_quorum_is_reported_after_all_sources_are_attempted() = runTest {
        fixture { engine, _, _, factory ->
            factory.sources.values.forEach { source ->
                source.writeResult = if (source.config.type == BackupSourceType.NOSTR_RELAY) Result.success(Unit)
                else Result.failure(IllegalStateException("rejected"))
            }

            val result = engine.backupVaultKey(vaultKey)

            assertTrue(result.relayQuorum)
            assertFalse(result.braveQuorum)
            assertEquals(DefaultBackupSources.all.size * 2, factory.calls.size)
        }
    }

    @Test
    fun scheduled_vault_key_consolidation_uses_the_stored_vault_key_and_ignores_accounts() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getAccounts() } returns listOf(account(10, "local"))

            val result = engine.consolidateVaultKey()!!

            assertTrue(result.quorumReached)
            // Only the Vault Key record is indexed and written; account IDs are never touched.
            assertEquals(
                DefaultBackupSources.all.flatMap { listOf("index:${it.id}:1", "write:${it.id}:vaultkey") },
                factory.calls,
            )
        }
    }

    @Test
    fun scheduled_vault_key_consolidation_is_skipped_while_its_own_result_is_fresh() = runTest {
        fixture { engine, _, _, factory ->
            // The first cycle records a completion timestamp at the fixed test clock.
            assertTrue(engine.consolidateVaultKey() != null)
            val callsAfterFirstRun = factory.calls.size

            assertEquals(null, engine.consolidateVaultKey())
            assertEquals(callsAfterFirstRun, factory.calls.size)
            // An explicit user action still bypasses freshness.
            assertTrue(engine.consolidateVaultKey(force = true) != null)
            assertTrue(factory.calls.size > callsAfterFirstRun)
        }
    }

    @Test
    fun scheduled_vault_key_consolidation_runs_again_while_a_vault_key_write_is_pending() = runTest {
        fixture { engine, appStorage, _, factory ->
            // Fail every write so the engine records a pending Vault Key retry entry.
            factory.sources.values.forEach { it.writeResult = Result.failure(IllegalStateException("rejected")) }
            assertFalse(engine.consolidateVaultKey()!!.quorumReached)
            assertTrue(appStorage.pendingSourceWrites.first().any { it.accountId == BackupAccountId.VAULT_KEY_VALUE })
            val callsAfterFirstRun = factory.calls.size

            // Freshness must not suppress a cycle that still has queued work.
            assertTrue(engine.consolidateVaultKey() != null)
            assertTrue(factory.calls.size > callsAfterFirstRun)
        }
    }

    @Test
    fun scheduled_vault_key_consolidation_does_nothing_without_a_stored_vault_key() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getVaultKey() } returns null

            assertEquals(null, engine.consolidateVaultKey(force = true))
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun targeted_consolidation_only_updates_and_writes_the_selected_account() = runTest {
        fixture { engine, appStorage, storage, factory ->
            val selected = account(10, "selected")
            val unrelated = account(20, "unrelated")
            every { storage.getAccountById(10) } returns selected
            every { storage.getAccounts() } returns listOf(selected, unrelated)
            val selectedId = BackupAccountId.associated(10)
            val verified = setOf(selectedId)
            factory.sources.values.forEach { source ->
                source.indexes += SourceIndex(emptySet(), emptyMap())
                source.indexes += SourceIndex(verified, emptyMap())
            }

            val result = engine.consolidateAccount(10)

            assertEquals(DefaultBackupSources.all.size, result.writes)
            assertTrue(factory.calls.filter { it.startsWith("write:") }.all { it.endsWith(":10") })
            assertTrue(appStorage.accountSourceStatuses.first().none { it.accountId == "20" })
        }
    }

    @Test
    fun targeted_consolidation_does_not_write_when_selected_account_is_already_present() = runTest {
        fixture { engine, _, storage, factory ->
            val selected = account(10, "selected")
            every { storage.getAccountById(10) } returns selected
            val present = setOf(BackupAccountId.associated(10))
            factory.sources.values.forEach { it.indexes += SourceIndex(present, emptyMap()) }

            val result = engine.consolidateAccount(10)

            assertEquals(0, result.writes)
            assertTrue(factory.calls.none { it.startsWith("write:") })
        }
    }

    @Test
    fun general_consolidation_excludes_vault_key_writes_and_statuses() = runTest {
        fixture { engine, appStorage, storage, factory ->
            every { storage.getAccounts() } returns listOf(account(10, "local"))
            val verified = setOf(BackupAccountId.VaultKey, BackupAccountId.associated(10))
            factory.sources.values.forEach { source ->
                source.indexes += SourceIndex(emptySet(), emptyMap())
                source.indexes += SourceIndex(verified, emptyMap())
            }

            engine.consolidate(ConsolidationTrigger.MANUAL, force = true)!!

            assertTrue(factory.calls.filter { it.startsWith("write:") }.none { it.endsWith(":vaultkey") })
            assertTrue(appStorage.accountSourceStatuses.first().none { it.accountId == BackupAccountId.VAULT_KEY_VALUE })
        }
    }

    @Test
    fun consolidation_does_not_write_accounts_already_indexed_at_a_source() = runTest {
        fixture { engine, _, storage, factory ->
            every { storage.getAccounts() } returns listOf(account(10, "local"))
            val allPresent = setOf(BackupAccountId.VaultKey, BackupAccountId.associated(10))
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
            val restored = account(20, "restored")
            val local = mutableListOf(account(10, "local"))
            every { storage.getAccounts() } answers { local.toList() }
            every { storage.addAccount(any()) } answers { local += firstArg<Account>() }
            factory.sources.values.forEach { source ->
                source.indexes += SourceIndex(setOf(BackupAccountId.VaultKey, BackupAccountId.associated(10)), emptyMap())
                source.indexes += SourceIndex(setOf(BackupAccountId.VaultKey, BackupAccountId.associated(10), BackupAccountId.associated(20)), emptyMap())
            }
            val first = factory.sources.getValue(DefaultBackupSources.all.first().id)
            val candidate = FakeCandidate(BackupAccountId.associated(20))
            first.indexes.clear()
            first.indexes += SourceIndex(
                setOf(BackupAccountId.VaultKey, BackupAccountId.associated(10), BackupAccountId.associated(20)),
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
            every { storage.getVaultKey() } returns vaultKey
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
        var runContextCreations = 0

        override fun createRunContext(vaultKey: Account): BackupSourceRunContext =
            object : BackupSourceRunContext {}.also { runContextCreations++ }

        override fun create(config: BackupSourceConfig, context: BackupSourceRunContext?): AccountBackupSource =
            sources.getValue(config.id)
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

        override suspend fun index(vaultKey: Account): Result<SourceIndex> {
            indexCount++
            calls += "index:${config.id}:$indexCount"
            return indexResult ?: Result.success(indexes.removeFirstOrNull() ?: SourceIndex(emptySet(), emptyMap()))
        }

        override suspend fun restoreCandidate(vaultKey: Account, candidate: RemoteAccountCandidate): Result<Account> =
            restored[candidate]?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("invalid"))

        override suspend fun write(vaultKey: Account, accountId: BackupAccountId, account: Account): Result<Unit> {
            calls += "write:${config.id}:${accountId.value}"
            return writeResult
        }
    }

    private data class FakeCandidate(
        override val id: BackupAccountId,
        override val sortOrder: Long = 1,
    ) : RemoteAccountCandidate

    companion object {
        val nostr = NostrKeyPair(
            NostrKeyDerivation.derive("alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady").publicKey.hex(),
            "01".repeat(32),
        )
        val vaultKey = account(1, "vaultKey")
        fun account(id: Long, key: String) = Account(id, key, "pass", nostr = nostr)

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
