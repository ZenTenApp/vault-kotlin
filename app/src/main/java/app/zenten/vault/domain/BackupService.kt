package app.zenten.vault.domain

import app.zenten.vault.crypto.Nip44
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import java.time.Clock

/** Builds the private backup envelope and publishes it under the Phase-4 event contract. */
class BackupService(
    private val relayGateway: NostrRelayGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun backupMaster(master: Account): PublishResult =
        relayGateway.publish(buildBackupEvent(master, master, MASTER_D_TAG, MASTER_BACKUP_KIND))

    suspend fun backupSubAccount(master: Account, account: Account): PublishResult =
        relayGateway.publish(buildBackupEvent(master, account, account.createdAt.toString(), SUB_ACCOUNT_BACKUP_KIND))

    fun buildBackupEvent(master: Account, account: Account, dTag: String, kind: Int): NostrEvent {
        val nostr = requireNotNull(master.nostr) { "Master Nostr key pair is required to back up accounts" }
        validateContract(account, dTag, kind)
        if (kind == SUB_ACCOUNT_BACKUP_KIND) requireEncryptedAssociatedKey(account)
        // The NIP-44 plaintext is exactly the single-line base64 OpenSSH private key.
        val encrypted = Nip44.encryptSelf(account.zenTenKey, nostr.privateKey(), nostr.publicKey())
        return NostrEventCodec.sign(
            privateKey = nostr.privateKey(),
            publicKey = nostr.publicKey(),
            kind = kind,
            tags = listOf(listOf("d", dTag)),
            content = encrypted,
            createdAtSeconds = clock.instant().epochSecond,
        )
    }

    private fun requireEncryptedAssociatedKey(account: Account) {
        val passphrase = requireNotNull(account.zenTenPass) { "Associated-account passphrase is unavailable" }
        require(passphrase.isNotBlank()) { "Associated-account passphrase is required" }
        require(app.zenten.vault.crypto.SshKeyCodec.parse(account.zenTenKey, passphrase.toCharArray()).encrypted) {
            "Associated-account SSH key must be passphrase-encrypted"
        }
    }

    private fun validateContract(account: Account, dTag: String, kind: Int) = when (kind) {
        MASTER_BACKUP_KIND -> require(dTag == MASTER_D_TAG) { "Master backup d tag must be $MASTER_D_TAG" }
        SUB_ACCOUNT_BACKUP_KIND -> require(dTag == account.createdAt.toString()) { "Sub-account d tag must be its createdAt timestamp" }
        else -> throw IllegalArgumentException("Unsupported backup kind: $kind")
    }

    companion object {
        const val MASTER_BACKUP_KIND = 37_801
        const val SUB_ACCOUNT_BACKUP_KIND = 37_802
        const val MASTER_D_TAG = "masterkey"
    }
}

internal fun NostrKeyPair.privateKey(): ByteArray = privkey.hexToBytes()
internal fun NostrKeyPair.publicKey(): ByteArray = pubkey.hexToBytes()
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it.digitToIntOrNull(16) != null }) { "Invalid hexadecimal Nostr key" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
