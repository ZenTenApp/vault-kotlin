package app.zenten.vault.di

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountConsolidationEngine
import app.zenten.vault.domain.AccountProfileRepository
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.BackupService
import app.zenten.vault.domain.BackupSourceFactory
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupWorkScheduler
import app.zenten.vault.domain.DefaultBackupSourceFactory
import app.zenten.vault.domain.DefaultAccountProfileRepository
import app.zenten.vault.domain.ProfileService
import app.zenten.vault.domain.SetupService
import app.zenten.vault.network.nostr.NostrRelayGateway
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
    fun profileService(gateway: NostrRelayGateway, clock: Clock) = ProfileService(gateway, clock)

    @Provides @Singleton
    fun profileRepository(implementation: DefaultAccountProfileRepository): AccountProfileRepository = implementation

    @Provides @Singleton
    fun sourceRepository(appStorage: AppStorageService) = BackupSourceRepository(appStorage)

    @Provides @Singleton
    fun sourceFactory(gateway: NostrRelayGateway, backupService: BackupService): BackupSourceFactory =
        DefaultBackupSourceFactory(gateway, backupService)

    @Provides @Singleton
    fun consolidationEngine(
        repository: BackupSourceRepository,
        factory: BackupSourceFactory,
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        clock: Clock,
    ) = AccountConsolidationEngine(repository, factory, secureStorage, appStorage, clock)

    @Provides @Singleton
    fun setupService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        engine: AccountConsolidationEngine,
        scheduler: BackupWorkScheduler,
    ) = SetupService(secureStorage, appStorage, engine, scheduler)

    @Provides @Singleton
    fun accountService(secureStorage: SecureStorageService, setupService: SetupService) =
        AccountService(secureStorage, setupService)
}
