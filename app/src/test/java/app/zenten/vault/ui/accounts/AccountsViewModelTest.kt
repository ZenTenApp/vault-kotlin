package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.domain.AccountProfile
import app.zenten.vault.domain.AccountProfileRepository
import app.zenten.vault.domain.AccountService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun displays_cached_profiles_and_retries_network_on_refresh() = runTest(dispatcher) {
        val account = Account(10L, "key", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))
        val accountService = mockk<AccountService>()
        every { accountService.getAccounts() } returns listOf(account)
        val repository = RecordingProfileRepository(mapOf(10L to AccountProfile("Cached", 100L)))

        val viewModel = AccountsViewModel(accountService, repository)
        advanceUntilIdle()
        assertEquals("Cached", viewModel.state.value.profiles[10L]?.displayName)
        assertEquals(1, repository.refreshCount)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, repository.refreshCount)
    }

    private class RecordingProfileRepository(initial: Map<Long, AccountProfile>) : AccountProfileRepository {
        override val profiles = MutableStateFlow(initial)
        var refreshCount = 0

        override suspend fun loadCachedProfiles() = Unit
        override suspend fun refresh(accounts: List<Account>, force: Boolean) { refreshCount++ }
        override suspend fun refreshIfNeeded(account: Account) = Unit
    }
}
