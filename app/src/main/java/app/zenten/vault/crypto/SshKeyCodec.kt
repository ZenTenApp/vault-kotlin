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

    /** AES block size in bytes; the CTR-mode private section is always a whole number of blocks. */
    private const val AES_BLOCK_BYTES = 16

    /**
     * Lowest work factor Vault will ever generate or let the user select. Each bcrypt_pbkdf round
     * costs a full Blowfish key schedule — roughly 8 ms of deliberate work on a desktop CPU — so
     * the default of 128 is about a second and a floor of 128 keeps generated keys in that range.
     *
     * This floor applies to **generation only**. Parsing accepts weaker values because the app
     * imports foreign keys: `ssh-keygen` defaults to 16 rounds, and rejecting anything below the
     * floor would make a user's own existing key impossible to paste or unlock. Those keys keep
     * whatever work factor they were created with.
     */
    const val MIN_BCRYPT_PBKDF_ROUNDS = 128

    /**
     * Highest work factor accepted anywhere. It bounds both what the Settings menu offers and what
     * a parsed container may declare, so a hostile pasted key cannot ask the app to run an
     * effectively unbounded bcrypt_pbkdf loop. Parsing is the security-critical direction: a
     * container declaring `rounds = 2_147_483_647` passes metadata validation today and then
     * wedges the passphrase step forever, which would make recovery impossible to complete.
     */
    const val MAX_BCRYPT_PBKDF_ROUNDS = 8192

    /**
     * Work factors Vault offers for *newly generated* keys, as powers of two inside the supported
     * band. Kept as a list because the Settings dropdown renders it directly.
     */
    val SUPPORTED_BCRYPT_PBKDF_ROUNDS: List<Int> =
        generateSequence(MIN_BCRYPT_PBKDF_ROUNDS) { previous -> (previous * 2).takeIf { it <= MAX_BCRYPT_PBKDF_ROUNDS } }
            .toList()

    /** Work factor used when the user has not chosen one. Tied to the floor so it stays selectable. */
    const val DEFAULT_BCRYPT_PBKDF_ROUNDS = MIN_BCRYPT_PBKDF_ROUNDS

    /**
     * Upper bound on accepted serialized-key text. A real encrypted OpenSSH key and the
     * self-describing export that wraps it span only a few kilobytes, so rejecting oversized
     * input keeps clipboard- or file-sourced strings from driving unbounded regex/Base64 work
     * (and the stack/heat it causes) before the format is even checked.
     */
    const val MAX_SERIALIZED_KEY_CHARS = 16 * 1024

    data class ParsedKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val comment: String,
        val encrypted: Boolean,
    )

    data class GeneratedKey(
        val privateKeyPem: String,
        val createdAt: Long,
    )

    private data class EncryptedContainer(
        val bytes: ByteArray,
        val publicKey: ByteArray,
        val encryptedPrivate: ByteArray,
        val salt: ByteArray,
        val rounds: Int,
    )

    /** Parses only passphrase-protected OpenSSH-v1 Ed25519 private keys. */
    fun parse(privateKeyPem: String, passphrase: CharArray): ParsedKey {
        val container = readEncryptedContainer(privateKeyPem)
        val privateBlob = cryptBcrypt(Cipher.DECRYPT_MODE, container.encryptedPrivate, container.salt, container.rounds, passphrase)
        return parsePrivateBlob(privateBlob, container.publicKey, encrypted = true)
    }

    /** Converts PEM, raw base64, or {"OPENSSH PRIVATE KEY":"..."} input to canonical single-line base64. */
    fun normalizeToSingleLineBase64(input: String): String = Base64.getEncoder().encodeToString(decodePem(input))

    /** Validates the encrypted OpenSSH-v1 Ed25519 container without requiring its passphrase. */
    fun validateAndNormalizeEncryptedPrivateKey(input: String): String {
        val container = readEncryptedContainer(input)
        return Base64.getEncoder().encodeToString(container.bytes)
    }

    /** Reads the bcrypt work factor stored in the unencrypted OpenSSH container metadata. */
    fun bcryptKdfRounds(input: String): Int = readEncryptedContainer(input).rounds

    /** Formats a stored canonical key for OpenSSH-compatible export. */
    fun formatAsPem(input: String): String = pemEncode(decodePem(input))

    /**
     * Formats the authorized-keys line for a public key.
     *
     * A blank comment omits the trailing comment field entirely instead of leaving a trailing
     * space, so a copied line stays a valid two-field entry.
     */
    fun publicKeyLine(publicKey: ByteArray, comment: String): String {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val blob = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(publicKey)
        val keyLine = "$Ed25519 ${Base64.getEncoder().encodeToString(blob)}"
        return if (comment.isBlank()) keyLine else "$keyLine $comment"
    }

    /** Fingerprint the SSH public-key wire blob, as ssh-keygen -E sha256 does. */
    fun fingerprint(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val wire = sshString(Ed25519.toByteArray(StandardCharsets.US_ASCII)) + sshString(publicKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }

    /** Extracts the Ed25519 public key from an encrypted OpenSSH-v1 key's unencrypted header. */
    fun publicKeyFromEncryptedPrivateKey(input: String): ByteArray =
        readEncryptedContainer(input).publicKey

    /** Fingerprints an encrypted private key without requiring its passphrase. */
    fun fingerprintFromEncryptedPrivateKey(input: String): String =
        fingerprint(publicKeyFromEncryptedPrivateKey(input))

    /** Generates an OpenSSH-v1 Ed25519 key encrypted with aes256-ctr and bcrypt_pbkdf. */
    fun generateEncrypted(
        createdAt: Long,
        passphrase: CharArray,
        rounds: Int = DEFAULT_BCRYPT_PBKDF_ROUNDS,
        random: SecureRandom = SecureRandom(),
    ): GeneratedKey {
        require(passphrase.isNotEmpty()) { "A passphrase is required to encrypt an SSH key" }
        // Generation is held to the supported band; only parsing tolerates weaker foreign keys.
        require(rounds in SUPPORTED_BCRYPT_PBKDF_ROUNDS) {
            "bcrypt KDF round count must be between $MIN_BCRYPT_PBKDF_ROUNDS and $MAX_BCRYPT_PBKDF_ROUNDS: $rounds"
        }
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
        val options = sshString(salt) + uint(rounds)
        val containerFields = OpenSshEncryption(
            Aes256Ctr.toByteArray(),
            Bcrypt.toByteArray(),
            options,
            encryptBcrypt(pad(privateBlob, AES_BLOCK_BYTES), salt, rounds, passphrase),
        )
        val container = magic + sshString(containerFields.cipherName) + sshString(containerFields.kdfName) + sshString(containerFields.kdfOptions) +
            uint(1) + sshString(publicBlob) + sshString(containerFields.privateSection)
        return GeneratedKey(pemEncode(container), createdAt)
    }

    private data class OpenSshEncryption(
        val cipherName: ByteArray,
        val kdfName: ByteArray,
        val kdfOptions: ByteArray,
        val privateSection: ByteArray,
    )

    private fun encryptBcrypt(plaintext: ByteArray, salt: ByteArray, rounds: Int, passphrase: CharArray): ByteArray =
        cryptBcrypt(Cipher.ENCRYPT_MODE, plaintext, salt, rounds, passphrase)

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
        val seed = privateAndPublic.copyOfRange(0, 32)
        // The seed and the public key are two views of one identity: the SSH key shown/exported to
        // the user comes from the public field, while the recovery words come from the seed. AES-CTR
        // authenticates nothing, so every check above can pass on a corrupt or crafted blob whose
        // seed belongs to a different key. Re-derive the public key from the seed and require the
        // two identities to agree, otherwise the user could receive a key no server accepts or
        // recover under an identity they never had.
        require(publicKeyOf(seed).contentEquals(public)) {
            "OpenSSH private key does not match its public key"
        }
        return ParsedKey(seed, public, comment, encrypted)
    }

    /** Derives the Ed25519 public key that belongs to [seed], the counterpart of `generateEncrypted`. */
    private fun publicKeyOf(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    private fun readPublicBlob(blob: ByteArray): ByteArray {
        val reader = SshReader(blob)
        require(reader.stringAscii() == Ed25519) { "Only ssh-ed25519 keys are supported" }
        val public = reader.bytes()
        require(public.size == 32 && reader.atEnd()) { "Invalid Ed25519 public key" }
        return public
    }

    private fun readEncryptedContainer(input: String): EncryptedContainer {
        val bytes = decodePem(input)
        require(bytes.take(magic.size).toByteArray().contentEquals(magic)) { "Not an OpenSSH v1 private key" }
        val reader = SshReader(bytes, magic.size)
        val cipherName = reader.stringAscii()
        val kdfName = reader.stringAscii()
        val kdfOptions = reader.bytes()
        require(reader.uInt() == 1L) { "Only one OpenSSH key is supported" }
        val publicKey = readPublicBlob(reader.bytes())
        val encryptedPrivate = reader.bytes()
        require(encryptedPrivate.isNotEmpty() && reader.atEnd()) { "Invalid OpenSSH private-key container" }
        // AES-CTR cannot be fed a partial block, and a truncated or spliced container would
        // otherwise decrypt to shifted garbage instead of being rejected outright.
        require(encryptedPrivate.size % AES_BLOCK_BYTES == 0) { "Invalid OpenSSH private-key length" }
        require(cipherName == Aes256Ctr) { "Unencrypted OpenSSH private keys are not supported" }
        require(kdfName == Bcrypt) { "Unsupported OpenSSH KDF: $kdfName" }
        val optionsReader = SshReader(kdfOptions)
        val salt = optionsReader.bytes()
        // Read the work factor as an unsigned 32-bit value before narrowing it. This field is
        // attacker-controlled: a crafted container can declare any value, and bcrypt_pbkdf cost
        // grows linearly with it, so an unchecked value turns one pasted key into a hang that
        // repeats on every unlock and recovery attempt. Reject it here, while only metadata has
        // been read, rather than after the KDF has already been asked to run.
        val declaredRounds = optionsReader.uInt()
        require(salt.isNotEmpty() && declaredRounds > 0 && optionsReader.atEnd()) { "Invalid bcrypt KDF options" }
        require(declaredRounds <= MAX_BCRYPT_PBKDF_ROUNDS) {
            "OpenSSH key uses $declaredRounds bcrypt KDF rounds; the supported maximum is $MAX_BCRYPT_PBKDF_ROUNDS"
        }
        return EncryptedContainer(bytes, publicKey, encryptedPrivate, salt, declaredRounds.toInt())
    }

    private fun verifyPadding(padding: ByteArray) {
        padding.forEachIndexed { index, byte -> require(byte.toInt() and 0xff == index + 1) { "Invalid OpenSSH private-key padding" } }
    }

    private fun pad(input: ByteArray, blockSize: Int): ByteArray {
        val count = blockSize - (input.size % blockSize)
        return input + ByteArray(count) { (it + 1).toByte() }
    }

    private fun decodePem(input: String): ByteArray {
        require(input.length <= MAX_SERIALIZED_KEY_CHARS) { "OpenSSH key input exceeds the supported size" }
        val trimmed = input.trim()
        val jsonValue = if (trimmed.startsWith("{")) {
            Regex("\\\"OPENSSH PRIVATE KEY\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(trimmed)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Invalid OpenSSH key JSON")
        } else trimmed
        // Self-describing exports wrap a one-line Base64 body in OpenSSH PEM markers and then
        // append public metadata (npub, creation ID, display name, date) on later lines. When the
        // markers are present, take only the text between them so that metadata is ignored on paste.
        val markerBody = Regex(
            "-----BEGIN OPENSSH PRIVATE KEY-----([\\s\\S]*?)-----END OPENSSH PRIVATE KEY-----",
        ).find(jsonValue)?.groupValues?.get(1) ?: jsonValue
        val body = markerBody.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
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
