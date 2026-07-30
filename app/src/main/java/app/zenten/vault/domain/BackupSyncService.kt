package app.zenten.vault.domain

import app.zenten.vault.data.model.Account
import java.time.Clock

/** Relay-only discovery with a five-minute cooldown; it never removes local accounts. */
class BackupSyncService(
    private val recoveryService: RecoveryService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var lastSyncMillis = Long.MIN_VALUE

    suspend fun sync(master: Account): RecoveryService.RecoveryResult? {
        val now = clock.millis()
        if (now - lastSyncMillis < COOLDOWN_MILLIS) return null
        lastSyncMillis = now
        return recoveryService.discoverSubAccounts(master)
    }

    companion object { const val COOLDOWN_MILLIS = 5 * 60 * 1000L }
}
