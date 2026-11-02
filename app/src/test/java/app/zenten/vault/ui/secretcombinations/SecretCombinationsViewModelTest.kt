package app.zenten.vault.ui.secretcombinations

import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.SecretCombinationsViewStep
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.VaultKeySession
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers the Settings-initiated Secret Combinations flow's gate and durable step markers.
 *
 * The displayed values themselves come from the same `Seedify.toVaultKeyBackupWordIndices`
 * derivation the create wizard uses; this test asserts the review flow decrypts the stored
 * VaultKey and reports those exact positions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretCombinationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val passphrase = "Correct1 horse!"
    private lateinit var secureStorage: SecureStorageService
    private lateinit var appStorage: AppStorageService
    private lateinit var accountService: AccountService
    private lateinit var vaultKeySession: VaultKeySession
    private lateinit var expectedIndices: List<Int>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val generated = SshKeyCodec.generateEncrypted(
            createdAt = 1L,
            passphrase = passphrase.toCharArray(),
            rounds = SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS,
        )
        val seed = SshKeyCodec.parse(generated.privateKeyPem, passphrase.toCharArray()).seed
        expectedIndices = Seedify.toVaultKeyBackupWordIndices(seed)
        secureStorage = mockk()
        every { secureStorage.getVaultKey() } returns Account(
            createdAt = generated.createdAt,
            zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem),

            nostr = NostrKeyPair(pubkey = "", privkey = ""),
        )
        appStorage = mockk()
        coJustRun { appStorage.setSecretCombinationsViewStep(any()) }
        coJustRun { appStorage.clearSecretCombinationsViewStep() }
        accountService = mockk()
        every { accountService.verifyVaultKeyPassphrase(any()) } answers {
            if (firstArg<String>() == passphrase) Result.success(Unit)
            else Result.failure(IllegalArgumentException())
        }
        vaultKeySession = VaultKeySession(secureStorage)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SecretCombinationsViewModel(secureStorage, appStorage, accountService, vaultKeySession)

    @Test
    fun wrongPassphrase_reportsAnErrorWithoutEnteringTheFlow() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.submitPassphrase("not the passphrase")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.passphraseError)
        assertFalse(viewModel.state.value.passphraseVerified)
        coVerify(exactly = 0) { appStorage.setSecretCombinationsViewStep(any()) }
    }

    @Test
    fun correctPassphrase_persistsShowBeforeReportingSuccess() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.submitPassphrase(passphrase)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.passphraseVerified)
        assertFalse(viewModel.state.value.passphraseError)
        coVerify(exactly = 1) { appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.SHOW) }
    }

    @Test
    fun loadSecretCombinationIndices_derivesTheStoredVaultKeyPositions() = runTest(dispatcher) {
        val viewModel = viewModel()
        // The displayed positions are derived from the session passphrase, so simulate the gate.
        vaultKeySession.establish(passphrase.toCharArray())
        viewModel.loadSecretCombinationIndices()
        // Derivation runs on Dispatchers.Default, which the test scheduler cannot fast-forward.
        viewModel.state.first { !it.secretCombinationsLoading }

        assertEquals(expectedIndices, viewModel.state.value.secretCombinationIndices)
    }

    @Test
    fun startConfirm_persistsConfirmBeforeInvokingTheCallback() = runTest(dispatcher) {
        val viewModel = viewModel()
        var navigated = false
        viewModel.startConfirm { navigated = true }
        advanceUntilIdle()

        assertTrue(navigated)
        coVerify(exactly = 1) { appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.CONFIRM) }
    }

    @Test
    fun startShow_persistsShowBeforeInvokingTheCallback() = runTest(dispatcher) {
        val viewModel = viewModel()
        var navigated = false
        viewModel.startShow { navigated = true }
        advanceUntilIdle()

        assertTrue(navigated)
        coVerify(exactly = 1) { appStorage.setSecretCombinationsViewStep(SecretCombinationsViewStep.SHOW) }
    }

    @Test
    fun completeFlow_clearsTheStepAndInMemoryEntries() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.updateSecretCombinationEntry(0, "12")
        viewModel.advanceSecretCombinationEntry()
        var completed = false
        viewModel.completeFlow { completed = true }
        advanceUntilIdle()

        assertTrue(completed)
        coVerify(exactly = 1) { appStorage.clearSecretCombinationsViewStep() }
        assertEquals("", viewModel.secretCombinationEntries[0])
        assertEquals(0, viewModel.activeSecretCombinationSegment)
    }
}