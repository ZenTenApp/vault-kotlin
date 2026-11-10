package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.auth.BraveSyncAuth
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.password.PasswordEntityCodec
import app.zenten.vault.bravesync.password.PasswordReader
import app.zenten.vault.bravesync.proto.SyncMessages
import app.zenten.vault.bravesync.transport.BraveSyncTransport

/** App-facing, password-only Brave Sync API. Instances retain keys only in memory. */
class BravePasswordService(
    private val transport: BraveSyncTransport,
    private val cacheSession: Boolean = false,
) {

    data class Note(val value: String, val hidden: Boolean = false)
    data class Password(val id: String, val version: Long, val clientTagHash: String, val url: String, val username: String, val password: String, val notes: List<Note>)
    data class ListResult(val passwords: List<Password>, val decryptionFailures: Int)

    fun list(syncCode: String): ListResult = withSession(syncCode) { session ->
        val response = request(session, SyncMessages.getUpdatesRequest(BraveSyncConfig.PASSWORD_TYPE_ID))
        val result = PasswordReader.read(response.getUpdates, session.keys)
        ListResult(result.passwords.map { it.toPublic() }, result.decryptionFailures)
    }

    fun create(syncCode: String, url: String, username: String, password: String, note: String? = null): Password = withSession(syncCode) { session ->
        commitNewPassword(session, url, username, password, note)
        // The server provides the authoritative entity ID/version; refetch avoids relying on
        // commit-response details that vary across Sync server versions.
        list(syncCode).passwords.firstOrNull { it.url == realm(url) && it.username == username }
            ?: throw IllegalStateException("Brave Sync accepted the password commit but did not return the new entry")
    }

    /** Commits a record when a source index already proved it absent, avoiding another full list. */
    fun createKnownMissing(syncCode: String, url: String, username: String, password: String, note: String? = null) =
        withSession(syncCode) { session -> commitNewPassword(session, url, username, password, note) }

    fun update(syncCode: String, existing: Password, password: String, note: String? = null): Password = withSession(syncCode) { session ->
        val entity = PasswordEntityCodec.update(existing.toInternal(), password, note?.let(PasswordEntityCodec::PasswordNote), session.keys)
        commit(session, entity)
        list(syncCode).passwords.firstOrNull { it.id == existing.id }
            ?: throw IllegalStateException("Brave Sync accepted the password update but did not return the entry")
    }

    fun delete(syncCode: String, existing: Password) = withSession(syncCode) { session ->
        commit(session, PasswordEntityCodec.delete(existing.toInternal(), session.keys))
    }

    private data class Session(val clientId: String, val token: String, val keys: NigoriCrypto.Keys)

    private var cachedSession: Session? = null

    private fun <T> withSession(syncCode: String, block: (Session) -> T): T {
        val session = cachedSession ?: run {
            val keyPair = BraveSyncAuth.deriveKeyPair(BraveSyncAuth.mnemonicEntropy(syncCode))
            Session(
                BraveSyncAuth.clientId(keyPair.publicKey),
                BraveSyncAuth.generateAuthToken(keyPair, System.currentTimeMillis()),
                resolveKeys(syncCode, keyPair),
            ).also { if (cacheSession) cachedSession = it }
        }
        return block(session)
    }

    private fun resolveKeys(syncCode: String, pair: BraveSyncAuth.KeyPair): NigoriCrypto.Keys {
        val clientId = BraveSyncAuth.clientId(pair.publicKey)
        val token = BraveSyncAuth.generateAuthToken(pair, System.currentTimeMillis())
        val response = request(Session(clientId, token, NigoriCrypto.derivePbkdf2Keys(syncCode)), SyncMessages.getUpdatesRequest(BraveSyncConfig.NIGORI_TYPE_ID))
        val settings = SyncMessages.nigoriSettings(response)
        if (settings != null) return NigoriCrypto.deriveKeys(syncCode, settings.keyDerivationMethod, settings.salt)
        val keys = NigoriCrypto.derivePbkdf2Keys(syncCode)
        commit(Session(clientId, token, keys), NigoriInitializer.entity(syncCode))
        return keys
    }

    private fun commitNewPassword(
        session: Session,
        url: String,
        username: String,
        password: String,
        note: String?,
    ) {
        val entity = PasswordEntityCodec.create(
            PasswordEntityCodec.NewPassword(url, username, password, note?.let(PasswordEntityCodec::PasswordNote)),
            session.keys,
        )
        commit(session, entity)
    }

    private fun request(session: Session, payload: ByteArray): SyncMessages.Response {
        val response = SyncMessages.parseResponse(transport.post(session.clientId, session.token, payload))
        require(response.errorCode == 0) { "Brave Sync server error ${response.errorCode}: ${response.errorMessage.orEmpty()}" }
        return response
    }

    private fun commit(session: Session, entity: ByteArray) {
        val response = request(session, SyncMessages.commitRequest(listOf(entity)))
        response.commit?.entryResults?.forEach {
            require(it.responseType == 1) { "Brave Sync commit failed (${it.responseType}): ${it.errorMessage.orEmpty()}" }
        }
    }

    private fun PasswordEntityCodec.PasswordEntry.toPublic() = Password(id, version, clientTagHash, origin, username, password, notes.map { Note(it.value, it.hidden) })
    private fun Password.toInternal() = PasswordEntityCodec.PasswordEntry(id, version, clientTagHash, realm(url), url, username, password, notes.map { PasswordEntityCodec.PasswordNote(it.value, it.hidden) })
    private fun realm(url: String): String = java.net.URI(url).let { "${it.scheme}://${it.host}${if (it.port == -1 || it.port == 80 || it.port == 443) "" else ":${it.port}"}/" }
}
