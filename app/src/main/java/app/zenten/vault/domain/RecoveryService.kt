package app.zenten.vault.domain

import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Key
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway

/** Restores only associated key events. VaultKey recovery remains user SSH-key onboarding. */
class RecoveryService(
    private val relayGateway: NostrRelayGateway,
    private val secureStorage: SecureStorageService,
    private val braveSyncBackupService: BraveSyncBackupService? = null,
) {
    suspend fun discoverAssociatedKeys(vaultKey: Key, passphrase: CharArray): RecoveryResult {
        // The Vault Key identity is derived from its seed: decrypting the container requires the
        // passphrase this call already holds, so nothing needs to be stored to find the records.
        val identity = NostrKeyDerivation.fromKeySeed(SshKeyCodec.parse(vaultKey.encryptedKey, passphrase).seed)
        val events = relayGateway.fetchAuthenticatedByAuthorAndKind(
            identity.pubkey,
            BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            identity.privateKey,
        )
        val recovered = mutableListOf<Key>()
        val skipped = mutableListOf<String>()
        val knownKeyIds = secureStorage.getKeys().mapTo(mutableSetOf()) { it.createdAt }
        events.forEach { event ->
            val key = decodeAssociatedKey(event, vaultKey, passphrase)
            if (key == null) skipped += event.id
            else if (knownKeyIds.add(key.createdAt)) {
                secureStorage.addKey(key)
                recovered += key
            }
        }
        val brave = braveSyncBackupService?.let { service -> runCatching { service.discoverAssociatedKeys(vaultKey, passphrase) }.getOrNull() }
        brave?.keys.orEmpty().forEach { key ->
            if (knownKeyIds.add(key.createdAt)) {
                secureStorage.addKey(key)
                recovered += key
            }
        }
        skipped += brave?.skippedEntryIds.orEmpty()
        return RecoveryResult(recovered, skipped, events.size, brave?.discoveredEntryCount ?: 0, brave?.decryptionFailures ?: 0)
    }

    fun decodeAssociatedKey(event: NostrEvent, vaultKey: Key, passphrase: CharArray): Key? = runCatching {
        val identity = NostrKeyDerivation.fromKeySeed(SshKeyCodec.parse(vaultKey.encryptedKey, passphrase).seed)
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.ASSOCIATED_KEY_BACKUP_KIND && event.pubkey == identity.pubkey)
        val dTag = event.tags.single { it.firstOrNull() == "d" }.getOrNull(1)
        require(!dTag.isNullOrBlank() && dTag.all(Char::isDigit))
        val createdAt = dTag.toLong()
        val sshPrivateKey = Nip44.decryptSelf(event.content, identity.privateKey, identity.publicKey)
        // Intake is shared with consolidation so both restore paths reject the same records:
        // this validates the encrypted container (including its bcrypt work factor) and
        // canonicalizes it, throwing for a record that must not be restored.
        keyFromSshKey(
            createdAt = createdAt,
            sshPrivateKey = sshPrivateKey,
            // A missing or malformed label is not a reason to discard an otherwise valid key:
            // the key still restores and its card simply shows no name.
            keyName = event.keyLabel(),
        )
    }.getOrNull()

    /**
     * Reads the optional `label` tag on an associated-key event.
     *
     * The first label is used when an event carries more than one, and blank values are treated
     * as absent so the restored key keeps an empty name.
     */
    private fun NostrEvent.keyLabel(): String? =
        tags.firstOrNull { it.firstOrNull() == BackupService.KEY_LABEL_TAG }
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    data class RecoveryResult(
        val recovered: List<Key>,
        val skippedEventIds: List<String>,
        val discoveredEventCount: Int,
        val discoveredBraveEntryCount: Int = 0,
        val braveDecryptionFailures: Int = 0,
    )
}
