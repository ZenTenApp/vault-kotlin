package app.zenten.vault.ui.accounts

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account

object LocalAccountBackupShare {
    private const val MIME_TYPE = "text/plain"
    private val preferredNotePackages = setOf(
        "com.google.android.keep",
        "com.microsoft.office.onenote",
        "com.standardnotes",
        "com.automattic.simplenote",
        "md.obsidian",
        "net.cozic.joplin",
    )

    private fun sendIntent(key: String): Intent = Intent(Intent.ACTION_SEND)
        .setType(MIME_TYPE)
        .putExtra(Intent.EXTRA_TEXT, key)

    fun availableTargets(packageManager: PackageManager): List<ShareTarget> =
        orderedTargets(
            packageManager.queryIntentActivities(sendIntent(""), PackageManager.MATCH_DEFAULT_ONLY)
                .map { resolveInfo ->
                    ShareTarget(
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                        activityName = resolveInfo.activityInfo.name,
                        resolveInfo = resolveInfo,
                    )
                },
        )

    fun targetedSendIntent(text: String, target: ShareTarget): Intent = sendIntent(text)
        .setComponent(ComponentName(target.packageName, target.activityName))

    /** Builds the self-describing text saved during mandatory associated-account export. */
    internal fun accountExportText(account: Account): String {
        val displayName = account.accountName?.trim().takeUnless { it.isNullOrEmpty() } ?: formatShortNpub(account.nostr.pubkey)
        return selfDescribingExportText(account, displayName)
    }

    /**
     * Builds the same self-describing export for the Vault Key. The Vault Key has no
     * user-set display name, so the fixed label "VaultKey" is written instead.
     */
    internal fun vaultKeyExportText(account: Account): String = selfDescribingExportText(account, "VaultKey")

    private fun selfDescribingExportText(account: Account, displayName: String): String {
        val key = SshKeyCodec.normalizeToSingleLineBase64(account.zenTenKey)
        val npub = formatNpub(account.nostr.pubkey)
        return listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            key,
            "-----END OPENSSH PRIVATE KEY-----",
            npub,
            account.createdAt.toString(),
            displayName,
            "Created on ${formatAccountCreatedAt(account.createdAt)}",
        ).joinToString("\n")
    }

    internal fun <T : Target> orderedTargets(availableTargets: List<T>): List<T> =
        availableTargets
            .distinctBy { it.packageName to it.activityName }
            .sortedWith(
                compareByDescending<T> { it.packageName in preferredNotePackages }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )

    interface Target {
        val label: String
        val packageName: String
        val activityName: String
    }

    data class ShareTarget(
        override val label: String,
        override val packageName: String,
        override val activityName: String,
        val resolveInfo: ResolveInfo,
    ) : Target
}
