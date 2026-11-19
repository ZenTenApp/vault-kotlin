package app.zenten.vault.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.zenten.vault.background.ClipboardClearWorker
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Copies sensitive text (an encrypted key export) to the clipboard and clears it again shortly
 * after, so the export does not stay readable by other apps indefinitely.
 *
 * How the clear is guaranteed:
 * - A WorkManager job clears the clipboard after [CLEAR_DELAY_SECONDS]. WorkManager persists the
 *   job, so it still runs if Android kills the app process while the user is in another app.
 *   Only a random, non-secret ownership token is stored in the job's input, never the export.
 * - Each copy is tagged with that token in its [ClipDescription] extras. Before clearing, the
 *   clipboard is checked for the token, so text the user copied afterwards is not wiped.
 *   Android 10+ hides the clipboard from background apps, though, so a job that runs while Vault
 *   is in the background cannot see the clip and clears unconditionally. To reduce that, a
 *   clip-changed listener cancels the job when it can see that something else replaced the
 *   export (in practice, while Vault still has window focus).
 *
 * Each clip is also flagged as sensitive, which on Android 13+ hides its preview in the system
 * copy confirmation and tells keyboards not to suggest it or save it in their clipboard history.
 */
object SensitiveClipboard {
    /** Long enough to switch apps and paste, short enough to keep the exposure window small. */
    const val CLEAR_DELAY_SECONDS = 60L

    /** Unique work name, so a newer copy replaces the older pending clear instead of adding one. */
    private const val CLEAR_WORK_NAME = "clear-sensitive-clipboard"

    /**
     * The value of `ClipDescription.EXTRA_IS_SENSITIVE`. That constant was added in API 33, but
     * putting the same literal key in the extras is harmless on older versions, which ignore it.
     */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** App-private extra that marks a clip as one this helper placed, so it can tell it apart. */
    internal const val EXTRA_OWNER_TOKEN = "app.zenten.vault.extra.SENSITIVE_CLIP_TOKEN"

    // At most one export is tracked at a time; a new copy replaces the previous listener.
    // Copies run on the main thread and the worker on a background thread, so access is
    // synchronized on this object.
    private var activeListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * Places [text] on the clipboard, flagged as sensitive, and schedules its removal.
     * Throws if Android rejects the clipboard write, so callers can show an error.
     */
    @Synchronized
    fun copy(context: Context, label: String, text: String) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val token = UUID.randomUUID().toString()
        // Stop watching an earlier export before this copy replaces it, or that listener would
        // see this copy as "something else" and cancel the clear just scheduled below.
        stopWatching(clipboard)
        clipboard.setPrimaryClip(buildClip(label, text, token))

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            CLEAR_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                .setInitialDelay(CLEAR_DELAY_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(ClipboardClearWorker.KEY_TOKEN to token))
                .build(),
        )

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // This also fires (late) for our own copy, often after the Android 13+ "copied"
            // overlay has taken window focus, when Vault can no longer read the clipboard.
            // So cancel only when a different clip is actually visible, never when unreadable.
            val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
            if (isVisiblyReplaced(description, token)) {
                stopWatching(clipboard)
                WorkManager.getInstance(appContext).cancelUniqueWork(CLEAR_WORK_NAME)
            }
        }
        clipboard.addPrimaryClipChangedListener(listener)
        activeListener = listener
    }

    /**
     * Clears the clipboard unless it visibly holds something other than the clip tagged with
     * [token]. Returns whether it cleared. Called by [ClipboardClearWorker].
     */
    fun clearIfOwned(context: Context, token: String): Boolean {
        val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
        // Our own clear changes the clip, so stop listening before it fires the listener.
        stopWatching(clipboard)
        // A null description means the clipboard is empty or, on Android 10+ in the background,
        // unreadable. Either way clearing is the safe choice: it wipes nothing visible to us.
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (isVisiblyReplaced(description, token)) return false
        clear(clipboard)
        return true
    }

    /**
     * Removes the current clipboard entry. [ClipboardManager.clearPrimaryClip] exists only from
     * Android 9 (API 28), so older versions overwrite the entry with empty text instead.
     * Throws if Android rejects the write.
     */
    fun clear(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** Builds the clip with the sensitive flag and the ownership token in its extras. */
    internal fun buildClip(label: String, text: String, token: String): ClipData =
        ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
                putString(EXTRA_OWNER_TOKEN, token)
            }
        }

    /**
     * True only when the clipboard is readable and holds a clip other than the one tagged with
     * [token]. A null [description] (empty, or unreadable without window focus) is not proof the
     * export was replaced, so it returns false and the pending clear stays in place.
     */
    internal fun isVisiblyReplaced(description: ClipDescription?, token: String): Boolean =
        description != null && description.extras?.getString(EXTRA_OWNER_TOKEN) != token

    @Synchronized
    private fun stopWatching(clipboard: ClipboardManager) {
        activeListener?.let(clipboard::removePrimaryClipChangedListener)
        activeListener = null
    }
}
