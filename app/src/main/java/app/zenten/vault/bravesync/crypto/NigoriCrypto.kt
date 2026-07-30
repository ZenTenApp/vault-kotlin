package app.zenten.vault.bravesync.crypto

import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.auth.BraveSyncAuth
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Chromium Nigori crypto used to protect Brave Sync password protobufs. */
object NigoriCrypto {
    private const val BlockSize = 16
    private const val MacSize = 32
    private const val ScryptN = 8192
    private const val ScryptR = 8
    private const val ScryptP = 11
    private val pbkdf2Salt = byteArrayOf(
        -57, -54, -5, 35, -20, 42, -99, 76,
        3, 90, -112, -82, -19, -117, -92, -104,
    )

    data class Keys(val encryptionKey: ByteArray, val macKey: ByteArray)

    fun deriveKeys(syncCode: String, method: Int, salt: ByteArray? = null): Keys = when (method) {
        BraveSyncConfig.KEY_DERIVATION_PBKDF2 -> derivePbkdf2Keys(syncCode)
        BraveSyncConfig.KEY_DERIVATION_SCRYPT -> deriveScryptKeys(syncCode, requireNotNull(salt) { "Scrypt Nigori settings require a salt" })
        else -> throw IllegalArgumentException("Unsupported Nigori key derivation method: $method")
    }

    fun derivePbkdf2Keys(syncCode: String): Keys = Keys(
        encryptionKey = pbkdf2Sha1(BraveSyncAuth.first24Words(syncCode).encodeToByteArray(), pbkdf2Salt, 1003, 16),
        macKey = pbkdf2Sha1(BraveSyncAuth.first24Words(syncCode).encodeToByteArray(), pbkdf2Salt, 1004, 16),
    )

    fun derivePbkdf2UserKey(syncCode: String): ByteArray =
        pbkdf2Sha1(BraveSyncAuth.first24Words(syncCode).encodeToByteArray(), pbkdf2Salt, 1002, 16)

    fun deriveScryptKeys(syncCode: String, salt: ByteArray): Keys {
        val derived = SCrypt.generate(
            BraveSyncAuth.first24Words(syncCode).encodeToByteArray(), salt, ScryptN, ScryptR, ScryptP, 32,
        )
        return Keys(derived.copyOfRange(0, 16), derived.copyOfRange(16, 32))
    }

    /** Returns IV || AES-CBC ciphertext || HMAC-SHA256(ciphertext). */
    fun encrypt(plaintext: ByteArray, keys: Keys, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(BlockSize).also(random::nextBytes)
        return encryptWithIv(plaintext, keys, iv)
    }

    internal fun encryptWithIv(plaintext: ByteArray, keys: Keys, iv: ByteArray): ByteArray {
        require(iv.size == BlockSize) { "Nigori IV must be 16 bytes" }
        val ciphertext = aesCbc(true, plaintext, keys.encryptionKey, iv)
        val mac = hmacSha256(keys.macKey, ciphertext)
        return iv + ciphertext + mac
    }

    fun decrypt(encrypted: ByteArray, keys: Keys): ByteArray {
        require(encrypted.size >= BlockSize + MacSize) { "Nigori ciphertext is too short" }
        val iv = encrypted.copyOfRange(0, BlockSize)
        val ciphertext = encrypted.copyOfRange(BlockSize, encrypted.size - MacSize)
        val mac = encrypted.copyOfRange(encrypted.size - MacSize, encrypted.size)
        require(MessageDigest.isEqual(mac, hmacSha256(keys.macKey, ciphertext))) { "Nigori MAC verification failed" }
        return aesCbc(false, ciphertext, keys.encryptionKey, iv)
    }

    fun computeKeyName(keys: Keys): String {
        val nigoriStream = byteArrayOf(0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0, 10) + "nigori-key".encodeToByteArray()
        val ciphertext = aesCbc(true, nigoriStream, keys.encryptionKey, ByteArray(BlockSize))
        return Base64.getEncoder().encodeToString(ciphertext + hmacSha256(keys.macKey, ciphertext))
    }

    private fun pbkdf2Sha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        PKCS5S2ParametersGenerator(SHA1Digest()).run {
            init(password, salt, iterations)
            (generateDerivedParameters(length * 8) as KeyParameter).key
        }

    private fun aesCbc(forEncryption: Boolean, input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(AESEngine.newInstance()))
        cipher.init(forEncryption, ParametersWithIV(KeyParameter(key), iv))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val processed = cipher.processBytes(input, 0, input.size, output, 0)
        val length = processed + cipher.doFinal(output, processed)
        return output.copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(MacSize).also { output ->
        HMac(SHA256Digest()).run {
            init(KeyParameter(key))
            update(data, 0, data.size)
            doFinal(output, 0)
        }
    }
}
