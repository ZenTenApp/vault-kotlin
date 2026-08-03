package app.zenten.vault.domain

import app.zenten.vault.data.kv.AccountBackupStatus
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.BackupCoverageState
import app.zenten.vault.data.kv.DestinationBackupStatus
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrRelayGateway
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * Discovers every backup destination, restores unknown associated accounts locally, and repairs
 * destinations that were successfully checked but did not contain an account. Presence is based
 * solely on the canonical backup ID: masterkey for the master and createdAt for associated accounts.
 */
class BackupSyncService(
    private val relayGateway: NostrRelayGateway,
    private val backupService: BackupService,
    private val braveSync: BraveSyncBackupService,
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private var lastSyncMillis = Long.MIN_VALUE

    suspend fun sync(force: Boolean = false): SyncResult? = mutex.withLock {
        val now = clock.millis()
        if (!force && now - lastSyncMillis < COOLDOWN_MILLIS) return null
        lastSyncMillis = now
        val master = secureStorage.getMasterAccount() ?: return null
        val masterPubkey = requireNotNull(master.nostr) { "Master Nostr identity is required for reconciliation" }.pubkey
        val ids = linkedMapOf(MASTER_ID to master)
        secureStorage.getAccounts().forEach { ids[it.createdAt.toString()] = it }
        val coverage = ids.keys.associateWith { linkedMapOf<String, DestinationBackupStatus>() }.toMutableMap()
        val relayPresence = mutableMapOf<String, Set<String>?>()
        var bravePresence: Set<String>? = null

        // Each relay is independently checked. A failed request is UNKNOWN, never MISSING.
        relayGateway.relayUrls.forEach { relay ->
            val destination = relayDestination(relay)
            val masterEvents = relayGateway.fetchByRelay(relay, masterPubkey, BackupService.MASTER_BACKUP_KIND)
            val subEvents = relayGateway.fetchByRelay(relay, masterPubkey, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND)
            if (masterEvents.isFailure || subEvents.isFailure) {
                relayPresence[destination] = null
                coverage.values.forEach { it[destination] = status(destination, relay, BackupCoverageState.UNKNOWN) }
            } else {
                val present = linkedSetOf<String>()
                masterEvents.getOrThrow().firstOrNull { it.isMasterBackup() }?.let { present += MASTER_ID }
                subEvents.getOrThrow().forEach { event ->
                    event.associatedAccountId()?.let { id ->
                        present += id
                        if (id !in ids) recoveryAccount(event, master)?.let { account ->
                            ids[id] = account
                            secureStorage.addAccount(account)
                            coverage[id] = linkedMapOf()
                        }
                    }
                }
                relayPresence[destination] = present
                ids.keys.forEach { id ->
                    coverage.getValue(id)[destination] = status(destination, relay,
                        if (id in present) BackupCoverageState.PRESENT else BackupCoverageState.MISSING)
                }
            }
        }

        val braveDestination = BRAVE_DESTINATION
        val braveMaster = braveSync.hasMasterBackup(master)
        val braveSubs = runCatching { braveSync.discoverAssociatedAccounts(master) }
        if (braveMaster.isFailure || braveSubs.isFailure) {
            bravePresence = null
            coverage.values.forEach { it[braveDestination] = status(braveDestination, "Brave Sync", BackupCoverageState.UNKNOWN) }
        } else {
            val present = linkedSetOf<String>()
            if (braveMaster.getOrThrow()) present += MASTER_ID
            braveSubs.getOrThrow().accounts.forEach { account ->
                val id = account.createdAt.toString()
                present += id
                if (id !in ids) {
                    ids[id] = account
                    secureStorage.addAccount(account)
                    coverage[id] = linkedMapOf()
                }
            }
            bravePresence = present
            ids.keys.forEach { id ->
                coverage.getValue(id)[braveDestination] = status(braveDestination, "Brave Sync",
                    if (id in present) BackupCoverageState.PRESENT else BackupCoverageState.MISSING)
            }
        }

        // Late discoveries must be compared with sources already checked in this same pass.
        ids.keys.forEach { id ->
            relayGateway.relayUrls.forEach { relay ->
                val destination = relayDestination(relay)
                val present = relayPresence[destination]
                coverage.getValue(id)[destination] = status(destination, relay, when {
                    present == null -> BackupCoverageState.UNKNOWN
                    id in present -> BackupCoverageState.PRESENT
                    else -> BackupCoverageState.MISSING
                })
            }
            coverage.getValue(id)[braveDestination] = status(braveDestination, "Brave Sync", when {
                bravePresence == null -> BackupCoverageState.UNKNOWN
                id in bravePresence!! -> BackupCoverageState.PRESENT
                else -> BackupCoverageState.MISSING
            })
        }

        // Repair only destinations conclusively found missing. A later run rechecks the writes.
        ids.forEach { (id, account) ->
            coverage.getValue(id).values.filter { it.state == BackupCoverageState.MISSING }.forEach { missing ->
                val repaired = if (missing.id == BRAVE_DESTINATION) {
                    if (id == MASTER_ID) braveSync.backupMaster(master) else braveSync.backupAssociatedAccount(master, account)
                } else {
                    val relay = missing.id.removePrefix(RELAY_PREFIX)
                    relayGateway.publishToRelay(relay, if (id == MASTER_ID) backupService.buildBackupEvent(master, master, MASTER_ID, BackupService.MASTER_BACKUP_KIND)
                    else backupService.buildBackupEvent(master, account, id, BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND))
                }
                if (repaired.isSuccess) coverage.getValue(id)[missing.id] = missing.copy(state = BackupCoverageState.PRESENT)
                else coverage.getValue(id)[missing.id] = missing.copy(state = BackupCoverageState.REPAIR_FAILED)
            }
        }

        val expectedDestinations = relayGateway.relayUrls.map { relayDestination(it) to it } + (BRAVE_DESTINATION to "Brave Sync")
        val statuses = ids.keys.map { id ->
            val states = coverage.getValue(id)
            expectedDestinations.forEach { (destination, label) ->
                states.putIfAbsent(destination, status(destination, label, BackupCoverageState.UNKNOWN))
            }
            AccountBackupStatus(id, states.values.toList(), now)
        }
        appStorage.setBackupStatuses(statuses)
        SyncResult(statuses)
    }

    private fun recoveryAccount(event: NostrEvent, master: Account): Account? = RecoveryService(relayGateway, secureStorage).decodeAssociatedAccount(event, master)
    private fun NostrEvent.isMasterBackup() = tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1) == MASTER_ID
    private fun NostrEvent.associatedAccountId(): String? = tags.singleOrNull { it.firstOrNull() == "d" }?.getOrNull(1)?.takeIf { it.all(Char::isDigit) }
    private fun status(id: String, label: String, state: BackupCoverageState) = DestinationBackupStatus(id, label, state)
    private fun relayDestination(url: String) = RELAY_PREFIX + url

    data class SyncResult(val statuses: List<AccountBackupStatus>)

    companion object {
        const val COOLDOWN_MILLIS = 5 * 60 * 1000L
        const val MASTER_ID = "masterkey"
        const val BRAVE_DESTINATION = "brave-sync"
        const val RELAY_PREFIX = "nostr:"
    }
}
