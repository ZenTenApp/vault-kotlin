package com.zenten.vault.domain

import com.zenten.vault.crypto.Nip44
import com.zenten.vault.data.model.Account
import com.zenten.vault.data.secure.SecureStorageService
import com.zenten.vault.network.nostr.NostrEvent
import com.zenten.vault.network.nostr.NostrEventCodec
import com.zenten.vault.network.nostr.NostrRelayGateway

/** Restores only sub-account events. Master recovery remains user SSH-key onboarding. */
class RecoveryService(
    private val relayGateway: NostrRelayGateway,
    private val secureStorage: SecureStorageService,
) {
    suspend fun discoverSubAccounts(master: Account): RecoveryResult {
        val nostr = requireNotNull(master.nostr) { "Master Nostr identity is required for recovery" }
        val events = relayGateway.fetchByAuthorAndKind(nostr.pubkey, BackupService.SUB_ACCOUNT_BACKUP_KIND)
        val recovered = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        events.forEach { event ->
            val account = decodeSubAccount(event, master)
            if (account == null) skipped += event.id else {
                secureStorage.addAccount(account)
                recovered += account
            }
        }
        return RecoveryResult(recovered, skipped)
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
        Account(createdAt = createdAt, zenTenKey = sshPrivateKey)
    }.getOrNull()

    data class RecoveryResult(val recovered: List<Account>, val skippedEventIds: List<String>)
}
