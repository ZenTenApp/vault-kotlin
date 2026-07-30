package app.zenten.vault.bravesync.auth

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/** Brave Sync authentication derivation from the first 24 words of a Sync code. */
object BraveSyncAuth {
    private const val MnemonicWordCount = 24
    private const val EntropySizeBytes = 32
    private val hkdfInfo = "sync-auth-key".toByteArray(StandardCharsets.UTF_8)

    // brave-core/components/sync/service/brave_sync_auth_manager.cc
    private val hkdfSalt = byteArrayOf(
        72, -53, -100, 43, 64, -27, -31, 127, -42, -98, 50, 29, -126,
        -70, -74, -49, 6, 108, 47, -2, -11, 71, -58, 109, 44, 108,
        32, -63, -35, 126, 119, -113, 112, 113, 87, -72, -17, -25, -26,
        -22, 28, -121, 54, 42, 9, -13, 39, 30, -77, -109, -62, -45,
        -44, -17, -31, 52, -64, -37, -111, 40, 95, 19, -114, 98,
    )

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun first24Words(syncCode: String): String {
        val words = syncCode.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        require(words.size == 24 || words.size == 25) { "Expected 24 or 25 Brave Sync words, got ${words.size}" }
        return words.take(MnemonicWordCount).joinToString(" ")
    }

    /** Decodes BIP-39's 24-word data bits, rather than deriving its 64-byte BIP-39 seed. */
    fun mnemonicEntropy(syncCode: String): ByteArray {
        val words = first24Words(syncCode).split(" ")
        Bip39Mnemonics.validate(words)
        val indexes = words.map { word ->
            Bip39Mnemonics.englishWordlist.indexOf(word).also { require(it >= 0) }
        }
        val output = ByteArray(EntropySizeBytes)
        var bitOffset = 0
        indexes.forEach { index ->
            for (shift in 10 downTo 0) {
                if ((index and (1 shl shift)) != 0 && bitOffset < EntropySizeBytes * 8) {
                    output[bitOffset / 8] = (output[bitOffset / 8].toInt() or (1 shl (7 - bitOffset % 8))).toByte()
                }
                bitOffset++
            }
        }
        return output
    }

    fun deriveKeyPair(entropy: ByteArray): KeyPair {
        require(entropy.size == EntropySizeBytes) { "Brave Sync entropy must be 32 bytes" }
        val privateKey = hkdfSha512(entropy, hkdfSalt, hkdfInfo, 32)
        val publicKey = Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        return KeyPair(privateKey, publicKey)
    }

    fun clientId(publicKey: ByteArray): String = publicKey.toLowerHex()

    /** Base64(timestamp_hex|SIGNATURE_HEX|PUBLIC_KEY_HEX), exactly as Brave expects. */
    fun generateAuthToken(keyPair: KeyPair, timestampMillis: Long): String {
        val timestampBytes = timestampMillis.toString().toByteArray(StandardCharsets.UTF_8)
        val signature = ByteArray(64)
        Ed25519.sign(keyPair.privateKey, 0, timestampBytes, 0, timestampBytes.size, signature, 0)
        val token = "${timestampBytes.toLowerHex()}|${signature.toUpperHex()}|${keyPair.publicKey.toUpperHex()}"
        return Base64.getEncoder().encodeToString(token.toByteArray(StandardCharsets.US_ASCII))
    }

    /** RFC 5869 extract + expand; explicit to keep the Brave parameters auditable. */
    private fun hkdfSha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha512(salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha512(prk, previous + info + byteArrayOf(counter.toByte()))
            val copied = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, copied)
            offset += copied
            counter++
        }
        prk.fill(0)
        previous.fill(0)
        return output
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA512").run {
        init(SecretKeySpec(key, "HmacSHA512"))
        doFinal(data)
    }

    private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun ByteArray.toUpperHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }
}
