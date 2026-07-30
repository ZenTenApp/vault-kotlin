package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.proto.ProtoWire
import app.zenten.vault.bravesync.proto.SyncMessages
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtoWireTest {
    @Test
    fun encodes_and_decodes_varint_and_length_delimited_fields() {
        val payload = ProtoWire.message {
            varint(1, 300)
            string(2, "Brave")
            bytes(3, byteArrayOf(0, 1, 2))
        }
        val fields = ProtoWire.fields(payload)
        assertEquals(300, fields[0].varint)
        assertEquals("Brave", fields[1].bytes!!.decodeToString())
        assertArrayEquals(byteArrayOf(0, 1, 2), fields[2].bytes)
    }

    @Test
    fun builds_get_updates_request_with_password_type_and_empty_progress_token() {
        val root = ProtoWire.fields(SyncMessages.getUpdatesRequest(BraveSyncConfig.PASSWORD_TYPE_ID))
        assertEquals("", root.first { it.number == 1 }.bytes!!.decodeToString())
        assertEquals(2, root.first { it.number == 3 }.varint)
        val getUpdates = ProtoWire.fields(root.first { it.number == 5 }.bytes!!)
        assertEquals(12, getUpdates.first { it.number == 9 }.varint)
        val marker = ProtoWire.fields(getUpdates.first { it.number == 6 }.bytes!!)
        assertEquals(BraveSyncConfig.PASSWORD_TYPE_ID.toLong(), marker.first { it.number == 1 }.varint)
        assertArrayEquals(ByteArray(0), marker.first { it.number == 2 }.bytes)
    }

    @Test
    fun parses_nigori_settings_from_sync_response() {
        val nigori = ProtoWire.message {
            varint(24, 1)
            varint(30, 4)
            varint(45, BraveSyncConfig.KEY_DERIVATION_SCRYPT.toLong())
            string(46, "AQID")
        }
        val specifics = ProtoWire.message { message(BraveSyncConfig.NIGORI_TYPE_ID, nigori) }
        val entity = ProtoWire.message {
            string(1, "nigori")
            message(21, specifics)
        }
        val updates = ProtoWire.message { message(1, entity) }
        val response = ProtoWire.message { message(2, updates) }

        val settings = requireNotNull(SyncMessages.nigoriSettings(SyncMessages.parseResponse(response)))
        assertEquals(4, settings.passphraseType)
        assertEquals(BraveSyncConfig.KEY_DERIVATION_SCRYPT, settings.keyDerivationMethod)
        assertArrayEquals(byteArrayOf(1, 2, 3), settings.salt)
        assertEquals(true, settings.encryptEverything)
        assertNull(SyncMessages.parseResponse(ProtoWire.message {}).getUpdates)
    }
}
