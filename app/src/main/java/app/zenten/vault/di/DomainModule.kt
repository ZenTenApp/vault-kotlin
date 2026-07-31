package app.zenten.vault.di

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.AccountBackupCoordinator
import app.zenten.vault.domain.AccountBackupTarget
import app.zenten.vault.domain.BackupService
import app.zenten.vault.domain.BackupSyncService
import app.zenten.vault.domain.NostrAccountBackupTarget
import app.zenten.vault.domain.BraveSyncBackupService
import app.zenten.vault.domain.RecoveryService
import app.zenten.vault.domain.RelayBackupRetryService
import app.zenten.vault.domain.RelayBackupRetryScheduler
import app.zenten.vault.domain.StartupRetryScheduler
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
    fun braveSyncBackupService() = BraveSyncBackupService()

    @Provides @Singleton
    fun backupCoordinator(
        backupService: BackupService,
        braveSyncBackupService: BraveSyncBackupService,
    ): AccountBackupCoordinator = AccountBackupCoordinator(
        setOf(NostrAccountBackupTarget(backupService), braveSyncBackupService),
    )

    @Provides @Singleton
    fun recoveryService(
        gateway: NostrRelayGateway,
        secureStorage: SecureStorageService,
        braveSyncBackupService: BraveSyncBackupService,
    ) = RecoveryService(gateway, secureStorage, braveSyncBackupService)

    @Provides @Singleton
    fun backupSyncService(
        gateway: NostrRelayGateway,
        backupService: BackupService,
        braveSyncBackupService: BraveSyncBackupService,
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        clock: Clock,
    ) = BackupSyncService(gateway, backupService, braveSyncBackupService, secureStorage, appStorage, clock)

    @Provides @Singleton
    fun retryService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupCoordinator: AccountBackupCoordinator,
    ) = RelayBackupRetryService(secureStorage, appStorage, backupCoordinator)

    @Provides @Singleton
    fun startupRetryScheduler(
        appStorage: AppStorageService,
        retryScheduler: RelayBackupRetryScheduler,
    ) = StartupRetryScheduler(appStorage, retryScheduler)

    @Provides @Singleton
    fun setupService(
        secureStorage: SecureStorageService,
        appStorage: AppStorageService,
        backupCoordinator: AccountBackupCoordinator,
        recoveryService: RecoveryService,
        retryScheduler: RelayBackupRetryScheduler,
    ) = SetupService(secureStorage, appStorage, backupCoordinator, recoveryService, retryScheduler)

    @Provides @Singleton
    fun accountService(
        secureStorage: SecureStorageService,
        setupService: SetupService,
    ) = AccountService(secureStorage, setupService)
}
