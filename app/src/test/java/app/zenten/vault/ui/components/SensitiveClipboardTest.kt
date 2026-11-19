package app.zenten.vault.ui.components

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the sensitive-clip flag and that the delayed clear removes only Vault's own export.
 * The WorkManager scheduling in [SensitiveClipboard.copy] is not exercised here; the worker only
 * forwards to [SensitiveClipboard.clearIfOwned], which these tests cover.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application keeps Robolectric from running VaultApplication's Hilt graph.
@Config(sdk = [34], application = Application::class)
class SensitiveClipboardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun buildClip_marksClipSensitiveAndTagsOwner() {
        val clip = SensitiveClipboard.buildClip("Encrypted key", "export", "token-1")

        val extras = clip.description.extras
        assertTrue(extras.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals("token-1", extras.getString(SensitiveClipboard.EXTRA_OWNER_TOKEN))
        assertEquals("export", clip.getItemAt(0).text.toString())
    }

    @Test
    fun clearIfOwned_clearsOwnExport() {
        clipboard.setPrimaryClip(SensitiveClipboard.buildClip("Encrypted key", "export", "token-1"))

        assertTrue(SensitiveClipboard.clearIfOwned(context, "token-1"))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun clearIfOwned_keepsTextCopiedAfterTheExport() {
        clipboard.setPrimaryClip(SensitiveClipboard.buildClip("Encrypted key", "export", "token-1"))
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "something else"))

        assertFalse(SensitiveClipboard.clearIfOwned(context, "token-1"))
        assertEquals("something else", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun isVisiblyReplaced_onlyForAReadableForeignClip() {
        val own = SensitiveClipboard.buildClip("Encrypted key", "export", "token-1").description
        val other = ClipData.newPlainText("note", "something else").description

        // Unreadable (e.g. the Android 13+ copy overlay holds focus) must not cancel the clear.
        assertFalse(SensitiveClipboard.isVisiblyReplaced(null, "token-1"))
        assertFalse(SensitiveClipboard.isVisiblyReplaced(own, "token-1"))
        assertTrue(SensitiveClipboard.isVisiblyReplaced(other, "token-1"))
    }

    @Test
    fun clearIfOwned_keepsANewerExport() {
        clipboard.setPrimaryClip(SensitiveClipboard.buildClip("Encrypted key", "newer", "token-2"))

        // The older export's pending clear must not remove the newer one.
        assertFalse(SensitiveClipboard.clearIfOwned(context, "token-1"))
        assertTrue(clipboard.hasPrimaryClip())
    }
}
