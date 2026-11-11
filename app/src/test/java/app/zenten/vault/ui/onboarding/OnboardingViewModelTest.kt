package app.zenten.vault.ui.onboarding

import app.zenten.vault.domain.SetupService
import app.zenten.vault.domain.VaultKeyPersistedException
import app.zenten.vault.domain.VaultKeySession
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy
import app.zenten.vault.ui.onboarding.createvaultkey.passphraseStrengthForScore
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Builds a harness whose durable "imported backup pending" state defaults to false, so
     * individual tests only state the resume fact they care about.
     *
     * The measured strength defaults to a passing bucket so existing tests exercise the import
     * rather than the new gate; tests that care about the gate set it explicitly.
     */
    private fun fixture(
        pendingImport: Boolean = false,
        score: Int = VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE,
    ): Fixture {
        val setupService = mockk<SetupService>()
        val session = mockk<VaultKeySession>()
        val strengthChecker = mockk<PassphraseStrengthChecker>()
        coJustRun { session.establish(any()) }
        coEvery { setupService.hasPendingImportedVaultKeyBackup() } returns pendingImport
        coEvery { strengthChecker.measure(any()) } returns passphraseStrengthForScore(score)
        return Fixture(setupService, session, strengthChecker)
    }

    private inner class Fixture(
        val setupService: SetupService,
        val session: VaultKeySession,
        val strengthChecker: PassphraseStrengthChecker,
    ) {
        val viewModel = OnboardingViewModel(setupService, session, clock, strengthChecker)

        /**
         * Waits for the import to finish after it reaches the bcrypt parse.
         *
         * Parsing runs on [Dispatchers.Default], a real dispatcher the test scheduler cannot
         * fast-forward, so `advanceUntilIdle` can return while the parse is still in flight.
         * Suspending on the state flow lets that work actually settle before asserting.
         */
        suspend fun awaitSetupFinished() {
            viewModel.state.first { !it.inProgress }
        }
    }

    @Test
    fun a_weak_passphrase_is_rejected_before_anything_is_persisted() = runTest {
        val fixture = fixture(score = VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1)

        // The key itself is valid, so only the passphrase rule can explain the refusal.
        fixture.viewModel.performSetup(goldenEncryptedKey(), "phase1-test-passphrase")
        advanceUntilIdle()

        val feedback = fixture.viewModel.passphraseFeedback.value
        assertTrue(feedback.rejectedAsWeak)
        assertEquals(
            VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1,
            feedback.measured?.strength?.score,
        )
        assertFalse(fixture.viewModel.state.value.inProgress)
        assertFalse(fixture.viewModel.state.value.canRetryStoredVaultKey)
        coVerify(exactly = 0) { fixture.setupService.performSetup(any()) }
    }

    @Test
    fun parse_failure_does_not_offer_stored_key_retry() = runTest {
        val fixture = fixture()

        // Long enough to clear the passphrase policy, so the parse failure is what is reported.
        fixture.viewModel.performSetup("not-an-encrypted-openssh-key", "long-enough-passphrase")
        fixture.awaitSetupFinished()

        val state = fixture.viewModel.state.value
        assertTrue(state.error != null)
        // Nothing was persisted, so there is no stored key to retry.
        assertFalse(state.canRetryStoredVaultKey)
        coVerify(exactly = 0) { fixture.setupService.performSetup(any()) }
    }

    @Test
    fun post_persistence_backup_failure_offers_stored_key_retry() = runTest {
        val fixture = fixture()
        coEvery { fixture.setupService.performSetup(any()) } returns
            Result.failure(VaultKeyPersistedException("VaultKey saved locally; backup incomplete.", IllegalStateException("quorum")))

        fixture.viewModel.performSetup(goldenEncryptedKey(), "phase1-test-passphrase")
        fixture.awaitSetupFinished()

        val state = fixture.viewModel.state.value
        assertTrue(state.canRetryStoredVaultKey)
        assertTrue(state.error?.contains("saved locally") == true)
    }

    @Test
    fun successful_retry_completes_setup_and_drops_the_retry_affordance() = runTest {
        val fixture = fixture(pendingImport = true)
        coEvery { fixture.setupService.retryVaultKeyBackup() } returns Result.success(Unit)

        fixture.viewModel.retryStoredVaultKey()
        advanceUntilIdle()

        val state = fixture.viewModel.state.value
        assertTrue(state.completed)
        assertFalse(state.canRetryStoredVaultKey)
    }

    @Test
    fun recreated_view_model_restores_the_retry_affordance_from_durable_state() = runTest {
        // A new ViewModel stands in for a process recreation: only the durable pending flag
        // survives, and it must be enough to expose the stored-key retry before any failure.
        val first = fixture(pendingImport = true)
        advanceUntilIdle()
        assertTrue(first.viewModel.state.value.canRetryStoredVaultKey)

        val recreated = fixture(pendingImport = true)
        advanceUntilIdle()
        assertTrue(recreated.viewModel.state.value.canRetryStoredVaultKey)
    }

    /** Real passphrase-encrypted Ed25519 key from the shared golden crypto vectors. */
    private fun goldenEncryptedKey(): String {
        val root = Json.parseToJsonElement(
            requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json")).bufferedReader().use { it.readText() },
        ).jsonObject
        return root["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
    }
}
