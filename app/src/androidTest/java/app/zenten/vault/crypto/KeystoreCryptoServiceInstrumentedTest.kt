package app.zenten.vault.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an Android runtime because the key lives in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoServiceInstrumentedTest {
    private val crypto = KeystoreCryptoService()

    @Test
    fun round_trip_returns_plaintext() {
        val plain = "vault-secret-\u2713"
        val encrypted = crypto.encrypt(plain)

        assertNotEquals(plain, encrypted)
        assertEquals(plain, crypto.decrypt(encrypted))
    }

    @Test
    fun fresh_iv_produces_different_ciphertext_for_same_plaintext() {
        val plain = "same-plaintext"
        val first = crypto.encrypt(plain)
        val second = crypto.encrypt(plain)

        assertNotEquals(first, second)
        assertEquals(plain, crypto.decrypt(first))
        assertEquals(plain, crypto.decrypt(second))
    }

    @Test
    fun tampered_payload_returns_null() {
        val encrypted = crypto.encrypt("tamper-check")
        val bytes = java.util.Base64.getDecoder().decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertNull(crypto.decrypt(tampered))
    }

    @Test
    fun malformed_payload_returns_null() {
        assertNull(crypto.decrypt("not-base64"))
        assertNull(crypto.decrypt(""))
    }
}
