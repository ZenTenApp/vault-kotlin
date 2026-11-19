package app.zenten.vault.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.zenten.vault.ui.components.SensitiveClipboard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Clears a sensitive clipboard export once its time is up; see [SensitiveClipboard].
 *
 * It has no dependencies, but it is a Hilt worker so the app's `HiltWorkerFactory` creates it
 * the same way as the other workers. The input holds only the clip's random ownership token,
 * never the copied text, because WorkManager stores work input on disk.
 */
@HiltWorker
class ClipboardClearWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        // Writing to the clipboard is allowed from the background on every supported version.
        // A failed write is not retried: the exposure window has already passed, and a retry
        // much later could wipe something the user copied in the meantime.
        runCatching { SensitiveClipboard.clearIfOwned(applicationContext, token) }
        return Result.success()
    }

    companion object {
        const val KEY_TOKEN = "token"
    }
}
