package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
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
 * Recovers the VaultKey record addressed by the user's Brave recovery word positions.
 *
 * The 24 words alone are enough to derive both recovery addresses, so no passphrase is needed
 * to locate a candidate:
 * - the Brave Sync code, which appends Brave's date-derived 25th word; and
 * - the Nostr identity under which Vault Key relay records are published, because that identity
 *   is the NIP-06 key pair of the same 24-word Brave sub-account mnemonic.
 *
 * Every enabled restore-capable source is queried and the first copy that decrypts with the
 * user's passphrase wins. A copy that is corrupt or belongs to another key does not block the
 * remaining sources.
 */
class VaultKeyRecoveryService(
    private val sourceRepository: BackupSourceRepository,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val workScheduler: BackupWorkScheduler,
    private val relayGateway: NostrRelayGateway,
    private val clock: Clock,
) {
    suspend fun recover(indices: List<Int>, passphrase: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val words = mnemonicWords(indices)
            val braveNostr = NostrKeyDerivation.derive(words).toPersistedKeyPair()
            val syncCode = "$words ${braveSync25thWord()}"

            // Nostr relays are queried first because a relay record needs only the 24 words,
            // while every Brave lookup also depends on Brave Sync's date-derived 25th word.
            val lookups = lookupNostrSources(braveNostr) + lookupBraveSources(syncCode)
            val candidates = lookups.mapNotNull { it.value }
            if (candidates.isEmpty()) {
                // A reachable source that simply holds no Vault Key proves absence; only when
                // every source failed do we surface connectivity instead of "not found".
                if (lookups.none { it.error == null }) {
                    lookups.firstNotNullOfOrNull { it.error as? IOException }?.let { throw it }
                }
                throw IllegalArgumentException("No VaultKey backup was found for these Secret Combinations.")
            }

            var lastFailure: Throwable? = null
            val recovered = candidates.firstNotNullOfOrNull { encryptedKey ->
                runCatching { normalizeAndParse(encryptedKey, passphrase) }
                    .onFailure { lastFailure = it }
                    .getOrNull()
            } ?: throw (lastFailure ?: IllegalArgumentException("No VaultKey backup was found for these Secret Combinations."))

            val (normalized, parsed) = recovered
            val nostr = NostrKeyDerivation.derive(
                Bip39Mnemonics.toMnemonics(parsed.seed).joinToString(" "),
            ).toPersistedKeyPair()
            secureStorage.addVaultKey(
                Account(
                    createdAt = clock.millis() * 1_000L,
                    zenTenKey = normalized,
                    zenTenPass = passphrase,
                    nostr = nostr,
                ),
            )
            appStorage.setSetupComplete()
            workScheduler.scheduleConsolidation()
        }
    }

    /** One source read; [error] preserves the difference between unreachable and record-absent. */
    private data class Lookup(val value: String?, val error: Throwable?)

    /** Reads Vault Key records from enabled relays under the 24-word-derived Brave identity. */
    private suspend fun lookupNostrSources(braveNostr: NostrKeyPair): List<Lookup> =
        enabledSources(BackupSourceType.NOSTR_RELAY).map { source ->
            runCatching {
                relayGateway.fetchAuthenticatedByRelay(
                    source.endpoint,
                    braveNostr.pubkey,
                    BackupService.VAULT_KEY_BACKUP_KIND,
                    braveNostr.privateKey(),
                ).getOrThrow().firstNotNullOfOrNull { decodeVaultKeyEvent(it, braveNostr) }
            }.fold(
                onSuccess = { Lookup(it, null) },
                onFailure = { Lookup(null, it) },
            )
        }

    /**
     * Decrypts a relay Vault Key record.
     *
     * The relay copy is a NIP-44 self-encryption under the Brave identity, and its payload is
     * the single-line encrypted OpenSSH private key. Returns `null` for anything that does not
     * satisfy the Vault Key event contract instead of handing bad material to the parser.
     */
    private fun decodeVaultKeyEvent(event: NostrEvent, braveNostr: NostrKeyPair): String? = runCatching {
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.VAULT_KEY_BACKUP_KIND && event.pubkey == braveNostr.pubkey)
        val dTag = event.tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
        require(dTag == BackupService.VAULT_KEY_D_TAG) { "VaultKey event is missing its d tag" }
        Nip44.decryptSelf(event.content, braveNostr.privateKey(), braveNostr.publicKey())
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Reads Vault Key records from enabled Brave Sync servers under the 25-word sync code. */
    private suspend fun lookupBraveSources(syncCode: String): List<Lookup> =
        enabledSources(BackupSourceType.BRAVE_SYNC).map { source ->
            runCatching {
                val service = BravePasswordService(
                    BraveSyncTransport(serverUrl = source.endpoint, serviceKey = BraveSyncConfig.SERVICE_KEY),
                )
                service.list(syncCode).passwords.firstOrNull {
                    it.username == BraveSyncBackupService.VAULT_KEY_ID &&
                        it.url == "https://${BraveSyncBackupService.VAULT_KEY_ID}.zenten/" &&
                        it.notes.isEmpty()
                }?.password
            }.fold(
                onSuccess = { Lookup(it, null) },
                onFailure = { Lookup(null, it) },
            )
        }

    /** Canonicalizes a stored key copy and proves the passphrase decrypts it. */
    private fun normalizeAndParse(
        encryptedKey: String,
        passphrase: String,
    ): Pair<String, SshKeyCodec.ParsedKey> {
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(encryptedKey)
        return normalized to SshKeyCodec.parse(normalized, passphrase.toCharArray())
    }

    private suspend fun enabledSources(type: BackupSourceType): List<BackupSourceConfig> =
        sourceRepository.sources.first()
            .filter { it.enabled && it.type == type && it.restoreEnabled }
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
