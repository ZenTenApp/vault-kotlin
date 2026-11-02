package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway

/**
 * Builds the [AccountBackupSource] implementations used by [AccountConsolidationEngine].
 *
 * Both source types need credentials derived from the Vault Key's SSH seed. Deriving them is
 * expensive (SSH decryption plus BIP-39 work), so one [BraveRunContext] is created per engine
 * operation and shared by every endpoint of that run.
 */
class DefaultBackupSourceFactory(
    private val relayGateway: NostrRelayGateway,
    private val backupService: BackupService,
) : BackupSourceFactory {
    /**
     * Derives the run-scoped Vault Key backup credentials once.
     *
     * Besides avoiding repeated SSH decryption, this also fixes Brave Sync's date-derived 25th
     * word for the whole run, so a run that spans midnight cannot use two different sync codes.
     */
    override fun createRunContext(secrets: VaultKeyRunSecrets): BackupSourceRunContext {
        val seed = SshKeyCodec.parse(secrets.vaultKey.zenTenKey, secrets.passphrase).seed
        val vaultKeyBackupMnemonic = Seedify.toVaultKeyBackupMnemonic(seed)
        return BraveRunContext(
            syncCode = Seedify.toBraveSyncCode(seed),
            nostr = NostrKeyDerivation.derive(vaultKeyBackupMnemonic).toPersistedKeyPair(),
        )
    }

    override fun create(config: BackupSourceConfig, context: BackupSourceRunContext?): AccountBackupSource {
        val vaultKeyBackupContext = context as? BraveRunContext
        return when (config.type) {
            // Without a run context the relay source falls back to the Vault Key identity for
            // Vault Key records; standalone callers that do so are responsible for that choice.
            BackupSourceType.NOSTR_RELAY -> NostrBackupSource(
                config = config,
                gateway = relayGateway,
                backupService = backupService,
                vaultKeyBackupNostr = vaultKeyBackupContext?.nostr,
            )
            BackupSourceType.BRAVE_SYNC -> {
                val passwordService = BravePasswordService(
                    BraveSyncTransport(serverUrl = config.endpoint, serviceKey = BraveSyncConfig.SERVICE_KEY),
                    cacheSession = true,
                )
                BraveBackupSource(config, BraveSyncBackupService(passwordService, vaultKeyBackupContext?.syncCode))
            }
        }
    }
}

/**
 * Secret, memory-only credentials shared across all endpoints of one engine operation.
 *
 * Both members are derived from the same Vault Key backup (`vkbckp`) associated account of the
 * Vault Key seed, which is what makes the Vault Key backup recoverable from the 24 words the user
 * writes down during setup.
 */
private data class BraveRunContext(
    val syncCode: String,
    val nostr: NostrKeyPair,
) : BackupSourceRunContext

data class NostrCandidate(
    override val id: BackupAccountId,
    override val sortOrder: Long,
    val event: NostrEvent,
) : RemoteAccountCandidate

/**
 * Stores backups as replaceable Nostr events on one relay.
 *
 * Two identities are in play, and they must not be mixed:
 *
 * - **Vault Key records** (`kind` [BackupService.VAULT_KEY_BACKUP_KIND]) use
 *   [vaultKeyBackupNostr], the identity derived from the Vault Key backup (`vkbckp`) associated
 *   account mnemonic, so they can be recovered from the 24 words alone.
 * - **Associated-account records** (`kind` [BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND]) use
 *   the Vault Key's own identity, which requires the Vault Key itself to recover.
 *
 * The relay record for each account is addressed by a `d` tag holding its [BackupAccountId].
 */
class NostrBackupSource(
    override val config: BackupSourceConfig,
    private val gateway: NostrRelayGateway,
    private val backupService: BackupService,
    private val vaultKeyBackupNostr: NostrKeyPair? = null,
) : AccountBackupSource {
    /** Identity that publishes and reads Vault Key records; falls back when no run context exists. */
    private fun vaultKeyIdentity(vaultKey: Account): NostrKeyPair = vaultKeyBackupNostr ?: vaultKey.nostr

    override suspend fun index(secrets: VaultKeyRunSecrets): Result<SourceIndex> = runCatching {
        val vaultKey = secrets.vaultKey
        val vaultKeyIdentity = vaultKeyIdentity(vaultKey)
        val accountIdentity = vaultKey.nostr
        // Each kind is queried under the identity that is allowed to publish it.
        val vaultKeyEvents = fetchEvents(vaultKeyIdentity, BackupService.VAULT_KEY_BACKUP_KIND)
        val accountEvents = fetchEvents(accountIdentity, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)

        val builder = SourceIndexBuilder()
        builder.addAll(vaultKeyEvents, BackupService.VAULT_KEY_BACKUP_KIND)
        builder.addAll(accountEvents, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)
        builder.build()
    }

    private suspend fun fetchEvents(identity: NostrKeyPair, kind: Int): List<NostrEvent> =
        gateway.fetchAuthenticatedByRelay(config.endpoint, identity.pubkey, kind, identity.privateKey())
            .getOrThrow()

    private fun SourceIndexBuilder.addAll(events: List<NostrEvent>, expectedKind: Int) =
        events.forEach { event ->
            val id = event.canonicalId(expectedKind)
            if (id == null) addInvalid() else add(NostrCandidate(id, event.createdAt, event))
        }

    override suspend fun restoreCandidate(secrets: VaultKeyRunSecrets, candidate: RemoteAccountCandidate): Result<Account> = runCatching {
        val vaultKey = secrets.vaultKey
        val event = (candidate as NostrCandidate).event
        val nostr = vaultKey.nostr
        // Only associated accounts are restorable: the Vault Key re-enters the app through
        // onboarding, because its passphrase is never stored remotely.
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND && event.pubkey == nostr.pubkey)
        val createdAt = requireNotNull(candidate.id.createdAt)
        // The NIP-44 plaintext is the passphrase-encrypted OpenSSH key, so the session
        // passphrase is still required to make the restored account usable.
        val decrypted = Nip44.decryptSelf(event.content, nostr.privateKey(), nostr.publicKey())
        accountFromSshKey(createdAt, decrypted, secrets.passphrase)
    }

    override suspend fun write(secrets: VaultKeyRunSecrets, accountId: BackupAccountId, account: Account): Result<Unit> {
        val vaultKey = secrets.vaultKey
        // The publishing identity depends on the record class, so swap it in before signing.
        val publisher = vaultKey.copy(nostr = if (accountId.isVaultKey) vaultKeyIdentity(vaultKey) else vaultKey.nostr)
        val event = if (accountId.isVaultKey) {
            backupService.buildBackupEvent(
                publisher,
                vaultKey,
                BackupService.VAULT_KEY_D_TAG,
                BackupService.VAULT_KEY_BACKUP_KIND,
            )
        } else {
            backupService.buildBackupEvent(
                publisher,
                account,
                accountId.value,
                BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
            )
        }
        return gateway.publishAuthenticatedToRelay(config.endpoint, event, publisher.nostr.privateKey())
    }

    /**
     * Reads the backup ID from the event's single `d` tag.
     *
     * Returns `null` for anything that does not match the backup contract, including a Vault Key
     * ID published under an account kind or vice versa, so cross-kind records are never trusted.
     */
    private fun NostrEvent.canonicalId(expectedKind: Int): BackupAccountId? {
        if (kind != expectedKind) return null
        val tag = tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return null
        val id = BackupAccountId.parse(tag) ?: return null
        return id.takeIf { (expectedKind == BackupService.VAULT_KEY_BACKUP_KIND) == it.isVaultKey }
    }
}

data class BraveCandidate(
    override val id: BackupAccountId,
    override val sortOrder: Long,
    val entry: BravePasswordService.Password,
) : RemoteAccountCandidate

/**
 * Stores backups as Brave Sync password records on one sync server.
 *
 * All record encoding, decoding, and sync-code handling lives in [BraveSyncBackupService]; this
 * adapter only maps that service onto the [AccountBackupSource] contract. Brave Sync needs no
 * identity switch because its sync code is already derived from the Vault Key backup (`vkbckp`)
 * associated account seed.
 */
class BraveBackupSource(
    override val config: BackupSourceConfig,
    private val service: BraveSyncBackupService,
) : AccountBackupSource {
    override suspend fun index(secrets: VaultKeyRunSecrets): Result<SourceIndex> = service.listRecords(secrets.vaultKey, secrets.passphrase).map { result ->
        // Records that failed to decrypt are already counted as invalid by the service.
        val builder = SourceIndexBuilder(invalidRecordCount = result.decryptionFailures)
        result.passwords.forEach { entry ->
            val id = service.accountId(entry)?.let(BackupAccountId::parse)
            if (id == null) builder.addInvalid() else builder.add(BraveCandidate(id, entry.version, entry))
        }
        builder.build()
    }

    override suspend fun restoreCandidate(secrets: VaultKeyRunSecrets, candidate: RemoteAccountCandidate): Result<Account> = runCatching {
        requireNotNull(service.decodeAssociatedAccount(secrets.vaultKey, (candidate as BraveCandidate).entry, secrets.passphrase)) {
            "Invalid Brave Sync backup record"
        }
    }

    override suspend fun write(secrets: VaultKeyRunSecrets, accountId: BackupAccountId, account: Account): Result<Unit> =
        if (accountId.isVaultKey) service.backupVaultKey(secrets.vaultKey, secrets.passphrase)
        else service.backupAssociatedAccount(secrets.vaultKey, account, secrets.passphrase)
}

/**
 * Groups remote records by backup ID while counting the ones that break the backup contract.
 *
 * Candidates for the same ID are sorted newest-first in [build] so restoration always tries the
 * most recent copy before older ones.
 */
private class SourceIndexBuilder(private var invalidRecordCount: Int = 0) {
    private val candidates = mutableMapOf<BackupAccountId, MutableList<RemoteAccountCandidate>>()

    fun add(candidate: RemoteAccountCandidate) {
        candidates.getOrPut(candidate.id) { mutableListOf() } += candidate
    }

    fun addInvalid() {
        invalidRecordCount++
    }

    fun build() = SourceIndex(
        ids = candidates.keys,
        candidates = candidates.mapValues { (_, values) -> values.sortedByDescending { it.sortOrder } },
        invalidRecordCount = invalidRecordCount,
    )
}

/**
 * Rebuilds an [Account] from a restored OpenSSH private key.
 *
 * The key is normalized to the canonical single-line Base64 form that every source stores, then
 * reparsed with the Vault Key passphrase (via Seedify) to both derive the Nostr identity and
 * confirm it is a usable Ed25519 key before it is saved.
 */
internal fun accountFromSshKey(createdAt: Long, sshPrivateKey: String, passphrase: CharArray): Account {
    val normalized = SshKeyCodec.normalizeToSingleLineBase64(sshPrivateKey)
    return Account(
        createdAt = createdAt,
        zenTenKey = normalized,
        // The account's own Nostr identity is derived from its key, not stored in the backup.
        nostr = NostrKeyDerivation.derive(Seedify.fromSshKey(normalized, passphrase))
            .toPersistedKeyPair(),
    )
}
