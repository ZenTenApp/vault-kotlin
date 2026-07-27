package com.zenten.vault.crypto

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

    fun parse(privateKeyPem: String, passphrase: CharArray? = null): ParsedKey {
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

        val privateBlob = when (cipherName) {
            "none" -> {
                require(kdfName == "none" && kdfOptions.isEmpty()) { "Invalid unencrypted OpenSSH KDF" }
                encryptedPrivate
            }
            Aes256Ctr -> decryptBcrypt(encryptedPrivate, kdfName, kdfOptions, requireNotNull(passphrase) { "Passphrase required" })
            else -> throw IllegalArgumentException("Unsupported OpenSSH cipher: $cipherName")
        }
        return parsePrivateBlob(privateBlob, outerPublic, cipherName != "none")
    }

    /** Fingerprint the SSH public-key wire blob, as ssh-keygen -E sha256 does. */
    fun fingerprint(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val wire = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(publicKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }

    fun generateUnencrypted(createdAt: Long, random: SecureRandom = SecureRandom()): GeneratedKey {
        val private = Ed25519PrivateKeyParameters(random)
        val seed = private.encoded
        val public = private.generatePublicKey().encoded
        val publicBlob = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(public)
        val comment = createdAt.toString()
        val check = ByteArray(4).also(random::nextBytes)
        val privateBlob = check + check +
            sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(public) + sshString(seed + public) +
            sshString(comment.toByteArray(StandardCharsets.UTF_8))
        val padded = pad(privateBlob, 8)
        val container = magic + sshString("none".toByteArray()) + sshString("none".toByteArray()) + sshString(ByteArray(0)) +
            uint(1) + sshString(publicBlob) + sshString(padded)
        val pem = pemEncode(container)
        val publicLine = "$Ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} $comment"
        return GeneratedKey(pem, publicLine, createdAt)
    }

    private fun decryptBcrypt(ciphertext: ByteArray, kdfName: String, options: ByteArray, passphrase: CharArray): ByteArray {
        require(kdfName == Bcrypt) { "Unsupported OpenSSH KDF: $kdfName" }
        val optionsReader = SshReader(options)
        val salt = optionsReader.bytes()
        val rounds = optionsReader.uInt().toInt()
        require(rounds > 0 && optionsReader.atEnd()) { "Invalid bcrypt KDF options" }
        val derived = ByteArray(48)
        BCrypt().pbkdf(String(passphrase).toByteArray(StandardCharsets.UTF_8), salt, rounds, derived)
        try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(derived.copyOfRange(32, 48)))
            return cipher.doFinal(ciphertext)
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

    private fun decodePem(pem: String): ByteArray {
        val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
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
