package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock

/** Recovers the VaultKey record addressed by the user's Brave recovery word positions. */
class VaultKeyRecoveryService(
    private val sourceRepository: BackupSourceRepository,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val workScheduler: BackupWorkScheduler,
    private val clock: Clock,
) {
    suspend fun recover(indices: List<Int>, passphrase: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val words = mnemonicWords(indices)
            val syncCode = "$words ${braveSync25thWord()}"
            val entries = enabledBraveSources().asSequence().mapNotNull { source ->
                runCatching {
                    val service = BravePasswordService(
                        BraveSyncTransport(serverUrl = source.endpoint, serviceKey = BraveSyncConfig.SERVICE_KEY),
                    )
                    service.list(syncCode).passwords.firstOrNull {
                        it.username == BraveSyncBackupService.VAULT_KEY_ID &&
                            it.url == "https://${BraveSyncBackupService.VAULT_KEY_ID}.zenten/" &&
                            it.notes.isEmpty()
                    }
                }.getOrNull()
            }
            val key = entries.firstOrNull()?.password
                ?: throw IllegalArgumentException("No VaultKey backup was found for these Secret Combinations.")
            val normalized = SshKeyCodec.normalizeToSingleLineBase64(key)
            val parsed = SshKeyCodec.parse(normalized, passphrase.toCharArray())
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

    private suspend fun enabledBraveSources(): List<BackupSourceConfig> = sourceRepository.sources.first()
        .filter { it.enabled && it.type == BackupSourceType.BRAVE_SYNC && it.restoreEnabled }
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
        app.zenten.vault.crypto.Seedify.braveSync25thWord(clock.instant())
}
