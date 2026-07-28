package com.zenten.vault.domain

import com.zenten.vault.data.kv.AppStorageService
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.secure.SecureStorageService

/** First-run state transition: master persistence is rolled back unless its relay backup succeeds. */
class SetupService(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val backupService: BackupService,
) {
    suspend fun performSetup(master: Account): Result<Unit> = runCatching {
        require(master.nostr != null) { "Master account must have a Nostr identity" }
        secureStorage.addMasterAccount(master)
        val result = backupService.backupMaster(master)
        check(result.acknowledged) { "Master relay backup was not acknowledged" }
        appStorage.setSetupComplete()
        Unit
    }.onFailure {
        secureStorage.removeMasterAccount()
        appStorage.clearSetupComplete()
    }

    suspend fun addAccount(master: Account, account: Account): Result<Unit> = runCatching {
        secureStorage.addAccount(account)
        // Sub-account backups are intentionally non-fatal; Phase 5 adds durable retry scheduling.
        backupService.backupSubAccount(master, account)
    }
}
