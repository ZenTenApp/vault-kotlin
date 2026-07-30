package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.auth.BraveSyncAuth
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.password.PasswordUniqueTag
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Phase-one compatibility vectors generated from ../brave-sync-ts source.
 * These cover the password-only crypto/identity primitives before protobuf and network work.
 */
class BraveSyncCryptoParityTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

    @Test
    fun mnemonic_entropy_and_auth_keypair_match_typescript() {
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", BraveSyncAuth.mnemonicEntropy(mnemonic).hex())
        assertArrayEquals(BraveSyncAuth.mnemonicEntropy(mnemonic), BraveSyncAuth.mnemonicEntropy("$mnemonic abandon"))

        val keyPair = BraveSyncAuth.deriveKeyPair(BraveSyncAuth.mnemonicEntropy(mnemonic))
        assertEquals("f74ee4b0101e5295cbd9a7eba061c0dc615c0b7c4ccf6521bb9637711dad170a", keyPair.privateKey.hex())
        assertEquals("1d0e86b00a7c62a534846a0a30730c20b7037ea87d80fe1efb773538726c77e1", keyPair.publicKey.hex())
        assertEquals("1d0e86b00a7c62a534846a0a30730c20b7037ea87d80fe1efb773538726c77e1", BraveSyncAuth.clientId(keyPair.publicKey))
    }

    @Test
    fun invalid_sync_codes_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { BraveSyncAuth.first24Words("one two") }
        assertThrows(IllegalArgumentException::class.java) { BraveSyncAuth.mnemonicEntropy("abandon ".repeat(24).trim()) }
    }

    @Test
    fun pbkdf2_and_scrypt_nigori_keys_match_typescript() {
        val pbkdf2 = NigoriCrypto.derivePbkdf2Keys(mnemonic)
        assertEquals("46692dfa158fd470200805d2e0c40ec2", pbkdf2.encryptionKey.hex())
        assertEquals("20b1518638f44a12301dafe413cdb979", pbkdf2.macKey.hex())
        assertEquals("Bejhs5OIS89f/bKR2ZfaMHtEglr5tJcE4+eBUJoRi5jlRP3xaLqOCse+QG4qRRmDoXB9/dlLrGmwpzutDJ1DTA==", NigoriCrypto.computeKeyName(pbkdf2))

        val scrypt = NigoriCrypto.deriveScryptKeys(mnemonic, ByteArray(32) { 1 })
        assertEquals("b49bd536b43d848641a8c34009387da2", scrypt.encryptionKey.hex())
        assertEquals("e2a22dfaacc028be7de22376269fea74", scrypt.macKey.hex())
    }

    @Test
    fun nigori_encrypt_decrypt_and_password_identity_are_compatible() {
        val keys = NigoriCrypto.derivePbkdf2Keys(mnemonic)
        val plaintext = "password protobuf fixture".encodeToByteArray()
        val encrypted = NigoriCrypto.encryptWithIv(plaintext, keys, ByteArray(16) { it.toByte() })
        assertArrayEquals(plaintext, NigoriCrypto.decrypt(encrypted, keys))

        val tag = PasswordUniqueTag.compute("https://example.com/", "", "user@example.com", "", "https://example.com/")
        assertEquals("https%3A//example.com/||user@example.com||https%3A//example.com/", tag)
        assertEquals("YpNMNAln7pk4+KKjHl9wfzz0jlM=", PasswordUniqueTag.clientTagHash(tag))
        assertEquals("hello%20world%23%C3%A9", PasswordUniqueTag.escapePath("hello world#é"))
    }

    @Test
    fun auth_token_uses_brave_wire_format() {
        val pair = BraveSyncAuth.deriveKeyPair(BraveSyncAuth.mnemonicEntropy(mnemonic))
        val decoded = String(Base64.getDecoder().decode(BraveSyncAuth.generateAuthToken(pair, 1_700_000_000_000)))
        val fields = decoded.split("|")
        assertEquals(3, fields.size)
        assertEquals("1700000000000".encodeToByteArray().hex(), fields[0])
        assertEquals(128, fields[1].length)
        assertEquals(pair.publicKey.hex().uppercase(), fields[2])
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
