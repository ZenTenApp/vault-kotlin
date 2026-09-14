package app.zenten.vault.crypto

import org.apache.sshd.common.config.keys.loader.openssh.kdf.BCrypt
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** OpenSSH-v1 Ed25519 codec that exposes the seed deliberately hidden by JCA key APIs. */
object SshKeyCodec {
    private val magic = "openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private const val Ed25519 = "ssh-ed25519"
    private const val Aes256Ctr = "aes256-ctr"
    private const val Bcrypt = "bcrypt"

    /** OpenSSH bcrypt_pbkdf work factor for generated private keys. */
    const val BCRYPT_PBKDF_ROUNDS = 128

    data class ParsedKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val comment: String,
        val encrypted: Boolean,
    )

    data class GeneratedKey(
        val privateKeyPem: String,
        val publicKey: String,
        val createdAt: Long,
    )

    /** Parses only passphrase-protected OpenSSH-v1 Ed25519 private keys. */
    fun parse(privateKeyPem: String, passphrase: CharArray): ParsedKey {
        val container = decodePem(privateKeyPem)
        require(container.take(magic.size).toByteArray().contentEquals(magic)) { "Not an OpenSSH v1 private key" }
        val reader = SshReader(container, magic.size)
        val cipherName = reader.stringAscii()
        val kdfName = reader.stringAscii()
        val kdfOptions = reader.bytes()
        require(reader.uInt() == 1L) { "Only one OpenSSH key is supported" }
        val outerPublic = readPublicBlob(reader.bytes())
        val encryptedPrivate = reader.bytes()
        require(reader.atEnd()) { "Trailing OpenSSH container data" }

        require(cipherName == Aes256Ctr) { "Unencrypted OpenSSH private keys are not supported" }
        val privateBlob = decryptBcrypt(encryptedPrivate, kdfName, kdfOptions, passphrase)
        return parsePrivateBlob(privateBlob, outerPublic, encrypted = true)
    }

    /** Converts PEM, raw base64, or {"OPENSSH PRIVATE KEY":"..."} input to canonical single-line base64. */
    fun normalizeToSingleLineBase64(input: String): String = Base64.getEncoder().encodeToString(decodePem(input))

    /** Formats a stored canonical key for OpenSSH-compatible export. */
    fun formatAsPem(input: String): String = pemEncode(decodePem(input))

    fun publicKeyLine(publicKey: ByteArray, comment: String): String {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val blob = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(publicKey)
        return "$Ed25519 ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    /** Fingerprint the SSH public-key wire blob, as ssh-keygen -E sha256 does. */
    fun fingerprint(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val wire = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(publicKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }

    /** Generates an OpenSSH-v1 Ed25519 key encrypted with aes256-ctr and bcrypt_pbkdf. */
    fun generateEncrypted(createdAt: Long, passphrase: CharArray, random: SecureRandom = SecureRandom()): GeneratedKey {
        require(passphrase.isNotEmpty()) { "A passphrase is required to encrypt an SSH key" }
        val private = Ed25519PrivateKeyParameters(random)
        val seed = private.encoded
        val public = private.generatePublicKey().encoded
        val publicBlob = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(public)
        val comment = createdAt.toString()
        val check = ByteArray(4).also(random::nextBytes)
        val privateBlob = check + check +
            sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(public) + sshString(seed + public) +
            sshString(comment.toByteArray(StandardCharsets.UTF_8))
        val salt = ByteArray(16).also(random::nextBytes)
        val rounds = BCRYPT_PBKDF_ROUNDS
        val options = sshString(salt) + uint(rounds)
        val containerFields = OpenSshEncryption(
            Aes256Ctr.toByteArray(),
            Bcrypt.toByteArray(),
            options,
            encryptBcrypt(pad(privateBlob, 16), salt, rounds, passphrase),
        )
        val container = magic + sshString(containerFields.cipherName) + sshString(containerFields.kdfName) + sshString(containerFields.kdfOptions) +
            uint(1) + sshString(publicBlob) + sshString(containerFields.privateSection)
        return GeneratedKey(pemEncode(container), publicKeyLine(public, comment), createdAt)
    }

    private data class OpenSshEncryption(
        val cipherName: ByteArray,
        val kdfName: ByteArray,
        val kdfOptions: ByteArray,
        val privateSection: ByteArray,
    )

    private fun encryptBcrypt(plaintext: ByteArray, salt: ByteArray, rounds: Int, passphrase: CharArray): ByteArray =
        cryptBcrypt(Cipher.ENCRYPT_MODE, plaintext, salt, rounds, passphrase)

    private fun decryptBcrypt(ciphertext: ByteArray, kdfName: String, options: ByteArray, passphrase: CharArray): ByteArray {
        require(kdfName == Bcrypt) { "Unsupported OpenSSH KDF: $kdfName" }
        val optionsReader = SshReader(options)
        val salt = optionsReader.bytes()
        val rounds = optionsReader.uInt().toInt()
        require(rounds > 0 && optionsReader.atEnd()) { "Invalid bcrypt KDF options" }
        return cryptBcrypt(Cipher.DECRYPT_MODE, ciphertext, salt, rounds, passphrase)
    }

    private fun cryptBcrypt(mode: Int, input: ByteArray, salt: ByteArray, rounds: Int, passphrase: CharArray): ByteArray {
        val derived = ByteArray(48)
        BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)
        try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(mode, SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(derived.copyOfRange(32, 48)))
            return cipher.doFinal(input)
        } finally {
            derived.fill(0)
        }
    }

    private fun parsePrivateBlob(blob: ByteArray, outerPublic: ByteArray, encrypted: Boolean): ParsedKey {
        val reader = SshReader(blob)
        val check1 = reader.uInt()
        require(check1 == reader.uInt()) { "Incorrect passphrase or corrupt OpenSSH key" }
        require(reader.stringAscii() == Ed25519) { "Only ssh-ed25519 keys are supported" }
        val public = reader.bytes()
        require(public.size == 32 && public.contentEquals(outerPublic)) { "OpenSSH public-key mismatch" }
        val privateAndPublic = reader.bytes()
        require(privateAndPublic.size == 64 && privateAndPublic.copyOfRange(32, 64).contentEquals(public)) { "Invalid Ed25519 private key" }
        val comment = reader.stringUtf8()
        verifyPadding(reader.remaining())
        return ParsedKey(privateAndPublic.copyOfRange(0, 32), public, comment, encrypted)
    }

    private fun readPublicBlob(blob: ByteArray): ByteArray {
        val reader = SshReader(blob)
        require(reader.stringAscii() == Ed25519) { "Only ssh-ed25519 keys are supported" }
        val public = reader.bytes()
        require(public.size == 32 && reader.atEnd()) { "Invalid Ed25519 public key" }
        return public
    }

    private fun verifyPadding(padding: ByteArray) {
        padding.forEachIndexed { index, byte -> require(byte.toInt() and 0xff == index + 1) { "Invalid OpenSSH private-key padding" } }
    }

    private fun pad(input: ByteArray, blockSize: Int): ByteArray {
        val count = blockSize - (input.size % blockSize)
        return input + ByteArray(count) { (it + 1).toByte() }
    }

    private fun decodePem(input: String): ByteArray {
        val trimmed = input.trim()
        val jsonValue = if (trimmed.startsWith("{")) {
            Regex("\\\"OPENSSH PRIVATE KEY\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(trimmed)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Invalid OpenSSH key JSON")
        } else trimmed
        val body = jsonValue.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
        require(body.isNotEmpty()) { "Empty OpenSSH private key" }
        return try { Base64.getDecoder().decode(body) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid OpenSSH PEM encoding", e) }
    }

    private fun pemEncode(bytes: ByteArray): String {
        val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(bytes)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----"
    }

    private fun uint(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun sshString(value: ByteArray): ByteArray = uint(value.size) + value

    private class SshReader(private val data: ByteArray, initialOffset: Int = 0) {
        private var offset = initialOffset
        fun uInt(): Long {
            require(offset + 4 <= data.size) { "Truncated OpenSSH data" }
            val result = ((data[offset].toLong() and 0xff) shl 24) or ((data[offset + 1].toLong() and 0xff) shl 16) or
                ((data[offset + 2].toLong() and 0xff) shl 8) or (data[offset + 3].toLong() and 0xff)
            offset += 4
            return result
        }
        fun bytes(): ByteArray {
            val size = uInt().toInt()
            require(size >= 0 && offset + size <= data.size) { "Truncated OpenSSH string" }
            return data.copyOfRange(offset, offset + size).also { offset += size }
        }
        fun stringAscii(): String = bytes().toString(StandardCharsets.US_ASCII)
        fun stringUtf8(): String = bytes().toString(StandardCharsets.UTF_8)
        fun remaining(): ByteArray = data.copyOfRange(offset, data.size).also { offset = data.size }
        fun atEnd(): Boolean = offset == data.size
    }
}
