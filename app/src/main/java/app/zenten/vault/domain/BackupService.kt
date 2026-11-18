package app.zenten.vault.domain

import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.NostrKeyDerivation.NostrKeys
import app.zenten.vault.data.model.Key
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
    suspend fun backupVaultKey(vaultKey: Key, identity: NostrKeys): PublishResult =
        relayGateway.publishAuthenticated(
            buildBackupEvent(vaultKey, vaultKey, VAULT_KEY_D_TAG, VAULT_KEY_BACKUP_KIND, identity),
            identity.privateKey,
        )

    suspend fun backupAssociatedKey(vaultKey: Key, key: Key, identity: NostrKeys): PublishResult =
        relayGateway.publishAuthenticated(
            buildBackupEvent(vaultKey, key, key.createdAt.toString(), ASSOCIATED_KEY_BACKUP_KIND, identity),
            identity.privateKey,
        )

    /**
     * Builds the signed backup event; the same identity signs an optional NIP-42 challenge.
     *
     * [identity] is derived by the caller from the Vault Key seed: [NostrKeyDerivation.fromVaultKeyBackupSeed]
     * for a Vault Key record, [NostrKeyDerivation.fromKeySeed] for an associated-key record.
     * It is not stored on [vaultKey], so nothing can drift from the seed that produced it.
     */
    fun buildBackupEvent(
        vaultKey: Key,
        key: Key,
        dTag: String,
        kind: Int,
        identity: NostrKeys,
    ): NostrEvent {
        validateContract(key, dTag, kind)
        if (kind == ASSOCIATED_KEY_BACKUP_KIND) requireEncryptedAssociatedKey(key)
        // The NIP-44 plaintext is exactly the single-line base64 OpenSSH private key.
        val encrypted = Nip44.encryptSelf(key.zenTenKey, identity.privateKey, identity.publicKey)
        return NostrEventCodec.sign(
            privateKey = identity.privateKey,
            publicKey = identity.publicKey,
            kind = kind,
            // Associated-key events carry the key label as a plain tag. The content is
            // still encrypted, so the label is the only readable field; it is optional because
            // a record written before labels existed has none, and such a key restores
            // with an empty name.
            tags = if (kind == ASSOCIATED_KEY_BACKUP_KIND) {
                listOf(keyLabelTag(key)).filterNotNull() + listOf(listOf("d", dTag))
            } else {
                listOf(listOf("d", dTag))
            },
            content = encrypted,
            createdAtSeconds = clock.instant().epochSecond,
        )
    }

    /**
     * Returns the key's `label` tag, or `null` when it has no name.
     *
     * The label is a tag rather than a content field so recovery and other Nostr clients can read
     * it without the Vault Key passphrase; the encrypted key stays the event's content.
     */
    private fun keyLabelTag(key: Key): List<String>? =
        key.keyName?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(KEY_LABEL_TAG, it) }

    private fun requireEncryptedAssociatedKey(key: Key) {
        // Validate the encrypted-container metadata without needing the passphrase: the
        // passphrase is no longer stored, and the key was already validated when it was
        // generated or restored.
        require(
            runCatching { app.zenten.vault.crypto.SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(key.zenTenKey) }.isSuccess,
        ) { "Associated-key SSH key must be passphrase-encrypted" }
    }

    private fun validateContract(key: Key, dTag: String, kind: Int) = when (kind) {
        VAULT_KEY_BACKUP_KIND -> require(dTag == VAULT_KEY_D_TAG) {
            "VaultKey backup d tag must be $VAULT_KEY_D_TAG"
        }
        ASSOCIATED_KEY_BACKUP_KIND -> require(dTag == key.createdAt.toString()) { "Associated key d tag must be its createdAt timestamp" }
        else -> throw IllegalArgumentException("Unsupported backup kind: $kind")
    }

    companion object {
        const val VAULT_KEY_BACKUP_KIND = 37_801
        const val ASSOCIATED_KEY_BACKUP_KIND = 37_802
        const val VAULT_KEY_D_TAG = "vaultkey"

        /** Plain tag on associated-key events that carries the user's key name. */
        const val KEY_LABEL_TAG = "label"
    }
}


