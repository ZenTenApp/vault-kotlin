package app.zenten.vault.ui.accounts

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import app.zenten.vault.crypto.SshKeyCodec

internal object LocalAccountBackupShare {
    private const val MIME_TYPE = "text/plain"
    private val preferredNotePackages = setOf(
        "com.google.android.keep",
        "com.microsoft.office.onenote",
        "com.standardnotes",
        "com.automattic.simplenote",
        "md.obsidian",
        "net.cozic.joplin",
    )

    fun sendIntent(key: String): Intent = Intent(Intent.ACTION_SEND)
        .setType(MIME_TYPE)
        .putExtra(Intent.EXTRA_TEXT, SshKeyCodec.formatAsPem(key))

    fun preferredTargetIntents(resolvedActivities: List<ResolveInfo>, key: String): Array<Intent> =
        preferredTargets(resolvedActivities.map { PreferredTarget(it.activityInfo.packageName, it.activityInfo.name) })
            .map { target ->
                sendIntent(key).setComponent(ComponentName(target.packageName, target.activityName))
            }
            .toTypedArray()

    internal fun preferredTargets(availableTargets: List<PreferredTarget>): List<PreferredTarget> =
        availableTargets
            .filter { it.packageName in preferredNotePackages }
            .distinctBy { it.packageName }

    internal data class PreferredTarget(val packageName: String, val activityName: String)
}
