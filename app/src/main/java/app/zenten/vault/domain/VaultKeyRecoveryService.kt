package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock

/**
 * Recovers the VaultKey record addressed by the user's Vault Key backup recovery word positions.
 *
 * The 24 words alone are enough to derive both recovery addresses, so no passphrase is needed
 * to locate a candidate:
 * - the Brave Sync code, which appends Brave's date-derived 25th word; and
 * - the Nostr identity under which Vault Key relay records are published, because that identity
 *   is the NIP-06 key pair of the same 24-word Vault Key backup (`vkbckp`) associated account mnemonic.
 *
 * Every enabled, restore-trusted source is queried and every copy it holds is a candidate. A
 * candidate is accepted only when the passphrase decrypts it *and* its `vkbckp` backup mnemonic
 * re-derives to the exact 24 words the user supplied; decryption alone proves the passphrase, not
 * ownership. Candidates are tried newest-first and a copy that is corrupt or belongs to another key
 * does not block the remaining copies or sources.
 */
class VaultKeyRecoveryService(
    private val sourceRepository: BackupSourceRepository,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val workScheduler: BackupWorkScheduler,
    private val relayGateway: NostrRelayGateway,
    private val vaultKeySession: VaultKeySession,
    private val clock: Clock,
) {
    suspend fun recover(indices: List<Int>, passphrase: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val words = mnemonicWords(indices)
            val vaultKeyBackupIdentity = NostrKeyDerivation.derive(words)
            val syncCode = "$words ${braveSync25thWord()}"

            // Nostr relays are queried first because a relay record needs only the 24 words,
            // while every Brave lookup also depends on Brave Sync's date-derived 25th word.
            val lookups = lookupNostrSources(vaultKeyBackupIdentity) + lookupBraveSources(syncCode)
            val candidates = lookups.flatMap { it.candidates }
            if (candidates.isEmpty()) {
                // A reachable source that simply holds no Vault Key proves absence; only when
                // every source failed do we surface connectivity instead of "not found".
                if (lookups.none { it.error == null }) {
                    lookups.firstNotNullOfOrNull { it.error as? IOException }?.let { throw it }
                }
                throw IllegalArgumentException("No VaultKey backup was found for these Secret Combinations.")
            }

            // Decryption proves the passphrase, not ownership: a copy that decrypts but whose seed
            // re-derives to other words is another key's backup and is skipped, not persisted.
            var sawIncompatibleCopy = false
            var lastFailure: Throwable? = null
            val recovered = candidates.firstNotNullOfOrNull { encryptedKey ->
                runCatching { normalizeAndParse(encryptedKey, passphrase, indices) }
                    .onFailure {
                        if (it is VaultKeyMismatchException) sawIncompatibleCopy = true
                        lastFailure = it
                    }
                    .getOrNull()
            } ?: run {
                if (sawIncompatibleCopy) {
                    throw IllegalArgumentException(
                        "A VaultKey backup was found for these Secret Combinations, but it does not " +
                            "belong to them. Check the Secret Combinations, or confirm the backup was " +
                            "not overwritten.",
                    )
                }
                throw (lastFailure ?: IllegalArgumentException("No VaultKey backup was found for these Secret Combinations."))
            }

            // Only the SSH container is stored; every identity derived from it is rebuilt on demand.
            val normalized = recovered.first
            secureStorage.addVaultKey(
                Account(
                    createdAt = clock.millis() * 1_000L,
                    zenTenKey = normalized,
                ),
            )
            // The passphrase was just used to decrypt the recovered key, so record it in the
            // session without repeating the bcrypt decryption. It is never persisted.
            vaultKeySession.establish(passphrase.toCharArray())
            // A recovered Vault Key exists remotely by definition: the copy was just read and
            // decrypted from a source, so the setup-completion quorum check is satisfied.
            appStorage.setVaultKeyQuorumReached(true)
            appStorage.setSetupComplete()
            workScheduler.scheduleConsolidation()
        }
    }

    /**
     * One source read. [candidates] keeps every decryptable copy so a corrupt first copy cannot
     * mask a valid later one; [error] preserves the difference between unreachable and absent.
     */
    private data class Lookup(val candidates: List<String>, val error: Throwable?)

    /** Reads Vault Key records from enabled relays under the 24-word-derived Vault Key backup identity. */
    private suspend fun lookupNostrSources(vaultKeyBackupIdentity: NostrKeyDerivation.NostrKeys): List<Lookup> =
        enabledSources(BackupSourceType.NOSTR_RELAY).map { source ->
            runCatching {
                relayGateway.fetchAuthenticatedByRelay(
                    source.endpoint,
                    vaultKeyBackupIdentity.pubkey,
                    BackupService.VAULT_KEY_BACKUP_KIND,
                    vaultKeyBackupIdentity.privateKey,
                ).getOrThrow()
                    .sortedByDescending(NostrEvent::createdAt)
                    .mapNotNull { decodeVaultKeyEvent(it, vaultKeyBackupIdentity) }
            }.fold(
                onSuccess = { Lookup(it, null) },
                onFailure = { Lookup(emptyList(), it) },
            )
        }

    /**
     * Decrypts a relay Vault Key record.
     *
     * The relay copy is a NIP-44 self-encryption under the Vault Key backup identity, and its payload is
     * the single-line encrypted OpenSSH private key. Returns `null` for anything that does not
     * satisfy the Vault Key event contract instead of handing bad material to the parser.
     */
    private fun decodeVaultKeyEvent(event: NostrEvent, vaultKeyBackupIdentity: NostrKeyDerivation.NostrKeys): String? = runCatching {
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.VAULT_KEY_BACKUP_KIND && event.pubkey == vaultKeyBackupIdentity.pubkey)
        val dTag = event.tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
        require(dTag == BackupService.VAULT_KEY_D_TAG) { "VaultKey event is missing its d tag" }
        Nip44.decryptSelf(event.content, vaultKeyBackupIdentity.privateKey, vaultKeyBackupIdentity.publicKey)
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Reads Vault Key records from enabled Brave Sync servers under the 25-word sync code. */
    private suspend fun lookupBraveSources(syncCode: String): List<Lookup> =
        enabledSources(BackupSourceType.BRAVE_SYNC).map { source ->
            runCatching {
                val service = BravePasswordService(
                    BraveSyncTransport(serverUrl = source.endpoint, serviceKey = BraveSyncConfig.SERVICE_KEY),
                )
                service.list(syncCode).passwords
                    .filter {
                        it.username == BraveSyncBackupService.VAULT_KEY_ID &&
                            it.url == "https://${BraveSyncBackupService.VAULT_KEY_ID}.zenten/" &&
                            it.notes.isEmpty()
                    }
                    .sortedByDescending { it.version }
                    .map { it.password }
            }.fold(
                onSuccess = { Lookup(it, null) },
                onFailure = { Lookup(emptyList(), it) },
            )
        }

    /** Thrown when a copy decrypts but its seed re-derives to words other than the user's. */
    private class VaultKeyMismatchException(message: String) : IllegalArgumentException(message)

    /**
     * Canonicalizes a stored key copy and proves both that the passphrase decrypts it and that the
     * key belongs to the supplied recovery words.
     *
     * The 24 words address the backup (`vkbckp`) associated account, so the recovered seed's backup
     * mnemonic — not the raw seed mnemonic — is what must match. The 25th Brave word is a
     * date-derived freshness token and is not part of the identity.
     */
    private fun normalizeAndParse(
        encryptedKey: String,
        passphrase: String,
        indices: List<Int>,
    ): Pair<String, SshKeyCodec.ParsedKey> {
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(encryptedKey)
        val parsed = SshKeyCodec.parse(normalized, passphrase.toCharArray())
        if (Seedify.toVaultKeyBackupWordIndices(parsed.seed) != indices) {
            throw VaultKeyMismatchException("This VaultKey backup does not belong to the supplied Secret Combinations.")
        }
        return normalized to parsed
    }

    private suspend fun enabledSources(type: BackupSourceType): List<BackupSourceConfig> =
        sourceRepository.sources.first()
            .filter { it.enabled && it.type == type && it.restoreEnabled && it.trustedForRestore }
            .sortedBy(BackupSourceConfig::order)

    private fun mnemonicWords(indices: List<Int>): String {
        require(indices.size == 24) { "Enter all 24 Secret Combination values." }
        val words = indices.map { index ->
            require(index in 1..Bip39Mnemonics.englishWordlist.size) {
                "Each Secret Combination value must be between 1 and ${Bip39Mnemonics.englishWordlist.size}."
            }
            Bip39Mnemonics.englishWordlist[index - 1]
        }
        // This validates both the allowed values and the BIP-39 checksum before any network call.
        Bip39Mnemonics.validate(words)
        return words.joinToString(" ")
    }

    private fun braveSync25thWord(): String =
        Seedify.braveSync25thWord(clock.instant())
}
