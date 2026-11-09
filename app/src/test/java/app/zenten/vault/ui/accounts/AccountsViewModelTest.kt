package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
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
    fun shows_the_account_name_stored_with_the_account() = runTest(dispatcher) {
        val account = Account(10L, "key", accountName = "Cached")
        val accountService = mockk<AccountService>()
        val accounts = MutableStateFlow(listOf(account))
        every { accountService.accountUpdates } returns accounts
        every { accountService.getAccounts() } returns listOf(account)

        val viewModel = AccountsViewModel(accountService)
        advanceUntilIdle()
        assertEquals("Cached", viewModel.state.value.accounts.single().accountName)
    }

    @Test
    fun displays_accounts_restored_after_the_list_is_already_visible() = runTest(dispatcher) {
        val restored = Account(20L, "restored")
        val accountService = mockk<AccountService>()
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        every { accountService.accountUpdates } returns accounts
        every { accountService.getAccounts() } returns emptyList()

        val viewModel = AccountsViewModel(accountService)
        advanceUntilIdle()
        accounts.value = listOf(restored)
        advanceUntilIdle()

        assertEquals(listOf(restored), viewModel.state.value.accounts)
    }
}
