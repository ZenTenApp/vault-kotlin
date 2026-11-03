package app.zenten.vault.ui.root

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CreateVaultKeyStep
import app.zenten.vault.data.kv.SecretCombinationsViewStep
import app.zenten.vault.ui.VaultDestinations
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        setupComplete: Boolean = false,
        pendingLocalBackupIds: Set<String> = emptySet(),
        createStep: CreateVaultKeyStep? = null,
        secretCombinationsStep: SecretCombinationsViewStep? = null,
        importedVaultKeySetupPending: Boolean = false,
    ): RootViewModel {
        val appStorage = mockk<AppStorageService>()
        every { appStorage.setupComplete } returns flowOf(setupComplete)
        every { appStorage.pendingLocalBackupAccountIds } returns flowOf(pendingLocalBackupIds)
        every { appStorage.createVaultKeyStep } returns flowOf(createStep)
        every { appStorage.secretCombinationsViewStep } returns flowOf(secretCombinationsStep)
        every { appStorage.importedVaultKeySetupPending } returns flowOf(importedVaultKeySetupPending)
        return RootViewModel(appStorage)
    }

    @Test
    fun incomplete_import_with_a_retained_key_routes_to_the_stored_key_retry() = runTest {
        val viewModel = viewModel(importedVaultKeySetupPending = true)

        assertEquals(VaultDestinations.GET_STARTED_SSH, viewModel.destination.first { it != null })
    }

    @Test
    fun the_retry_routing_survives_process_recreation() = runTest {
        // A second ViewModel with the same durable state represents a relaunch: no in-memory
        // import state exists, only the persisted pending flag.
        viewModel(importedVaultKeySetupPending = true).destination.first { it != null }
        val recreated = viewModel(importedVaultKeySetupPending = true)

        assertEquals(VaultDestinations.GET_STARTED_SSH, recreated.destination.first { it != null })
    }

    @Test
    fun generated_key_wizard_still_wins_over_the_import_retry_route() = runTest {
        // The create flow always records a step marker; item #6's resume behavior must not be
        // redirected to the imported-key retry.
        val viewModel = viewModel(
            createStep = CreateVaultKeyStep.BACKUP_INTRO,
            importedVaultKeySetupPending = true,
        )

        assertEquals(
            VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO,
            viewModel.destination.first { it != null },
        )
    }

    @Test
    fun incomplete_setup_without_a_retained_import_starts_onboarding() = runTest {
        val viewModel = viewModel()

        assertEquals(VaultDestinations.GET_STARTED, viewModel.destination.first { it != null })
    }

    @Test
    fun completed_setup_opens_accounts_even_if_a_stale_pending_flag_exists() = runTest {
        val viewModel = viewModel(setupComplete = true, importedVaultKeySetupPending = true)

        assertEquals(VaultDestinations.ACCOUNTS, viewModel.destination.first { it != null })
    }
}
