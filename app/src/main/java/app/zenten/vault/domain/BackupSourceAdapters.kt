package app.zenten.vault.domain

import app.zenten.vault.bravesync.BravePasswordService
import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.transport.BraveSyncTransport
import app.zenten.vault.crypto.Nip44
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Account
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway

class DefaultBackupSourceFactory(
    private val relayGateway: NostrRelayGateway,
    private val backupService: BackupService,
) : BackupSourceFactory {
    override fun create(config: BackupSourceConfig): AccountBackupSource = when (config.type) {
        BackupSourceType.NOSTR_RELAY -> NostrBackupSource(config, relayGateway, backupService)
        BackupSourceType.BRAVE_SYNC -> {
            val passwordService = BravePasswordService(
                BraveSyncTransport(serverUrl = config.endpoint, serviceKey = BraveSyncConfig.SERVICE_KEY),
            )
            BraveBackupSource(config, BraveSyncBackupService(passwordService))
        }
    }
}

data class NostrCandidate(
    override val id: BackupAccountId,
    override val sortOrder: Long,
    val event: NostrEvent,
) : RemoteAccountCandidate

class NostrBackupSource(
    override val config: BackupSourceConfig,
    private val gateway: NostrRelayGateway,
    private val backupService: BackupService,
) : AccountBackupSource {
    override suspend fun index(master: Account): Result<SourceIndex> = runCatching {
        val nostr = requireNotNull(master.nostr)
        val privateKey = nostr.privateKey()
        val masterEvents = gateway.fetchAuthenticatedByRelay(
            config.endpoint, nostr.pubkey, BackupService.MASTER_BACKUP_KIND, privateKey,
        ).getOrThrow()
        val accountEvents = gateway.fetchAuthenticatedByRelay(
            config.endpoint, nostr.pubkey, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND, privateKey,
        ).getOrThrow()
        val candidates = mutableMapOf<BackupAccountId, MutableList<RemoteAccountCandidate>>()
        var invalid = 0
        masterEvents.forEach { event ->
            val id = event.canonicalId(BackupService.MASTER_BACKUP_KIND)
            if (id == null) invalid++ else candidates.getOrPut(id) { mutableListOf() } += NostrCandidate(id, event.createdAt, event)
        }
        accountEvents.forEach { event ->
            val id = event.canonicalId(BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)
            if (id == null) invalid++ else candidates.getOrPut(id) { mutableListOf() } += NostrCandidate(id, event.createdAt, event)
        }
        SourceIndex(
            ids = candidates.keys,
            candidates = candidates.mapValues { (_, values) -> values.sortedByDescending { it.sortOrder } },
            invalidRecordCount = invalid,
        )
    }

    override suspend fun restoreCandidate(master: Account, candidate: RemoteAccountCandidate): Result<Account> = runCatching {
        val event = (candidate as NostrCandidate).event
        val nostr = requireNotNull(master.nostr)
        require(NostrEventCodec.isValid(event))
        require(event.kind == BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND && event.pubkey == nostr.pubkey)
        val id = requireNotNull(candidate.id.createdAt)
        val decrypted = Nip44.decryptSelf(event.content, nostr.privateKey(), nostr.publicKey())
        val passphrase = requireNotNull(master.zenTenPass)
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(decrypted)
        val parsed = SshKeyCodec.parse(normalized, passphrase.toCharArray())
        Account(
            createdAt = id,
            zenTenKey = normalized,
            zenTenPass = passphrase,
            pubkey = SshKeyCodec.publicKeyLine(parsed.publicKey, parsed.comment),
        )
    }

    override suspend fun write(master: Account, accountId: BackupAccountId, account: Account): Result<Unit> {
        val event = if (accountId.isMaster) {
            backupService.buildBackupEvent(master, master, BackupService.MASTER_D_TAG, BackupService.MASTER_BACKUP_KIND)
        } else {
            backupService.buildBackupEvent(master, account, accountId.value, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)
        }
        return gateway.publishAuthenticatedToRelay(config.endpoint, event, requireNotNull(master.nostr).privateKey())
    }

    private fun NostrEvent.canonicalId(expectedKind: Int): BackupAccountId? {
        if (kind != expectedKind) return null
        val tag = tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return null
        val id = BackupAccountId.parse(tag) ?: return null
        return id.takeIf { (expectedKind == BackupService.MASTER_BACKUP_KIND) == it.isMaster }
    }
}

data class BraveCandidate(
    override val id: BackupAccountId,
    override val sortOrder: Long,
    val entry: BravePasswordService.Password,
) : RemoteAccountCandidate

class BraveBackupSource(
    override val config: BackupSourceConfig,
    private val service: BraveSyncBackupService,
) : AccountBackupSource {
    override suspend fun index(master: Account): Result<SourceIndex> = service.listRecords(master).map { result ->
        val candidates = mutableMapOf<BackupAccountId, MutableList<RemoteAccountCandidate>>()
        var invalid = result.decryptionFailures
        result.passwords.forEach { entry ->
            val id = service.accountId(entry)?.let(BackupAccountId::parse)
            if (id == null) invalid++ else candidates.getOrPut(id) { mutableListOf() } += BraveCandidate(id, entry.version, entry)
        }
        SourceIndex(
            ids = candidates.keys,
            candidates = candidates.mapValues { (_, values) -> values.sortedByDescending { it.sortOrder } },
            invalidRecordCount = invalid,
        )
    }

    override suspend fun restoreCandidate(master: Account, candidate: RemoteAccountCandidate): Result<Account> = runCatching {
        requireNotNull(service.decodeAssociatedAccount(master, (candidate as BraveCandidate).entry)) {
            "Invalid Brave Sync backup record"
        }
    }

    override suspend fun write(master: Account, accountId: BackupAccountId, account: Account): Result<Unit> =
        if (accountId.isMaster) service.backupMaster(master) else service.backupAssociatedAccount(master, account)
}
