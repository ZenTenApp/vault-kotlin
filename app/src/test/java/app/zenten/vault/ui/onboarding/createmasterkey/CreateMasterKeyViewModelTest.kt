package app.zenten.vault.ui.onboarding.createmasterkey

import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.SetupService
import app.zenten.vault.data.kv.AppStorageService
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import app.zenten.vault.crypto.SshKeyCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CreateMasterKeyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)

    private fun viewModel(): CreateMasterKeyViewModel {
        val secureStorage = mockk<SecureStorageService>()
        val appStorage = mockk<AppStorageService>()
        val engine = mockk<AccountConsolidationEngine>()
        every { secureStorage.addMasterAccount(any()) } just runs
        coJustRun { appStorage.clearSetupComplete() }
        coJustRun { appStorage.setCreateMasterKeyStep(any()) }
        coEvery { engine.backupMaster(any()) } returns AccountConsolidationEngine.MasterBackupResult(
            relayQuorum = true, braveQuorum = true, successfulSourceIds = emptySet(), failedSourceIds = emptySet(),
        )
        return CreateMasterKeyViewModel(clock, SetupService(secureStorage, appStorage, engine))
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Waits for verification to settle.
     *
     * Key generation deliberately runs on [Dispatchers.Default] (bcrypt_pbkdf is
     * slow), which is a real dispatcher the test scheduler cannot fast-forward.
     * Suspending on the state flow lets that background work actually finish
     * instead of asserting on a still-in-progress state.
     */
    private suspend fun CreateMasterKeyViewModel.awaitVerificationResult(): CreateMasterKeyState =
        state.first { !it.generating }

    @Test
    fun rejects_a_mismatched_re_entry_without_generating_a_key() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.choosePassphrase("correct horse battery staple")

        viewModel.verifyAndGenerate("correct horse battery stapl")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.mismatch)
        // A mismatch is decided synchronously; no generation must have started.
        assertFalse(viewModel.state.value.generating)
        assertFalse(viewModel.state.value.keyGenerated)
        assertFalse(viewModel.state.value.navigateToBackup)
    }

    @Test
    fun generates_a_key_encrypted_with_the_confirmed_passphrase_and_signals_navigation() = runTest(dispatcher) {
        val passphrase = "correct horse battery staple"
        val viewModel = viewModel()
        viewModel.choosePassphrase(passphrase)

        viewModel.verifyAndGenerate(passphrase)
        val state = viewModel.awaitVerificationResult()

        assertTrue(state.keyGenerated)
        assertTrue(state.navigateToBackup)
        assertFalse(state.mismatch)
        assertFalse(state.generating)

    }

    @Test
    fun clears_the_navigation_signal_once_the_backup_step_has_been_shown() = runTest(dispatcher) {
        val passphrase = "correct horse battery staple"
        val viewModel = viewModel()
        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()

        viewModel.onBackupStepShown()

        assertFalse(viewModel.state.value.navigateToBackup)
        assertTrue(viewModel.state.value.keyGenerated)
    }

    @Test
    fun resets_ui_state_when_a_new_passphrase_is_chosen() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.choosePassphrase("first passphrase")
        viewModel.verifyAndGenerate("first passphrase")
        viewModel.awaitVerificationResult()

        viewModel.choosePassphrase("second passphrase")

        assertFalse(viewModel.state.value.keyGenerated)
    }

    @Test
    fun reports_a_missing_passphrase_instead_of_verifying_against_nothing() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.verifyAndGenerate("anything")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.passphraseMissing)
    }

    @Test
    fun clears_the_mismatch_message_when_the_field_is_edited_again() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.choosePassphrase("passphrase")
        viewModel.verifyAndGenerate("wrong")
        assertTrue(viewModel.state.value.mismatch)

        viewModel.clearVerificationMessage()

        assertFalse(viewModel.state.value.mismatch)
    }
}
