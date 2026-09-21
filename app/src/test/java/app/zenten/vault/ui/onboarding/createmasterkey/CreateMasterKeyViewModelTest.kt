package app.zenten.vault.ui.onboarding.createmasterkey

import app.zenten.vault.crypto.Seedify
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
        val viewModel = CreateMasterKeyViewModel(clock)
        viewModel.choosePassphrase("correct horse battery staple")

        viewModel.verifyAndGenerate("correct horse battery stapl")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.mismatch)
        // A mismatch is decided synchronously; no generation must have started.
        assertFalse(viewModel.state.value.generating)
        assertFalse(viewModel.state.value.keyGenerated)
        assertFalse(viewModel.state.value.navigateToBackup)
        assertNull(viewModel.generatedKey())
    }

    @Test
    fun generates_a_key_encrypted_with_the_confirmed_passphrase_and_signals_navigation() = runTest(dispatcher) {
        val passphrase = "correct horse battery staple"
        val viewModel = CreateMasterKeyViewModel(clock)
        viewModel.choosePassphrase(passphrase)

        viewModel.verifyAndGenerate(passphrase)
        val state = viewModel.awaitVerificationResult()

        assertTrue(state.keyGenerated)
        assertTrue(state.navigateToBackup)
        assertFalse(state.mismatch)
        assertFalse(state.generating)

        val generated = requireNotNull(viewModel.generatedKey())
        // createdAt is the clock's millis in microseconds, matching account ids.
        assertEquals(clock.millis() * 1_000L, generated.createdAt)
        // The key must be openable with exactly the confirmed passphrase.
        val parsed = SshKeyCodec.parse(generated.privateKeyPem, passphrase.toCharArray())
        assertTrue(parsed.encrypted)
        assertEquals(
            SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
            generated.publicKey,
        )
        // Seed extraction must work, since recovery material derives from it.
        assertEquals(24, Seedify.toMnemonic(parsed.seed).split(" ").size)
    }

    @Test
    fun clears_the_navigation_signal_once_the_backup_step_has_been_shown() = runTest(dispatcher) {
        val passphrase = "correct horse battery staple"
        val viewModel = CreateMasterKeyViewModel(clock)
        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()

        viewModel.onBackupStepShown()

        assertFalse(viewModel.state.value.navigateToBackup)
        // The generated key survives so later backup steps can use it.
        assertTrue(viewModel.state.value.keyGenerated)
        assertNotNull(viewModel.generatedKey())
    }

    @Test
    fun discards_a_key_generated_for_a_passphrase_the_user_then_changed() = runTest(dispatcher) {
        val viewModel = CreateMasterKeyViewModel(clock)
        viewModel.choosePassphrase("first passphrase")
        viewModel.verifyAndGenerate("first passphrase")
        viewModel.awaitVerificationResult()
        assertNotNull(viewModel.generatedKey())

        viewModel.choosePassphrase("second passphrase")

        assertNull(viewModel.generatedKey())
        assertFalse(viewModel.state.value.keyGenerated)
    }

    @Test
    fun reports_a_missing_passphrase_instead_of_verifying_against_nothing() = runTest(dispatcher) {
        val viewModel = CreateMasterKeyViewModel(clock)

        viewModel.verifyAndGenerate("anything")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.passphraseMissing)
        assertNull(viewModel.generatedKey())
    }

    @Test
    fun clears_the_mismatch_message_when_the_field_is_edited_again() = runTest(dispatcher) {
        val viewModel = CreateMasterKeyViewModel(clock)
        viewModel.choosePassphrase("passphrase")
        viewModel.verifyAndGenerate("wrong")
        assertTrue(viewModel.state.value.mismatch)

        viewModel.clearVerificationMessage()

        assertFalse(viewModel.state.value.mismatch)
    }
}
