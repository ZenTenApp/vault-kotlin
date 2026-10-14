package app.zenten.vault.ui.onboarding.createvaultkey

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.SetupService
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CreateVaultKeyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)

    private fun viewModel(
        secureStorage: SecureStorageService = mockk(),
        engine: AccountConsolidationEngine = mockk(),
        clockOverride: Clock = clock,
    ): CreateVaultKeyViewModel {
        val appStorage = mockk<AppStorageService>()
        val remoteOperationPrecondition = mockk<RemoteOperationPrecondition>()
        coEvery { remoteOperationPrecondition.check() } returns RemoteOperationReadiness.READY
        every { secureStorage.addVaultKey(any()) } just runs
        every { appStorage.sshKdfRounds } returns flowOf(SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS)
        every { appStorage.vaultKeyPasteAttempts } returns flowOf(0)
        every { appStorage.vaultKeyPasteBlockedUntilMillis } returns flowOf(null)
        coJustRun { appStorage.clearSetupComplete() }
        coJustRun { appStorage.setCreateVaultKeyStep(any()) }
        coJustRun { appStorage.setVaultKeyPasteGuard(any(), any()) }
        coEvery { engine.backupVaultKey(any(), any()) } returns AccountConsolidationEngine.VaultKeyBackupResult(
            relayQuorum = true, braveQuorum = true, successfulSourceIds = emptySet(), failedSourceIds = emptySet(),
        )
        return CreateVaultKeyViewModel(
            clockOverride,
            SetupService(secureStorage, appStorage, engine, remoteOperationPrecondition),
            secureStorage,
            appStorage,
        )
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
    private suspend fun CreateVaultKeyViewModel.awaitVerificationResult(): CreateVaultKeyState =
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
    fun reuses_secret_combination_indices_derived_during_key_generation() = runTest(dispatcher) {
        val storedVaultKey = slot<Account>()
        val secureStorage = mockk<SecureStorageService>()
        val viewModel = viewModel(secureStorage)
        val passphrase = "Correct1 horse!"

        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()
        io.mockk.verify { secureStorage.addVaultKey(capture(storedVaultKey)) }

        val seed = SshKeyCodec.parse(storedVaultKey.captured.zenTenKey, passphrase.toCharArray()).seed
        assertEquals(Seedify.toBraveSyncWordIndices(seed), viewModel.state.value.secretCombinationIndices)
        assertFalse(viewModel.state.value.secretCombinationsLoading)
    }

    @Test
    fun stores_the_vault_key_in_the_same_single_line_format_used_by_associated_account_exports() = runTest(dispatcher) {
        val storedVaultKey = slot<Account>()
        val secureStorage = mockk<SecureStorageService>()
        val viewModel = viewModel(secureStorage)
        val passphrase = "Correct1 horse!"

        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()
        io.mockk.verify(exactly = 1) { secureStorage.addVaultKey(capture(storedVaultKey)) }

        assertFalse(storedVaultKey.captured.zenTenKey.contains("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertFalse(storedVaultKey.captured.zenTenKey.contains('\n'))
        assertEquals(
            storedVaultKey.captured.zenTenKey,
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(storedVaultKey.captured.zenTenKey),
        )
    }

    @Test
    fun initial_relay_backup_uses_the_brave_sub_account_nostr_identity() = runTest(dispatcher) {
        val storedVaultKey = slot<Account>()
        val backedUpVaultKey = slot<Account>()
        val secureStorage = mockk<SecureStorageService>()
        val engine = mockk<AccountConsolidationEngine>()
        val viewModel = viewModel(secureStorage, engine)
        val passphrase = "Correct1 horse!"
        coEvery { engine.backupVaultKey(capture(backedUpVaultKey), any()) } returns
            AccountConsolidationEngine.VaultKeyBackupResult(true, true, emptySet(), emptySet())

        viewModel.choosePassphrase(passphrase)
        viewModel.verifyAndGenerate(passphrase)
        viewModel.awaitVerificationResult()
        io.mockk.verify { secureStorage.addVaultKey(capture(storedVaultKey)) }

        val seed = SshKeyCodec.parse(storedVaultKey.captured.zenTenKey, passphrase.toCharArray()).seed
        val expected = NostrKeyDerivation.derive(Seedify.toBraveAccountMnemonic(seed))
        assertEquals(expected.publicKey.joinToString("") { "%02x".format(it) }, backedUpVaultKey.captured.nostr.pubkey)
        assertEquals(expected.privateKey.joinToString("") { "%02x".format(it) }, backedUpVaultKey.captured.nostr.privkey)
        val expectedStored = NostrKeyDerivation.derive(Seedify.toMnemonic(seed))
        assertEquals(expectedStored.publicKey.joinToString("") { "%02x".format(it) }, storedVaultKey.captured.nostr.pubkey)
        assertTrue(storedVaultKey.captured.nostr != backedUpVaultKey.captured.nostr)
        coVerify(exactly = 1) { engine.backupVaultKey(any(), any()) }
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

    @Test
    fun first_invalid_paste_blocks_the_wizard_for_one_minute() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val failure = viewModel.registerFailedVaultKeyPaste()

        assertEquals(VaultKeyPasteFailure.Blocked(clock.millis() + 60_000L), failure)
        assertEquals(1, viewModel.pasteGuard.value.attempts)
        assertEquals(clock.millis() + 60_000L, viewModel.pasteGuard.value.blockedUntilMillis)
    }

    @Test
    fun successive_invalid_pastes_extend_the_block_by_one_minute_each() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            clock.millis() + 60_000L,
            (viewModel.registerFailedVaultKeyPaste() as VaultKeyPasteFailure.Blocked).untilMillis,
        )
        assertEquals(
            clock.millis() + 120_000L,
            (viewModel.registerFailedVaultKeyPaste() as VaultKeyPasteFailure.Blocked).untilMillis,
        )
        assertEquals(
            clock.millis() + 180_000L,
            (viewModel.registerFailedVaultKeyPaste() as VaultKeyPasteFailure.Blocked).untilMillis,
        )
    }

    @Test
    fun fourth_invalid_paste_returns_to_export_and_resets_the_guard() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repeat(3) { viewModel.registerFailedVaultKeyPaste() }

        val fourth = viewModel.registerFailedVaultKeyPaste()

        assertEquals(VaultKeyPasteFailure.ReturnToExport, fourth)
        assertEquals(VaultKeyPasteGuard(), viewModel.pasteGuard.value)
    }

    @Test
    fun expired_block_is_lifted_while_the_failure_count_is_kept() = runTest(dispatcher) {
        var now = Instant.parse("2026-02-01T00:00:00Z")
        val mutableClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = now
        }
        val viewModel = viewModel(clockOverride = mutableClock)
        advanceUntilIdle()
        viewModel.registerFailedVaultKeyPaste()
        assertNotNull(viewModel.pasteGuard.value.blockedUntilMillis)

        now = now.plusSeconds(61)
        viewModel.onPasteLockoutExpired()

        assertEquals(1, viewModel.pasteGuard.value.attempts)
        assertNull(viewModel.pasteGuard.value.blockedUntilMillis)
    }
}
