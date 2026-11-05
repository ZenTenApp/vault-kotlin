package app.zenten.vault.domain

import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway

/** Restores only associated account events. VaultKey recovery remains user SSH-key onboarding. */
class RecoveryService(
    private val relayGateway: NostrRelayGateway,
    private val secureStorage: SecureStorageService,
    private val braveSyncBackupService: BraveSyncBackupService? = null,
) {
    suspend fun discoverAssociatedAccounts(vaultKey: Account, passphrase: CharArray): RecoveryResult {
        val nostr = vaultKey.nostr
        val events = relayGateway.fetchAuthenticatedByAuthorAndKind(
            nostr.pubkey,
            BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
            nostr.privateKey(),
        )
        val recovered = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        val knownAccountIds = secureStorage.getAccounts().mapTo(mutableSetOf()) { it.createdAt }
        events.forEach { event ->
            val account = decodeAssociatedAccount(event, vaultKey, passphrase)
            if (account == null) skipped += event.id
            else if (knownAccountIds.add(account.createdAt)) {
                secureStorage.addAccount(account)
                recovered += account
            }
        }
        val brave = braveSyncBackupService?.let { service -> runCatching { service.discoverAssociatedAccounts(vaultKey, passphrase) }.getOrNull() }
        brave?.accounts.orEmpty().forEach { account ->
            if (knownAccountIds.add(account.createdAt)) {
                secureStorage.addAccount(account)
                recovered += account
            }
        }
        skipped += brave?.skippedEntryIds.orEmpty()
        return RecoveryResult(recovered, skipped, events.size, brave?.discoveredEntryCount ?: 0, brave?.decryptionFailures ?: 0)
    }

    fun decodeAssociatedAccount(event: NostrEvent, vaultKey: Account, passphrase: CharArray): Account? = runCatching {
        val nostr = vaultKey.nostr
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND && event.pubkey == nostr.pubkey)
        val dTag = event.tags.single { it.firstOrNull() == "d" }.getOrNull(1)
        require(!dTag.isNullOrBlank() && dTag.all(Char::isDigit))
        val createdAt = dTag.toLong()
        val sshPrivateKey = Nip44.decryptSelf(event.content, nostr.privateKey(), nostr.publicKey())
        require(sshPrivateKey.isNotBlank())
        Account(
            createdAt = createdAt,
            zenTenKey = app.zenten.vault.crypto.SshKeyCodec.normalizeToSingleLineBase64(sshPrivateKey),
            nostr = NostrKeyDerivation.derive(
                Seedify.fromSshKey(sshPrivateKey, passphrase),
            ).toPersistedKeyPair(),
            // A missing or malformed label is not a reason to discard an otherwise valid key:
            // the account still restores and its card simply shows no name.
            accountName = event.accountLabel(),
        )
    }.getOrNull()

    /**
     * Reads the optional `label` tag on an associated-account event.
     *
     * The first label is used when an event carries more than one, and blank values are treated
     * as absent so the restored account keeps an empty name.
     */
    private fun NostrEvent.accountLabel(): String? =
        tags.firstOrNull { it.firstOrNull() == BackupService.ACCOUNT_LABEL_TAG }
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    data class RecoveryResult(
        val recovered: List<Account>,
        val skippedEventIds: List<String>,
        val discoveredEventCount: Int,
        val discoveredBraveEntryCount: Int = 0,
        val braveDecryptionFailures: Int = 0,
    )
}
