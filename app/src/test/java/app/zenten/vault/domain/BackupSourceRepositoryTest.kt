package app.zenten.vault.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.data.kv.AppStorageService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class BackupSourceRepositoryTest {
    @Test
    fun defaults_have_required_order_and_configuration() = runTest {
        fixture { repository ->
            val sources = repository.current()
            assertEquals(listOf("appdata1", "appdata2", "litesync1", "litesync2", "brave sync"), sources.map { it.label })
            assertEquals(
                listOf(
                    "wss://appdata1.zentext.me",
                    "wss://appdata2.nostr.box",
                    "https://litesync1.zentext.me/litesync",
                    "https://litesync2.nostr.box/litesync",
                    "https://sync-v2.brave.com/v2",
                ),
                sources.map { it.endpoint },
            )
            assertTrue(sources.all { it.enabled && it.isDefault && !it.removable && it.countsTowardQuorum && it.trustedForRestore })
        }
    }

    @Test
    fun supports_both_custom_source_types_and_rejects_normalized_duplicates() = runTest {
        fixture { repository ->
            val relay = repository.addCustom("extra", BackupSourceType.NOSTR_RELAY, "WSS://Relay.Example/")
            val brave = repository.addCustom("sync", BackupSourceType.BRAVE_SYNC, "https://sync.example/path/")
            assertEquals("wss://relay.example", relay.endpoint)
            assertEquals("https://sync.example/path", brave.endpoint)
            try {
                repository.addCustom("duplicate", BackupSourceType.NOSTR_RELAY, "wss://relay.example")
                fail("Expected normalized duplicate source to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
            repository.setEnabled(relay.id, false)
            assertTrue(repository.current().first { it.id == relay.id }.enabled.not())
            repository.remove(relay.id)
            assertTrue(repository.current().none { it.id == relay.id })
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(block: suspend (BackupSourceRepository) -> Unit) {
        val directory = Files.createTempDirectory("source-repository").toFile()
        try {
            val storage = AppStorageService(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(directory, "settings.preferences_pb")
            })
            block(BackupSourceRepository(storage))
        } finally {
            directory.deleteRecursively()
        }
    }
}
