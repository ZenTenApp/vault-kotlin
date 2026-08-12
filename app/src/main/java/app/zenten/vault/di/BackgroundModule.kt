package app.zenten.vault.di

import app.zenten.vault.background.WorkManagerBackupWorkScheduler
import app.zenten.vault.domain.BackupWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundModule {
    @Binds @Singleton
    abstract fun backupWorkScheduler(implementation: WorkManagerBackupWorkScheduler): BackupWorkScheduler
}
