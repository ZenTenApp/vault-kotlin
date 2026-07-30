package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.password.PasswordEntityCodec
import app.zenten.vault.bravesync.proto.ProtoWire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PasswordEntityCodecTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)

    @Test
    fun creates_brave_compatible_manually_added_password_entity() {
        val entity = PasswordEntityCodec.create(
            PasswordEntityCodec.NewPassword("https://example.com/login", "user@example.com", "secret", PasswordEntityCodec.PasswordNote("memo")),
            NigoriCrypto.derivePbkdf2Keys(mnemonic),
            clock,
            "00000000-0000-4000-8000-000000000001",
        )
        val fields = ProtoWire.fields(entity)
        assertEquals("00000000-0000-4000-8000-000000000001", fields.first { it.number == 1 }.bytes!!.decodeToString())
        assertEquals(0, fields.first { it.number == 4 }.varint)
        assertEquals("https://example.com/", fields.first { it.number == 7 }.bytes!!.decodeToString())
        assertEquals("YpNMNAln7pk4+KKjHl9wfzz0jlM=", fields.first { it.number == 23 }.bytes!!.decodeToString())

        val specifics = ProtoWire.fields(fields.first { it.number == 21 }.bytes!!)
        val password = ProtoWire.fields(specifics.first { it.number == BraveSyncConfig.PASSWORD_TYPE_ID }.bytes!!)
        assertNotNull(password.firstOrNull { it.number == 1 })
        assertNotNull(password.firstOrNull { it.number == 5 })
    }

    @Test
    fun deletion_is_a_tombstone_with_encrypted_specifics() {
        val entry = PasswordEntityCodec.PasswordEntry("id", 7, "tag", "https://example.com/", "https://example.com/", "user", "secret")
        val fields = ProtoWire.fields(PasswordEntityCodec.delete(entry, NigoriCrypto.derivePbkdf2Keys(mnemonic), clock))
        assertEquals(1, fields.first { it.number == 18 }.varint)
        val specifics = ProtoWire.fields(fields.first { it.number == 21 }.bytes!!)
        val password = ProtoWire.fields(specifics.first { it.number == BraveSyncConfig.PASSWORD_TYPE_ID }.bytes!!)
        assertTrue(password.any { it.number == 1 })
        assertTrue(password.none { it.number == 5 })
    }
}
