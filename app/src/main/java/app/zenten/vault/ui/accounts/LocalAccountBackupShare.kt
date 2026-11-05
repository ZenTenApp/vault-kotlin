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

    /**
     * Builds the self-describing text saved during mandatory associated-account export.
     *
     * The npub line is deliberately omitted so the exported backup does not link the account to
     * a Nostr identity. An account restored without a label exports an empty display-name line
     * rather than any identifier.
     */
    internal fun accountExportText(account: Account): String =
        selfDescribingExportText(account, account.accountName?.trim().orEmpty(), includeNpub = false)

    /**
     * Builds the same self-describing export for the Vault Key. The Vault Key has no
     * user-set display name, so the fixed label "VaultKey" is written instead. The Vault Key's
     * npub stays in its export because that key pair is the Vault Key's own identity.
     */
    internal fun vaultKeyExportText(account: Account): String =
        selfDescribingExportText(account, "VaultKey", includeNpub = true)

    /**
     * Builds the clipboard-only export: the passphrase-encrypted OpenSSH key wrapped in PEM
     * markers, and nothing else. The clipboard form deliberately omits the account metadata
     * (creation ID, display name, creation date) that the notes export appends, because a raw
     * key is what a key-import field or another SSH tool expects to receive.
     */
    internal fun accountKeyPemText(account: Account): String = pemBlock(account)

    private fun pemBlock(account: Account): String = listOf(
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        SshKeyCodec.normalizeToSingleLineBase64(account.zenTenKey),
        "-----END OPENSSH PRIVATE KEY-----",
    ).joinToString("\n")

    private fun selfDescribingExportText(
        account: Account,
        displayName: String,
        includeNpub: Boolean,
    ): String = listOfNotNull(
        pemBlock(account),
        // Associated-account exports leave this out so the text never carries their npub.
        formatNpub(account.nostr.pubkey).takeIf { includeNpub },
        account.createdAt.toString(),
        displayName,
        "Created on ${formatAccountCreatedAt(account.createdAt)}",
    ).joinToString("\n")

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
