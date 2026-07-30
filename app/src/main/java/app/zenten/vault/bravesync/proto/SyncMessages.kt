package app.zenten.vault.bravesync.proto

import app.zenten.vault.bravesync.BraveSyncConfig
import java.util.UUID

/** Password-only encoders/decoders for Chromium Sync protobuf messages. */
internal object SyncMessages {
    const val CONTENTS_COMMIT = 1L
    const val CONTENTS_GET_UPDATES = 2L
    const val GET_UPDATES_ORIGIN_TRIGGER = 12L

    data class NigoriSettings(
        val passphraseType: Int,
        val keyDerivationMethod: Int,
        val salt: ByteArray?,
        val encryptEverything: Boolean,
    )

    data class CommitResult(
        val errorCode: Int,
        val errorMessage: String?,
        val entryResults: List<CommitEntryResult>,
    )

    data class CommitEntryResult(
        val responseType: Int,
        val id: String?,
        val version: Long?,
        val errorMessage: String?,
    )

    data class SyncEntity(
        val id: String,
        val version: Long,
        val name: String,
        val clientTagHash: String,
        val deleted: Boolean,
        val specifics: EntitySpecifics?,
    )

    data class EntitySpecifics(val password: PasswordSpecifics? = null, val nigori: NigoriSpecifics? = null)
    data class PasswordSpecifics(val encrypted: EncryptedData?, val encryptedNotesBackup: EncryptedData?)
    data class NigoriSpecifics(
        val encryptionKeybag: EncryptedData?,
        val passphraseType: Int?,
        val keyDerivationMethod: Int?,
        val salt: String?,
        val encryptEverything: Boolean?,
    )
    data class EncryptedData(val keyName: String, val blob: String)

    fun getUpdatesRequest(dataTypeId: Int): ByteArray = clientToServer(
        contents = CONTENTS_GET_UPDATES,
        getUpdates = ProtoWire.message {
            varint(9, GET_UPDATES_ORIGIN_TRIGGER)
            message(6, ProtoWire.message {
                varint(1, dataTypeId.toLong())
                bytes(2, ByteArray(0))
            })
        },
    )

    fun commitRequest(entities: List<ByteArray>): ByteArray = clientToServer(
        contents = CONTENTS_COMMIT,
        commit = ProtoWire.message {
            entities.forEach { message(1, it) }
            string(2, UUID.randomUUID().toString())
        },
    )

    fun clientToServer(contents: Long, getUpdates: ByteArray? = null, commit: ByteArray? = null): ByteArray = ProtoWire.message {
        string(1, "")
        varint(3, contents)
        commit?.let { message(4, it) }
        getUpdates?.let { message(5, it) }
    }

    fun parseResponse(payload: ByteArray): Response = Response.from(ProtoWire.fields(payload))

    data class Response(val commit: CommitResult?, val getUpdates: List<SyncEntity>?, val errorCode: Int, val errorMessage: String?) {
        companion object {
            fun from(fields: List<ProtoWire.Field>): Response {
                val commit = fields.bytes(1)?.let(::parseCommit)
                val entries = fields.bytes(2)?.let(::parseGetUpdates)
                return Response(commit, entries, fields.varint(4)?.toInt() ?: 0, fields.string(5))
            }
        }
    }

    private fun parseCommit(bytes: ByteArray): CommitResult {
        val fields = ProtoWire.fields(bytes)
        return CommitResult(
            errorCode = 0,
            errorMessage = null,
            entryResults = fields.filter { it.number == 1 && it.group != null }.map(::parseCommitEntry),
        )
    }

    private fun parseCommitEntry(entry: ProtoWire.Field): CommitEntryResult {
        val fields = requireNotNull(entry.group)
        // EntryResponse is a proto2 group. Its member field numbers are encoded directly
        // in the enclosing CommitResponse, hence response_type=2, id=3, version=6.
        return CommitEntryResult(
            responseType = fields.varint(2)?.toInt() ?: 0,
            id = fields.string(3),
            version = fields.varint(6),
            errorMessage = fields.string(9),
        )
    }

    private fun parseGetUpdates(bytes: ByteArray): List<SyncEntity> =
        ProtoWire.fields(bytes).allBytes(1).map(::parseSyncEntity)

    private fun parseSyncEntity(bytes: ByteArray): SyncEntity {
        val fields = ProtoWire.fields(bytes)
        return SyncEntity(
            id = fields.string(1).orEmpty(),
            version = fields.varint(4) ?: 0,
            name = fields.string(7).orEmpty(),
            deleted = fields.varint(18) == 1L,
            clientTagHash = fields.string(23).orEmpty(),
            specifics = fields.bytes(21)?.let(::parseEntitySpecifics),
        )
    }

    private fun parseEntitySpecifics(bytes: ByteArray): EntitySpecifics {
        val fields = ProtoWire.fields(bytes)
        return EntitySpecifics(
            password = fields.bytes(BraveSyncConfig.PASSWORD_TYPE_ID)?.let(::parsePasswordSpecifics),
            nigori = fields.bytes(BraveSyncConfig.NIGORI_TYPE_ID)?.let(::parseNigoriSpecifics),
        )
    }

    private fun parsePasswordSpecifics(bytes: ByteArray): PasswordSpecifics {
        val fields = ProtoWire.fields(bytes)
        return PasswordSpecifics(
            encrypted = fields.bytes(1)?.let(::parseEncryptedData),
            encryptedNotesBackup = fields.bytes(5)?.let(::parseEncryptedData),
        )
    }

    private fun parseNigoriSpecifics(bytes: ByteArray): NigoriSpecifics {
        val fields = ProtoWire.fields(bytes)
        return NigoriSpecifics(
            encryptionKeybag = fields.bytes(1)?.let(::parseEncryptedData),
            passphraseType = fields.varint(30)?.toInt(),
            keyDerivationMethod = fields.varint(45)?.toInt(),
            salt = fields.string(46),
            encryptEverything = fields.varint(24)?.let { it != 0L },
        )
    }

    private fun parseEncryptedData(bytes: ByteArray): EncryptedData {
        val fields = ProtoWire.fields(bytes)
        return EncryptedData(fields.string(1).orEmpty(), fields.string(2).orEmpty())
    }

    fun nigoriSettings(response: Response): NigoriSettings? = response.getUpdates
        ?.firstOrNull { it.specifics?.nigori != null }
        ?.specifics?.nigori
        ?.let { nigori ->
            val method = nigori.keyDerivationMethod ?: BraveSyncConfig.KEY_DERIVATION_PBKDF2
            NigoriSettings(
                passphraseType = nigori.passphraseType ?: 0,
                keyDerivationMethod = method,
                salt = nigori.salt?.let { java.util.Base64.getDecoder().decode(it) },
                encryptEverything = nigori.encryptEverything ?: false,
            )
        }

    private fun List<ProtoWire.Field>.bytes(number: Int): ByteArray? = firstOrNull { it.number == number }?.bytes
    private fun List<ProtoWire.Field>.allBytes(number: Int): List<ByteArray> = filter { it.number == number }.mapNotNull { it.bytes }
    private fun List<ProtoWire.Field>.string(number: Int): String? = bytes(number)?.decodeToString()
    private fun List<ProtoWire.Field>.varint(number: Int): Long? = firstOrNull { it.number == number }?.varint
}
