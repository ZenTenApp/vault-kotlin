package app.zenten.vault.ui.auth

import android.app.Application
import android.content.Context
import app.zenten.vault.crypto.KeystoreCryptoService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.data.secure.UnreadableReason
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.VaultKeySession
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Verifies the launch gate tells an unreadable vault apart from a fresh install.
 *
 * This is the regression guard for the device-migration defect: the gate previously asked only
 * "is there a record?", so a vault whose wrapping key was left behind on the old phone looked
 * exactly like a brand-new install and the user was shown an empty account list with no
 * explanation. Every test asserts the resulting state, because the state is what picks the screen.
 *
 * Storage is a real [SecureStorageService] over a stubbed crypto transform, the same technique
 * `SecureStorageServiceTest` uses. Mocking the service is not an option — `vaultKeyState` is a val
 * on a final class — and would in any case only restate this class's own decision logic.
 *
 * JUnit 4 annotations are used because Robolectric supplies a JUnit 4 runner; the class still runs
 * on the JUnit Platform through the Vintage engine configured in `app/build.gradle.kts`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultUnlockGateViewModelTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // `stateIn` runs on viewModelScope, which needs a Main dispatcher in a host-side test.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Reads the gate's first settled state.
     *
     * The gate shares its state with `WhileSubscribed`, so it reports `Loading` until something
     * collects — `.value` alone would never advance past the initial value. Awaiting the first
     * non-Loading emission also mirrors what the UI does, so these tests assert on the state the
     * screen would actually receive.
     */
    private suspend fun VaultUnlockGateViewModel.settled(): VaultUnlockGateState =
        state.first { it !is VaultUnlockGateState.Loading }

    /**
     * Waits for the gate to publish a specific state.
     *
     * Needed for transitions, because `state` is a `StateFlow` that replays its current value — a
     * plain `first { it !is Loading }` on an already-settled gate returns the old state immediately
     * and would make a transition test pass or fail for reasons unrelated to the change.
     */
    private suspend fun VaultUnlockGateViewModel.awaitState(expected: VaultUnlockGateState) =
        state.first { it == expected }

    private fun gate(
        unlocked: Boolean = false,
        storage: SecureStorageService = healthyStorage(),
    ): VaultUnlockGateViewModel = VaultUnlockGateViewModel(
        secureStorage = storage,
        vaultKeySession = mockk<VaultKeySession> { every { isUnlocked } returns MutableStateFlow(unlocked) },
        scheduler = mockk<BackupWorkScheduler> { coJustRun { scheduleStartupConsolidation() } },
    )

    /** Storage whose wrapping key exists, built over a reversible transform. */
    private fun healthyStorage(): SecureStorageService =
        SecureStorageService(context, cryptoWithKey(present = true))

    /**
     * Crypto stand-in whose decrypt success follows key presence.
     *
     * The two must move together: a fake that reports no key while still decrypting would model a
     * state the real implementation cannot produce, and would let a broken gate pass.
     */
    private fun cryptoWithKey(present: Boolean): KeystoreCryptoService = mockk {
        every { encrypt(any()) } answers {
            "enc:" + Base64.getEncoder().encodeToString(firstArg<String>().toByteArray())
        }
        every { decrypt(any()) } answers {
            val value = firstArg<String>()
            when {
                !present -> null
                !value.startsWith("enc:") -> null
                else -> String(Base64.getDecoder().decode(value.removePrefix("enc:")))
            }
        }
        every { isKeyPresent() } returns present
    }

    @Test
    fun absent_vault_opens_onboarding() = runTest {
        assertEquals(VaultUnlockGateState.Onboarding, gate().settled())
    }

    @Test
    fun readable_vault_requires_the_passphrase() = runTest {
        val storage = healthyStorage()
        storage.addVaultKey(Account(createdAt = 1, zenTenKey = "encrypted-vault-key"))

        assertEquals(VaultUnlockGateState.PassphraseRequired(), gate(storage = storage).settled())
    }

    /**
     * The defect this work fixes. An unreadable vault must never be reported as onboarding,
     * because onboarding combined with a durable `setupComplete` flag is exactly what produced an
     * empty account list with no explanation. It must not be reported as `PassphraseRequired`
     * either, since no passphrase can open a record whose wrapping key is gone.
     */
    @Test
    fun vault_with_a_missing_keystore_key_reports_unreadable_not_onboarding() = runTest {
        healthyStorage().addVaultKey(Account(createdAt = 1, zenTenKey = "encrypted-vault-key"))

        // Same ciphertext, but the wrap key is now absent: the migration state.
        val stranded = SecureStorageService(context, cryptoWithKey(present = false))

        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing),
            gate(storage = stranded).settled(),
        )
    }

    @Test
    fun corrupt_vault_reports_unreadable_with_its_own_reason() = runTest {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()

        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.PayloadInvalid),
            gate().settled(),
        )
    }

    /**
     * An unlocked session must not mask an unreadable vault. Reporting `Unlocked` would let the
     * user into an app whose every screen then fails to find its records.
     */
    @Test
    fun unreadable_vault_wins_over_an_unlocked_session() = runTest {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vault_key", "not-a-valid-payload")
            .commit()

        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.PayloadInvalid),
            gate(unlocked = true).settled(),
        )
    }

    /**
     * After the stranded vault is set aside, the gate must fall back to onboarding so the user can
     * reach **Recover VaultKey**. This is the post-recovery half of the fix, and it is what proves
     * the user is no longer trapped on the recovery screen.
     */
    @Test
    fun setting_the_stranded_vault_aside_returns_the_gate_to_onboarding() = runTest {
        healthyStorage().addVaultKey(Account(createdAt = 1, zenTenKey = "encrypted-vault-key"))
        val stranded = SecureStorageService(context, cryptoWithKey(present = false))
        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing),
            gate(storage = stranded).settled(),
        )

        stranded.quarantineUnreadableRecords()

        assertEquals(VaultUnlockGateState.Onboarding, gate(storage = stranded).settled())
    }

    /**
     * A partial transfer: account ciphertext arrives, the VaultKey record does not.
     *
     * The gate would otherwise see an absent VaultKey and report a fresh install, sending the user
     * to onboarding while their account records sit unreadable on disk with nothing to explain them.
     */
    @Test
    fun unreadable_account_records_without_a_vault_key_report_unreadable_not_onboarding() = runTest {
        val storage = healthyStorage()
        storage.addAccount(Account(createdAt = 10, zenTenKey = "encrypted-account-key"))

        val stranded = SecureStorageService(context, cryptoWithKey(present = false))

        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing),
            gate(storage = stranded).settled(),
        )
    }

    /**
     * Setting the partial case aside must reach onboarding too, and must not leave the account
     * records counted as unreadable afterwards.
     */
    @Test
    fun setting_aside_a_partial_transfer_returns_the_gate_to_onboarding() = runTest {
        healthyStorage().addAccount(Account(createdAt = 10, zenTenKey = "encrypted-account-key"))
        val stranded = SecureStorageService(context, cryptoWithKey(present = false))
        assertEquals(
            VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing),
            gate(storage = stranded).settled(),
        )

        stranded.quarantineUnreadableRecords()

        assertEquals(VaultUnlockGateState.Onboarding, gate(storage = stranded).settled())
    }

    /**
     * The transition observed on a gate that is already mounted.
     *
     * This is what the screen actually experiences: the user taps the recovery action while the
     * Activity lives, so the gate must leave `VaultUnreadable` without being recreated. It fails if
     * unreadable-record detection is read once instead of observed.
     */
    @Test
    fun setting_aside_a_mounted_gate_leaves_the_recovery_screen_without_recreating_it() = runTest {
        healthyStorage().addVaultKey(Account(createdAt = 1, zenTenKey = "encrypted-vault-key"))
        val stranded = SecureStorageService(context, cryptoWithKey(present = false))
        val mountedGate = gate(storage = stranded)
        mountedGate.awaitState(VaultUnlockGateState.VaultUnreadable(UnreadableReason.KeystoreKeyMissing))

        stranded.quarantineUnreadableRecords()

        mountedGate.awaitState(VaultUnlockGateState.Onboarding)
    }

    /**
     * A readable vault must not be stranded by an unrelated broken account record.
     *
     * Blocks correctness in the other direction: unreadable-record state is consulted only when the
     * VaultKey is absent, so a damaged account cannot lock the user out of a working vault.
     */
    @Test
    fun a_readable_vault_with_a_corrupt_account_still_asks_for_the_passphrase() = runTest {
        val storage = healthyStorage()
        storage.addVaultKey(Account(createdAt = 1, zenTenKey = "encrypted-vault-key"))
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("accounts_20", "not-a-valid-payload")
            .commit()

        assertEquals(VaultUnlockGateState.PassphraseRequired(), gate(storage = storage).settled())
    }

    private companion object {
        // Mirrors SecureStorageService.FILE_NAME, which is private to the implementation.
        const val FILE_NAME = "vault_secure_accounts_v2"
    }
}
