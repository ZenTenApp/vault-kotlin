package com.zenten.vault.di

import com.zenten.vault.data.kv.AppStorageService
import com.zenten.vault.data.secure.SecureStorageService
import com.zenten.vault.domain.AccountService
import com.zenten.vault.domain.BackupService
import com.zenten.vault.domain.RelayBackupRetryService
import com.zenten.vault.domain.RelayBackupRetryScheduler
import com.zenten.vault.domain.StartupRetryScheduler
import com.zenten.vault.domain.SetupService
import com.zenten.vault.network.nostr.NostrRelayGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun backupService(gateway: NostrRelayGateway, clock: Clock) = BackupService(gateway, clock)

    @Provides @Singleton
    fun retryService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupService: BackupService,
    ) = RelayBackupRetryService(secureStorage, appStorage, backupService)

    @Provides @Singleton
    fun startupRetryScheduler(
        appStorage: AppStorageService,
        retryScheduler: RelayBackupRetryScheduler,
    ) = StartupRetryScheduler(appStorage, retryScheduler)

    @Provides @Singleton
    fun setupService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupService: BackupService,
        retryScheduler: RelayBackupRetryScheduler,
    ) = SetupService(secureStorage, appStorage, backupService, retryScheduler)

    @Provides @Singleton
    fun accountService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        setupService: SetupService,
    ) = AccountService(secureStorage, appStorage, setupService)
}
