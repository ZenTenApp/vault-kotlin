package app.zenten.vault.bravesync.password

import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.proto.ProtoWire
import java.net.URI
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Builds the password entities that Brave creates for manually-added credentials. */
internal object PasswordEntityCodec {
    data class PasswordNote(val value: String, val hidden: Boolean = false)
    data class PasswordEntry(
        val id: String,
        val version: Long,
        val clientTagHash: String,
        val signonRealm: String,
        val origin: String,
        val username: String,
        val password: String,
        val notes: List<PasswordNote> = emptyList(),
    )
    data class NewPassword(val url: String, val username: String, val password: String, val note: PasswordNote? = null)

    fun create(input: NewPassword, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC(), id: String = UUID.randomUUID().toString()): ByteArray =
        entity(id, 0, input, keys, deleted = false, clock)

    fun update(existing: PasswordEntry, password: String, note: PasswordNote?, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC()): ByteArray =
        entity(existing.id, existing.version, NewPassword(existing.origin, existing.username, password, note), keys, deleted = false, clock)

    fun delete(existing: PasswordEntry, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC()): ByteArray =
        entity(existing.id, existing.version, NewPassword(existing.origin, existing.username, ""), keys, deleted = true, clock)

    private fun entity(id: String, version: Long, input: NewPassword, keys: NigoriCrypto.Keys, deleted: Boolean, clock: Clock): ByteArray {
        val (realm, origin) = urlComponents(input.url)
        val nowMillis = clock.millis()
        val nowWindowsMicros = nowMillis * 1_000 + BraveSyncConfig.WINDOWS_EPOCH_OFFSET_SECONDS * 1_000_000
        val payload = passwordData(realm, origin, input.username, input.password, input.note, nowWindowsMicros, deleted)
        val encrypted = encryptedData(payload, keys)
        val passwordSpecifics = ProtoWire.message {
            message(1, encrypted)
            if (!deleted) message(5, encryptedData(payload, keys))
        }
        val specifics = ProtoWire.message { message(BraveSyncConfig.PASSWORD_TYPE_ID, passwordSpecifics) }
        val uniqueTag = PasswordUniqueTag.compute(origin, "", input.username, "", realm)
        return ProtoWire.message {
            string(1, id)
            varint(4, version)
            varint(5, nowMillis)
            varint(6, nowMillis)
            string(7, realm)
            if (deleted) varint(18, 1)
            string(19, "")
            string(20, "")
            message(21, specifics)
            varint(22, 0)
            string(23, PasswordUniqueTag.clientTagHash(uniqueTag))
        }
    }

    private fun passwordData(realm: String, origin: String, username: String, password: String, note: PasswordNote?, nowWindowsMicros: Long, deleted: Boolean): ByteArray = ProtoWire.message {
        varint(1, 0) // SCHEME_HTML
        string(2, realm)
        string(3, origin)
        string(4, "")
        string(5, "")
        string(6, username)
        string(7, "")
        string(8, password)
        if (!deleted) {
            varint(11, nowWindowsMicros)
            varint(12, 0)
            varint(13, 3) // kManuallyAdded
            varint(14, 0)
            string(15, "")
            string(16, "")
            string(17, "")
            varint(18, 0)
            message(19, ProtoWire.message {})
            varint(20, nowWindowsMicros)
            note?.let { value ->
                message(22, ProtoWire.message {
                    message(1, ProtoWire.message {
                        string(1, "")
                        string(2, value.value)
                        varint(3, nowWindowsMicros)
                        varint(4, if (value.hidden) 1 else 0)
                    })
                })
            }
            string(23, "")
            string(24, "")
            varint(25, 0)
            varint(26, 0)
            string(27, "")
        } else {
            varint(12, 0)
            varint(13, 3)
        }
    }

    private fun encryptedData(plaintext: ByteArray, keys: NigoriCrypto.Keys): ByteArray = ProtoWire.message {
        string(1, NigoriCrypto.computeKeyName(keys))
        string(2, Base64.getEncoder().encodeToString(NigoriCrypto.encrypt(plaintext, keys)))
    }

    private fun urlComponents(url: String): Pair<String, String> {
        val uri = try { URI(url) } catch (e: Exception) { throw IllegalArgumentException("Invalid password URL", e) }
        require(uri.scheme == "https" || uri.scheme == "http") { "Password URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrEmpty()) { "Password URL must have a host" }
        val port = when {
            uri.port == -1 || uri.port == 80 || uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val realm = "${uri.scheme}://${uri.host}$port/"
        return realm to realm
    }
}
