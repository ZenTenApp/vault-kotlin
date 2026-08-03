package app.zenten.vault.domain

import app.zenten.vault.crypto.Nip44
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway

/** Restores only sub-account events. Master recovery remains user SSH-key onboarding. */
class RecoveryService(
    private val relayGateway: NostrRelayGateway,
    private val secureStorage: SecureStorageService,
    private val braveSyncBackupService: BraveSyncBackupService? = null,
) {
    suspend fun discoverSubAccounts(master: Account): RecoveryResult {
        val nostr = requireNotNull(master.nostr) { "Master Nostr identity is required for recovery" }
        val events = relayGateway.fetchByAuthorAndKind(nostr.pubkey, BackupService.SUB_ACCOUNT_BACKUP_KIND)
        val recovered = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        val knownAccountIds = secureStorage.getAccounts().mapTo(mutableSetOf()) { it.createdAt }
        events.forEach { event ->
            val account = decodeSubAccount(event, master)
            if (account == null) skipped += event.id
            else if (knownAccountIds.add(account.createdAt)) {
                secureStorage.addAccount(account)
                recovered += account
            }
        }
        val brave = braveSyncBackupService?.let { service -> runCatching { service.discoverSubAccounts(master) }.getOrNull() }
        brave?.accounts.orEmpty().forEach { account ->
            if (knownAccountIds.add(account.createdAt)) {
                secureStorage.addAccount(account)
                recovered += account
            }
        }
        skipped += brave?.skippedEntryIds.orEmpty()
        return RecoveryResult(recovered, skipped, events.size, brave?.discoveredEntryCount ?: 0, brave?.decryptionFailures ?: 0)
    }

    fun decodeSubAccount(event: NostrEvent, master: Account): Account? = runCatching {
        val nostr = requireNotNull(master.nostr)
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.SUB_ACCOUNT_BACKUP_KIND && event.pubkey == nostr.pubkey)
        val dTag = event.tags.single { it.firstOrNull() == "d" }.getOrNull(1)
        require(!dTag.isNullOrBlank() && dTag.all(Char::isDigit))
        val createdAt = dTag.toLong()
        val sshPrivateKey = Nip44.decryptSelf(event.content, nostr.privateKey(), nostr.publicKey())
        require(sshPrivateKey.isNotBlank())
        val passphrase = requireNotNull(master.zenTenPass) { "Master account passphrase is unavailable" }
        val parsed = app.zenten.vault.crypto.SshKeyCodec.parse(sshPrivateKey, passphrase.toCharArray())
        Account(
            createdAt = createdAt,
            zenTenKey = app.zenten.vault.crypto.SshKeyCodec.normalizeToSingleLineBase64(sshPrivateKey),
            zenTenPass = passphrase,
            pubkey = app.zenten.vault.crypto.SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
        )
    }.getOrNull()

    data class RecoveryResult(
        val recovered: List<Account>,
        val skippedEventIds: List<String>,
        val discoveredEventCount: Int,
        val discoveredBraveEntryCount: Int = 0,
        val braveDecryptionFailures: Int = 0,
    )
}
