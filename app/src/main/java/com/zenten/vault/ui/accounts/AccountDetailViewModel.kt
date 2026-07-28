package com.zenten.vault.ui.accounts

import androidx.lifecycle.ViewModel
import com.zenten.vault.data.model.Account
import com.zenten.vault.domain.AccountService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AccountDetailViewModel @Inject constructor(private val accountService: AccountService) : ViewModel() {
    fun account(id: Long): Account? = accountService.getAccount(id)
    fun delete(id: Long) = accountService.deleteSubAccount(id)
}
