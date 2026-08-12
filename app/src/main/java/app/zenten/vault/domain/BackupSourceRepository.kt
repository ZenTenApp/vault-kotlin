package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class BackupSourceRepository(private val appStorage: AppStorageService) {
    val sources: Flow<List<BackupSourceConfig>> = appStorage.customBackupSources.map(::mergeDefaults)

    suspend fun current(): List<BackupSourceConfig> = mergeDefaults(appStorage.customBackupSources.first())

    suspend fun addCustom(
        label: String,
        type: BackupSourceType,
        endpoint: String,
        enabled: Boolean = true,
        countsTowardQuorum: Boolean = false,
        restoreEnabled: Boolean = true,
        trustedForRestore: Boolean = false,
    ): BackupSourceConfig {
        require(label.isNotBlank()) { "Source label is required" }
        val normalized = normalizeSourceEndpoint(type, endpoint)
        val current = current()
        require(current.none { normalizeSourceEndpoint(it.type, it.endpoint) == normalized }) { "Backup source already exists" }
        val config = BackupSourceConfig(
            id = "custom-${UUID.randomUUID()}",
            label = label.trim(),
            type = type,
            endpoint = normalized,
            order = (current.maxOfOrNull { it.order } ?: 0) + 1,
            enabled = enabled,
            countsTowardQuorum = countsTowardQuorum,
            restoreEnabled = restoreEnabled,
            trustedForRestore = trustedForRestore,
            authentication = if (type == BackupSourceType.NOSTR_RELAY) SourceAuthentication.NIP42_OPTIONAL else SourceAuthentication.BRAVE_SYNC_TOKEN,
        )
        appStorage.setCustomBackupSources(appStorage.customBackupSources.first() + config)
        return config
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutateCustom(id) { it.copy(enabled = enabled) }
    suspend fun setOrder(id: String, order: Int) = mutateCustom(id) { it.copy(order = order) }

    suspend fun remove(id: String) {
        require(DefaultBackupSources.all.none { it.id == id }) { "Default backup sources cannot be removed" }
        appStorage.setCustomBackupSources(appStorage.customBackupSources.first().filterNot { it.id == id })
    }

    private suspend fun mutateCustom(id: String, transform: (BackupSourceConfig) -> BackupSourceConfig) {
        require(DefaultBackupSources.all.none { it.id == id }) { "Default backup sources cannot be changed" }
        val custom = appStorage.customBackupSources.first()
        require(custom.any { it.id == id }) { "Backup source not found" }
        appStorage.setCustomBackupSources(custom.map { if (it.id == id) transform(it) else it })
    }

    private fun mergeDefaults(custom: List<BackupSourceConfig>): List<BackupSourceConfig> =
        (DefaultBackupSources.all + custom.filterNot { candidate -> DefaultBackupSources.all.any { it.id == candidate.id } })
            .distinctBy { normalizeSourceEndpoint(it.type, it.endpoint) }
            .sortedWith(compareBy<BackupSourceConfig> { it.order }.thenBy { it.id })
}
