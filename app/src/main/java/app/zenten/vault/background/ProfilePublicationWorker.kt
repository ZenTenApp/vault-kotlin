package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zenten.vault.domain.AccountService
import app.zenten.vault.domain.ProfilePublicationScheduler
import app.zenten.vault.domain.ProfileService
import app.zenten.vault.ui.availability.RemoteOperationPrecondition
import app.zenten.vault.ui.availability.RemoteOperationReadiness
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@HiltWorker
class ProfilePublicationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val accountService: AccountService,
    private val profileService: ProfileService,
    private val remoteOperationPrecondition: RemoteOperationPrecondition,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            when (remoteOperationPrecondition.check()) {
                RemoteOperationReadiness.READY,
                RemoteOperationReadiness.TIME_UNVERIFIABLE -> Unit
                RemoteOperationReadiness.NO_VALIDATED_INTERNET,
                RemoteOperationReadiness.DEVICE_TIME_INCORRECT -> return Result.retry()
            }
            val accountId = inputData.getLong(ACCOUNT_ID, NO_ACCOUNT_ID)
            if (accountId == NO_ACCOUNT_ID) return Result.failure()
            val account = accountService.getAccount(accountId) ?: return Result.success()
            profileService.publishAssociatedAccountProfile(account)
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
        private const val NO_ACCOUNT_ID = Long.MIN_VALUE
    }
}

@Singleton
class WorkManagerProfilePublicationScheduler @Inject constructor(
    private val workManager: WorkManager,
) : ProfilePublicationScheduler {
    override fun scheduleProfilePublication(accountId: Long) {
        val request = OneTimeWorkRequestBuilder<ProfilePublicationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putLong(ProfilePublicationWorker.ACCOUNT_ID, accountId).build())
            .build()
        workManager.enqueueUniqueWork(
            "$PROFILE_PUBLICATION_PREFIX$accountId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        const val PROFILE_PUBLICATION_PREFIX = "profile-publication-"
    }
}
