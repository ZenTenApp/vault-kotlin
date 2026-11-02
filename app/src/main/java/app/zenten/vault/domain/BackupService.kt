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
    suspend fun backupVaultKey(vaultKey: Account): PublishResult {
        val nostr = vaultKey.nostr
        return relayGateway.publishAuthenticated(
            buildBackupEvent(vaultKey, vaultKey, VAULT_KEY_D_TAG, VAULT_KEY_BACKUP_KIND),
            nostr.privateKey(),
        )
    }

    suspend fun backupAssociatedAccount(vaultKey: Account, account: Account): PublishResult {
        val nostr = vaultKey.nostr
        return relayGateway.publishAuthenticated(
            buildBackupEvent(vaultKey, account, account.createdAt.toString(), ASSOCIATED_ACCOUNT_BACKUP_KIND),
            nostr.privateKey(),
        )
    }

    /** Builds the signed backup event; the same vault key signs an optional NIP-42 challenge. */
    fun buildBackupEvent(vaultKey: Account, account: Account, dTag: String, kind: Int): NostrEvent {
        val nostr = vaultKey.nostr
        validateContract(account, dTag, kind)
        if (kind == ASSOCIATED_ACCOUNT_BACKUP_KIND) requireEncryptedAssociatedKey(account)
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
        // Validate the encrypted-container metadata without needing the passphrase: the
        // passphrase is no longer stored, and the key was already validated when it was
        // generated or restored.
        require(
            runCatching { app.zenten.vault.crypto.SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(account.zenTenKey) }.isSuccess,
        ) { "Associated-account SSH key must be passphrase-encrypted" }
    }

    private fun validateContract(account: Account, dTag: String, kind: Int) = when (kind) {
        VAULT_KEY_BACKUP_KIND -> require(dTag == VAULT_KEY_D_TAG) {
            "VaultKey backup d tag must be $VAULT_KEY_D_TAG"
        }
        ASSOCIATED_ACCOUNT_BACKUP_KIND -> require(dTag == account.createdAt.toString()) { "Associated account d tag must be its createdAt timestamp" }
        else -> throw IllegalArgumentException("Unsupported backup kind: $kind")
    }

    companion object {
        const val VAULT_KEY_BACKUP_KIND = 37_801
        const val ASSOCIATED_ACCOUNT_BACKUP_KIND = 37_802
        const val VAULT_KEY_D_TAG = "vaultkey"
    }
}

internal fun NostrKeyPair.privateKey(): ByteArray = privkey.hexToBytes()
internal fun NostrKeyPair.publicKey(): ByteArray = pubkey.hexToBytes()
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it.digitToIntOrNull(16) != null }) { "Invalid hexadecimal Nostr key" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
