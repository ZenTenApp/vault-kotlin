package app.zenten.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by an Android Keystore key.
 *
 * The key never leaves the Keystore. Each [encrypt] call lets the Keystore generate a fresh
 * GCM IV and stores it alongside the ciphertext; [decrypt] restores it. A version byte allows
 * the payload format to change without silently misreading older data.
 *
 * The key is deliberately not bound to user authentication. Unattended background backup and
 * signature-authorized KeyHive provider access must keep reading secure storage without a
 * biometric prompt. See docs/technical/security-model.md.
 */
class KeystoreCryptoService {
    /** Encrypts [plainText] and returns a Base64 payload of `version || ivLength || iv || ciphertext`. */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(HEADER_SIZE + iv.size + cipherText.size)
        payload[0] = PAYLOAD_VERSION.toByte()
        payload[1] = iv.size.toByte()
        System.arraycopy(iv, 0, payload, HEADER_SIZE, iv.size)
        System.arraycopy(cipherText, 0, payload, HEADER_SIZE + iv.size, cipherText.size)

        return Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Decrypts a payload produced by [encrypt].
     *
     * Returns null when the payload is malformed, was tampered with, or the Keystore key is no
     * longer usable. Callers treat null as "record unavailable" rather than as an error.
     */
    fun decrypt(encoded: String): String? = runCatching {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > HEADER_SIZE) { "Payload too short" }
        require(payload[0].toInt() == PAYLOAD_VERSION) { "Unsupported payload version" }

        val ivSize = payload[1].toInt() and 0xFF
        require(ivSize in MIN_IV_SIZE..MAX_IV_SIZE && payload.size > HEADER_SIZE + ivSize) {
            "Invalid IV length"
        }

        val iv = payload.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivSize)
        val cipherText = payload.copyOfRange(HEADER_SIZE + ivSize, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey() ?: return null, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    /** Loads the app's Keystore key, creating it on first use. */
    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        loadKey() ?: generateKey()
    }

    /** Reads the existing key without creating one, so a missing key never silently rotates. */
    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Creates a non-exportable AES-256-GCM key in the Android Keystore. */
    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vault_secure_storage_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val HEADER_SIZE = 2
        const val PAYLOAD_VERSION = 1
        const val MIN_IV_SIZE = 12
        const val MAX_IV_SIZE = 32

        /**
         * Guards first-use key generation across threads. The key is process-wide; the lock is
         * shared so two service instances cannot race to create different keys.
         */
        val KEY_LOCK = Any()
    }
}
