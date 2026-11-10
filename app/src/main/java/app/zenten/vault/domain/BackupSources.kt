package app.zenten.vault.domain

import app.zenten.vault.data.model.Account
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
enum class BackupSourceType { NOSTR_RELAY, BRAVE_SYNC }

@Serializable
enum class SourceAuthentication { NIP42_OPTIONAL, BRAVE_SYNC_TOKEN }

@Serializable
data class BackupSourceConfig(
    val id: String,
    val label: String,
    val type: BackupSourceType,
    val endpoint: String,
    val order: Int,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val removable: Boolean = true,
    val countsTowardQuorum: Boolean = false,
    val restoreEnabled: Boolean = true,
    val trustedForRestore: Boolean = false,
    val authentication: SourceAuthentication,
)

object DefaultBackupSources {
    val all = listOf(
        BackupSourceConfig(
            id = "default-nostr-appdata1",
            label = "appdata1",
            type = BackupSourceType.NOSTR_RELAY,
            endpoint = "wss://appdata1.zentext.me",
            order = 1,
            isDefault = true,
            removable = false,
            countsTowardQuorum = true,
            trustedForRestore = true,
            authentication = SourceAuthentication.NIP42_OPTIONAL,
        ),
        BackupSourceConfig(
            id = "default-nostr-appdata2",
            label = "appdata2",
            type = BackupSourceType.NOSTR_RELAY,
            endpoint = "wss://appdata2.nostr.box",
            order = 2,
            isDefault = true,
            removable = false,
            countsTowardQuorum = true,
            trustedForRestore = true,
            authentication = SourceAuthentication.NIP42_OPTIONAL,
        ),
        BackupSourceConfig(
            id = "default-brave-zenten",
            label = "litesync1",
            type = BackupSourceType.BRAVE_SYNC,
            endpoint = "https://litesync1.zentext.me/litesync",
            order = 3,
            isDefault = true,
            removable = false,
            countsTowardQuorum = true,
            trustedForRestore = true,
            authentication = SourceAuthentication.BRAVE_SYNC_TOKEN,
        ),
        BackupSourceConfig(
            id = "default-brave-nostr-box",
            label = "litesync2",
            type = BackupSourceType.BRAVE_SYNC,
            endpoint = "https://litesync2.nostr.box/litesync",
            order = 4,
            isDefault = true,
            removable = false,
            countsTowardQuorum = true,
            trustedForRestore = true,
            authentication = SourceAuthentication.BRAVE_SYNC_TOKEN,
        ),
    )
}

@Serializable
data class BackupAccountId(val value: String) {
    val isVaultKey: Boolean get() = value == VAULT_KEY_VALUE
    val createdAt: Long? get() = value.takeUnless { isVaultKey }?.toLongOrNull()

    companion object {
        const val VAULT_KEY_VALUE = "vaultkey"
        val VaultKey = BackupAccountId(VAULT_KEY_VALUE)
        fun associated(createdAt: Long) = BackupAccountId(createdAt.toString())
        fun parse(value: String): BackupAccountId? = when {
            value == VAULT_KEY_VALUE -> VaultKey
            value.isNotEmpty() && value.all(Char::isDigit) && value.toLongOrNull() != null -> BackupAccountId(value)
            else -> null
        }
    }
}

interface RemoteAccountCandidate {
    val id: BackupAccountId
    val sortOrder: Long
}

data class SourceIndex(
    val ids: Set<BackupAccountId>,
    val candidates: Map<BackupAccountId, List<RemoteAccountCandidate>>,
    val invalidRecordCount: Int = 0,
)

/**
 * Secret run-scoped inputs for one consolidation operation.
 *
 * Carries the passphrase obtained from [VaultKeySession] so that restore and write can decrypt
 * or re-derive account material. This object is never persisted; the caller zeroes
 * [passphrase] when the run finishes.
 */
class VaultKeyRunSecrets(
    val vaultKey: Account,
    val passphrase: CharArray,
)

interface AccountBackupSource {
    val config: BackupSourceConfig
    suspend fun index(secrets: VaultKeyRunSecrets): Result<SourceIndex>
    suspend fun restoreCandidate(secrets: VaultKeyRunSecrets, candidate: RemoteAccountCandidate): Result<Account>
    suspend fun write(secrets: VaultKeyRunSecrets, accountId: BackupAccountId, account: Account): Result<Unit>
}

interface BackupSourceRunContext

interface BackupSourceFactory {
    /** Derives run-scoped credentials once; implementations must not persist this context. */
    fun createRunContext(secrets: VaultKeyRunSecrets): BackupSourceRunContext? = null
    fun create(config: BackupSourceConfig, context: BackupSourceRunContext? = null): AccountBackupSource
}

fun normalizeSourceEndpoint(type: BackupSourceType, raw: String): String {
    val uri = URI(raw.trim())
    require(uri.userInfo == null && uri.fragment == null && uri.query == null) { "Source URL cannot contain credentials, query, or fragment" }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("Source URL must include a scheme")
    val host = uri.host?.lowercase(Locale.ROOT) ?: error("Source URL must include a host")
    when (type) {
        BackupSourceType.NOSTR_RELAY -> require(scheme == "wss") { "Nostr sources must use wss" }
        BackupSourceType.BRAVE_SYNC -> require(scheme == "https") { "Brave Sync sources must use https" }
    }
    val port = when {
        uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "wss" && uri.port == 443) -> ""
        else -> ":${uri.port}"
    }
    val path = uri.path.orEmpty().trimEnd('/').takeUnless { it.isBlank() || it == "/" }.orEmpty()
    return "$scheme://$host$port$path"
}
