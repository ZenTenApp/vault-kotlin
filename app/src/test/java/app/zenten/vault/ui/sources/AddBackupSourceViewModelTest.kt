package app.zenten.vault.ui.sources

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class AddBackupSourceViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun save_normalizes_endpoint_derives_label_and_maps_advanced_flags() = runTest(dispatcher) {
        fixture { repository ->
            val viewModel = AddBackupSourceViewModel(repository)
            viewModel.setEndpoint("WSS://Relay.Example/path/")
            viewModel.setCountsTowardQuorum(true)
            viewModel.setUseForRecovery(true)
            viewModel.save()
            advanceUntilIdle()

            val custom = repository.current().single { !it.isDefault }
            assertTrue(viewModel.state.value.saved)
            assertEquals("relay.example", custom.label)
            assertEquals("wss://relay.example/path", custom.endpoint)
            assertEquals(BackupSourceType.NOSTR_RELAY, custom.type)
            assertTrue(custom.enabled)
            assertTrue(custom.countsTowardQuorum)
            assertTrue(custom.restoreEnabled)
            assertTrue(custom.trustedForRestore)
        }
    }

    @Test
    fun defaults_advanced_flags_to_off_and_trims_explicit_label() = runTest(dispatcher) {
        fixture { repository ->
            val viewModel = AddBackupSourceViewModel(repository)
            viewModel.setDisplayName("  Personal relay  ")
            viewModel.setEndpoint("wss://personal.example")
            viewModel.save()
            advanceUntilIdle()

            val custom = repository.current().single { !it.isDefault }
            assertEquals("Personal relay", custom.label)
            assertFalse(custom.countsTowardQuorum)
            assertFalse(custom.restoreEnabled)
            assertFalse(custom.trustedForRestore)
        }
    }

    @Test
    fun duplicate_preserves_form_and_emits_consumable_message() = runTest(dispatcher) {
        fixture { repository ->
            repository.addCustom("existing", BackupSourceType.NOSTR_RELAY, "wss://relay.example")
            val viewModel = AddBackupSourceViewModel(repository)
            viewModel.setDisplayName("Duplicate")
            viewModel.setEndpoint("WSS://RELAY.EXAMPLE/")
            viewModel.save()
            advanceUntilIdle()

            assertEquals("That backup source already exists", viewModel.state.value.message)
            assertEquals("Duplicate", viewModel.state.value.displayName)
            assertEquals("WSS://RELAY.EXAMPLE/", viewModel.state.value.endpoint)
            viewModel.messageShown()
            assertEquals(null, viewModel.state.value.message)
        }
    }

    @Test
    fun prevents_a_fourth_custom_nostr_relay() = runTest(dispatcher) {
        fixture { repository ->
            repeat(3) { repository.addCustom("relay $it", BackupSourceType.NOSTR_RELAY, "wss://relay$it.example") }
            val viewModel = AddBackupSourceViewModel(repository)
            viewModel.setEndpoint("wss://fourth.example")
            viewModel.save()
            advanceUntilIdle()

            assertEquals("You can add up to 3 custom relays", viewModel.state.value.message)
            assertEquals(3, repository.current().count { !it.isDefault })
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(block: suspend (BackupSourceRepository) -> Unit) {
        val directory = Files.createTempDirectory("source-ui").toFile()
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
