package app.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import app.zenten.vault.data.model.Account
import app.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AccountDetailViewModel @Inject constructor(private val accountService: AccountService) : ViewModel() {
    fun account(id: Long): Account? = accountService.getAccount(id)
}
