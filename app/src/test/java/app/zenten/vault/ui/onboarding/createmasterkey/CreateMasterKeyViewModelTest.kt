package app.zenten.vault.ui.onboarding.createmasterkey

import app.zenten.vault.data.model.Account
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
import io.mockk.slot
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

    private fun viewModel(secureStorage: SecureStorageService = mockk()): CreateMasterKeyViewModel {
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
    fun rejects_passphrases_that_do_not_satisfy_every_creation_rule() {
        val viewModel = viewModel()
        val invalidPassphrases = listOf(
            "Short1!",         // fewer than eight characters
            "lowercase1!",     // no uppercase letter
            "UPPERCASE1!",     // no lowercase letter
            "NoNumber!",       // no numeric character
            "NoSpecial1",       // no special character
            "Whitespace1 ",     // whitespace is not a special character
        )

        invalidPassphrases.forEach { passphrase ->
            assertFalse(viewModel.choosePassphrase(passphrase), passphrase)
        }

        viewModel.verifyAndGenerate("Whitespace1 ")
        assertTrue(viewModel.state.value.passphraseMissing)
    }

    @Test
    fun accepts_a_passphrase_that_satisfies_every_creation_rule() {
        assertTrue(viewModel().choosePassphrase("Valid123!"))
    }

    @Test
    fun invalid_choice_discards_a_previously_accepted_passphrase() {
        val viewModel = viewModel()
        assertTrue(viewModel.choosePassphrase("Valid123!"))

        assertFalse(viewModel.choosePassphrase("invalid"))
        viewModel.verifyAndGenerate("Valid123!")

        assertTrue(viewModel.state.value.passphraseMissing)
    }

    @Test
    fun rejects_a_mismatched_re_entry_without_generating_a_key() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.choosePassphrase("Correct1 horse!")

        viewModel.verifyAndGenerate("Correct1 horse")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.mismatch)
        // A mismatch is decided synchronously; no generation must have started.
        assertFalse(viewModel.state.value.generating)
        assertFalse(viewModel.state.value.keyGenerated)
        assertFalse(viewModel.state.value.navigateToBackup)
    }

    @Test
    fun generates_a_key_encrypted_with_the_confirmed_passphrase_and_signals_navigation() = runTest(dispatcher) {
        val passphrase = "Correct1 horse!"
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
    fun stores_the_master_key_in_the_same_single_line_format_used_by_associated_account_exports() = runTest(dispatcher) {
        val storedMaster = slot<Account>()
        val secureStorage = mockk<SecureStorageService>()
        val viewModel = viewModel(secureStorage)
        val passphrase = "Correct1 horse!"

        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()
        io.mockk.verify(exactly = 1) { secureStorage.addMasterAccount(capture(storedMaster)) }

        assertFalse(storedMaster.captured.zenTenKey.contains("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertFalse(storedMaster.captured.zenTenKey.contains('\n'))
        assertEquals(
            storedMaster.captured.zenTenKey,
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(storedMaster.captured.zenTenKey),
        )
    }

    @Test
    fun clears_the_navigation_signal_once_the_backup_step_has_been_shown() = runTest(dispatcher) {
        val passphrase = "Correct1 horse!"
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
        viewModel.choosePassphrase("First1 passphrase!")
        viewModel.verifyAndGenerate("First1 passphrase!")
        viewModel.awaitVerificationResult()

        viewModel.choosePassphrase("Second2 passphrase!")

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
        viewModel.choosePassphrase("Passphrase1!")
        viewModel.verifyAndGenerate("wrong")
        assertTrue(viewModel.state.value.mismatch)

        viewModel.clearVerificationMessage()

        assertFalse(viewModel.state.value.mismatch)
    }
}
