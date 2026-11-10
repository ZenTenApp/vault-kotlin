package app.zenten.vault.ui.onboarding.recovervaultkey

import app.zenten.vault.domain.VaultKeyRecoveryService
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy
import app.zenten.vault.ui.onboarding.createvaultkey.passphraseStrengthForScore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverVaultKeyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun fixture(score: Int = VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE): Fixture {
        val recoveryService = mockk<VaultKeyRecoveryService>()
        val strengthChecker = mockk<PassphraseStrengthChecker>()
        coEvery { strengthChecker.measure(any()) } returns passphraseStrengthForScore(score)
        coEvery { recoveryService.recover(any(), any()) } returns Result.success(Unit)
        return Fixture(recoveryService, strengthChecker)
    }

    private inner class Fixture(
        val recoveryService: VaultKeyRecoveryService,
        val strengthChecker: PassphraseStrengthChecker,
    ) {
        val viewModel = RecoverVaultKeyViewModel(recoveryService, strengthChecker)
    }

    @Test
    fun a_weak_passphrase_is_rejected_before_any_source_is_queried() = runTest {
        val fixture = fixture(score = VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1)

        fixture.viewModel.recover("weak")
        advanceUntilIdle()

        val feedback = fixture.viewModel.passphraseFeedback.value
        assertTrue(feedback.rejectedAsWeak)
        assertEquals(
            VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE - 1,
            feedback.measured?.strength?.score,
        )
        assertFalse(fixture.viewModel.state.value.inProgress)
        assertFalse(fixture.viewModel.state.value.completed)
        coVerify(exactly = 0) { fixture.recoveryService.recover(any(), any()) }
    }

    @Test
    fun a_passphrase_meeting_the_policy_proceeds_to_recovery() = runTest {
        val fixture = fixture()
        // Recovery parses the 24 combination entries, so they have to hold numbers.
        fixture.viewModel.entries.indices.forEach { fixture.viewModel.updateEntry(it, "1") }

        fixture.viewModel.recover("correct horse battery staple")
        advanceUntilIdle()

        assertFalse(fixture.viewModel.passphraseFeedback.value.rejectedAsWeak)
        assertTrue(fixture.viewModel.state.value.completed)
        coVerify(exactly = 1) { fixture.recoveryService.recover(any(), "correct horse battery staple") }
    }
}
