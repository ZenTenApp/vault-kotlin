package app.zenten.vault.ui.keys

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Key

object LocalKeyBackupShare {
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
     * Builds the self-describing text saved during mandatory associated-key export.
     *
     * No Nostr identity is written, so the exported backup does not link the key to a Nostr
     * identity. A key restored without a label exports an empty display-name line rather
     * than any identifier.
     */
    internal fun keyExportText(key: Key): String =
        selfDescribingExportText(key, key.keyName?.trim().orEmpty())

    /**
     * Builds the same self-describing export for the Vault Key. The Vault Key has no
     * user-set display name, so the fixed label "VaultKey" is written instead.
     *
     * [passphrase] is not used to build the text. It is checked by decrypting the key, so the
     * export is only produced for the correct passphrase, and a wrong one throws. The Vault Key's
     * npub is deliberately not written, the same as for associated keys.
     */
    internal fun vaultKeyExportText(key: Key, passphrase: CharArray): String {
        // Decryption is the passphrase gate: it throws on a wrong passphrase before any text exists.
        SshKeyCodec.parse(key.zenTenKey, passphrase)
        return selfDescribingExportText(key, "VaultKey")
    }

    /**
     * Wraps the passphrase-encrypted key in PEM markers as one Base64 body line. Both the
     * self-describing export and the clipboard/QR payloads build on this block.
     */
    private fun pemBlock(key: Key): String = listOf(
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        SshKeyCodec.normalizeToSingleLineBase64(key.zenTenKey),
        "-----END OPENSSH PRIVATE KEY-----",
    ).joinToString("\n")

    /**
     * Builds the metadata lines after the PEM block. Each line has a fixed prefix so it is
     * readable in a note without context:
     * - `unixTimestamp:` is the raw key ID (creation time in microseconds), for exact matching.
     * - `label:` is the display name.
     * - `createdAt:` is the human-readable creation time, shown in the key-detail format.
     */
    private fun selfDescribingExportText(key: Key, displayName: String): String = listOf(
        pemBlock(key),
        "unixTimestamp:${key.createdAt}",
        "label:$displayName",
        "createdAt:${formatKeyCreatedAt(key.createdAt)}",
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
