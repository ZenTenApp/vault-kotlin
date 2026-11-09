package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
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
     * The SSH seed is decrypted once for the whole run, and every identity the sources need is
     * derived from it here rather than read from stored state. Besides avoiding repeated SSH
     * decryption, this also fixes Brave Sync's date-derived 25th word for the whole run, so a run
     * that spans midnight cannot use two different sync codes.
     */
    override fun createRunContext(secrets: VaultKeyRunSecrets): BackupSourceRunContext {
        val seed = SshKeyCodec.parse(secrets.vaultKey.zenTenKey, secrets.passphrase).seed
        return BraveRunContext(
            syncCode = Seedify.toBraveSyncCode(seed),
            vaultKeyBackupIdentity = NostrKeyDerivation.fromVaultKeyBackupSeed(seed),
            accountIdentity = NostrKeyDerivation.fromAccountSeed(seed),
        )
    }

    override fun create(config: BackupSourceConfig, context: BackupSourceRunContext?): AccountBackupSource {
        val vaultKeyBackupContext = context as? BraveRunContext
        return when (config.type) {
            // A standalone source without a run context has no decrypted seed to derive from, so
            // it refuses to guess: both identities are required for a relay so that a Vault Key
            // record can never be published under the associated-account identity.
            BackupSourceType.NOSTR_RELAY -> NostrBackupSource(
                config = config,
                gateway = relayGateway,
                backupService = backupService,
                identities = vaultKeyBackupContext,
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
 * Every member is derived from the Vault Key seed and exists only for the life of the run:
 *
 * - [syncCode] and [vaultKeyBackupIdentity] come from the `vkbckp` associated account, which is
 *   what makes the Vault Key backup recoverable from the 24 words the user writes down.
 * - [accountIdentity] is the Vault Key's own Nostr identity, which owns associated-account records.
 */
private data class BraveRunContext(
    val syncCode: String,
    val vaultKeyBackupIdentity: NostrKeyDerivation.NostrKeys,
    val accountIdentity: NostrKeyDerivation.NostrKeys,
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
 *   [NostrKeyDerivation.fromVaultKeyBackupSeed], the identity derived from the Vault Key backup
 *   (`vkbckp`) associated account mnemonic, so they can be recovered from the 24 words alone.
 * - **Associated-account records** (`kind` [BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND]) use
 *   the Vault Key's own identity, which requires the Vault Key itself to recover.
 *
 * Both are passed in from the run context because they are derived from the decrypted seed;
 * neither is stored on the [Account]. The relay record for each account is addressed by a `d`
 * tag holding its [BackupAccountId].
 */
class NostrBackupSource(
    override val config: BackupSourceConfig,
    private val gateway: NostrRelayGateway,
    private val backupService: BackupService,
    private val identities: BackupSourceRunContext?,
) : AccountBackupSource {
    /**
     * The two identities for this run, or a failure when the source was created standalone.
     *
     * Deriving them requires the decrypted SSH seed, which only a run context has. Failing here
     * is deliberate: falling back to a stored identity is what used to let the two record classes
     * drift onto the same Nostr key.
     */
    private fun identities(): BraveRunContext =
        identities as? BraveRunContext ?: error("Nostr backup source requires a run context")

    override suspend fun index(secrets: VaultKeyRunSecrets): Result<SourceIndex> = runCatching {
        val runIdentities = identities()
        // Each kind is queried under the identity that is allowed to publish it.
        val vaultKeyEvents = fetchEvents(runIdentities.vaultKeyBackupIdentity, BackupService.VAULT_KEY_BACKUP_KIND)
        val accountEvents = fetchEvents(runIdentities.accountIdentity, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)

        val builder = SourceIndexBuilder()
        builder.addAll(vaultKeyEvents, BackupService.VAULT_KEY_BACKUP_KIND)
        builder.addAll(accountEvents, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)
        builder.build()
    }

    private suspend fun fetchEvents(identity: NostrKeyDerivation.NostrKeys, kind: Int): List<NostrEvent> =
        gateway.fetchAuthenticatedByRelay(config.endpoint, identity.pubkey, kind, identity.privateKey)
            .getOrThrow()

    private fun SourceIndexBuilder.addAll(events: List<NostrEvent>, expectedKind: Int) =
        events.forEach { event ->
            val id = event.canonicalId(expectedKind)
            if (id == null) addInvalid() else add(NostrCandidate(id, event.createdAt, event))
        }

    override suspend fun restoreCandidate(secrets: VaultKeyRunSecrets, candidate: RemoteAccountCandidate): Result<Account> = runCatching {
        val event = (candidate as NostrCandidate).event
        val identity = identities().accountIdentity
        // Only associated accounts are restorable: the Vault Key re-enters the app through
        // onboarding, because its passphrase is never stored remotely.
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND && event.pubkey == identity.pubkey)
        val createdAt = requireNotNull(candidate.id.createdAt)
        // The NIP-44 plaintext is the passphrase-encrypted OpenSSH key, so the session
        // passphrase is still required to make the restored account usable.
        val decrypted = Nip44.decryptSelf(event.content, identity.privateKey, identity.publicKey)
        // The `label` tag is the only place the account name survives a device change.
        accountFromSshKey(createdAt, decrypted, event.accountLabel())
    }

    /**
     * Reads the optional `label` tag on an associated-account event.
     *
     * The first label wins when an event carries more than one, and a blank value is treated as
     * absent so the restored account keeps an empty name.
     */
    private fun NostrEvent.accountLabel(): String? =
        tags.firstOrNull { it.firstOrNull() == BackupService.ACCOUNT_LABEL_TAG }
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    override suspend fun write(secrets: VaultKeyRunSecrets, accountId: BackupAccountId, account: Account): Result<Unit> {
        val vaultKey = secrets.vaultKey
        val runIdentities = identities()
        // The publishing identity depends on the record class: Vault Key records must stay under
        // the recoverable identity, associated-account records under the Vault Key's own.
        val identity = if (accountId.isVaultKey) runIdentities.vaultKeyBackupIdentity else runIdentities.accountIdentity
        val event = if (accountId.isVaultKey) {
            backupService.buildBackupEvent(
                vaultKey,
                vaultKey,
                BackupService.VAULT_KEY_D_TAG,
                BackupService.VAULT_KEY_BACKUP_KIND,
                identity,
            )
        } else {
            backupService.buildBackupEvent(
                vaultKey,
                account,
                accountId.value,
                BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
                identity,
            )
        }
        return gateway.publishAuthenticatedToRelay(config.endpoint, event, identity.privateKey)
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
        // `decodeAssociatedAccount` returns null for a record that fails intake, which also covers
        // a key whose declared work factor is outside the accepted band.
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
 * The key is normalized to the canonical single-line Base64 form that every source stores. Intake
 * is deliberately routed through [SshKeyCodec.validateAndNormalizeEncryptedPrivateKey] rather than
 * the plain normalizer: that reader enforces the supported structure and the accepted
 * `bcrypt_pbkdf` work-factor band without needing the passphrase. A record outside the band is
 * therefore rejected here, before it can be stored, instead of becoming an account the app would
 * later refuse to unlock or export. Nothing derived from the key is persisted: every consumer
 * re-derives what it needs from this container.
 *
 * @param sshPrivateKey the passphrase-encrypted OpenSSH key read from the backup record.
 * @param accountName the label carried by the record, or `null` when it carried none.
 */
internal fun accountFromSshKey(createdAt: Long, sshPrivateKey: String, accountName: String? = null): Account =
    Account(
        createdAt = createdAt,
        zenTenKey = SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(sshPrivateKey),
        accountName = accountName,
    )
