package app.zenten.vault.di

import android.content.Context
import androidx.work.WorkManager
import app.zenten.vault.background.WorkManagerBackupWorkScheduler
import app.zenten.vault.domain.BackupWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundModule {
    @Binds @Singleton
    abstract fun backupWorkScheduler(implementation: WorkManagerBackupWorkScheduler): BackupWorkScheduler

    companion object {
        @Provides @Singleton
        fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}
