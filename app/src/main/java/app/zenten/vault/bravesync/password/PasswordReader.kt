package app.zenten.vault.bravesync.password

import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.proto.ProtoWire
import app.zenten.vault.bravesync.proto.SyncMessages
import java.util.Base64

/** Decrypts password Sync entities, skipping malformed or unavailable records. */
internal object PasswordReader {
    data class ReadResult(val passwords: List<PasswordEntityCodec.PasswordEntry>, val decryptionFailures: Int)

    fun read(entities: List<SyncMessages.SyncEntity>?, keys: NigoriCrypto.Keys): ReadResult {
        val passwords = mutableListOf<PasswordEntityCodec.PasswordEntry>()
        var failures = 0
        entities.orEmpty().forEach { entity ->
            if (entity.deleted) return@forEach
            val encrypted = entity.specifics?.password?.encrypted ?: return@forEach
            try {
                val data = parseData(NigoriCrypto.decrypt(Base64.getDecoder().decode(encrypted.blob), keys))
                passwords += PasswordEntityCodec.PasswordEntry(
                    id = entity.id,
                    version = entity.version,
                    clientTagHash = entity.clientTagHash,
                    signonRealm = data.signonRealm,
                    origin = data.origin,
                    username = data.username,
                    password = data.password,
                    notes = data.notes,
                )
            } catch (_: Exception) {
                failures++
            }
        }
        return ReadResult(passwords, failures)
    }

    private data class Data(val signonRealm: String, val origin: String, val username: String, val password: String, val notes: List<PasswordEntityCodec.PasswordNote>)

    private fun parseData(payload: ByteArray): Data {
        val fields = ProtoWire.fields(payload)
        val notes = fields.bytes(22)?.let { notesPayload ->
            ProtoWire.fields(notesPayload).allBytes(1).map { note ->
                val noteFields = ProtoWire.fields(note)
                PasswordEntityCodec.PasswordNote(
                    value = noteFields.string(2).orEmpty(),
                    hidden = noteFields.varint(4) == 1L,
                )
            }
        }.orEmpty()
        return Data(
            signonRealm = fields.string(2).orEmpty(),
            origin = fields.string(3).orEmpty(),
            username = fields.string(6).orEmpty(),
            password = fields.string(8).orEmpty(),
            notes = notes,
        )
    }

    private fun List<ProtoWire.Field>.bytes(number: Int): ByteArray? = firstOrNull { it.number == number }?.bytes
    private fun List<ProtoWire.Field>.allBytes(number: Int): List<ByteArray> = filter { it.number == number }.mapNotNull { it.bytes }
    private fun List<ProtoWire.Field>.string(number: Int): String? = bytes(number)?.decodeToString()
    private fun List<ProtoWire.Field>.varint(number: Int): Long? = firstOrNull { it.number == number }?.varint
}
