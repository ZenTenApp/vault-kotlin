package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64

class SshKeyCodecTest {
    private val json = Json

    @Test
    fun extracts_ed25519_seed_fingerprint_and_mnemonic_from_encrypted_golden_key() {
        val vector = vector("unencrypted-ed25519")
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
        val passphrase = "phase1-test-passphrase".toCharArray()
        val parsed = SshKeyCodec.parse(pem, passphrase)

        assertTrue(parsed.encrypted)
        assertEquals(value(vector, "seedHex"), parsed.seed.hex())
        assertEquals(value(vector, "ed25519PubkeyHex"), parsed.publicKey.hex())
        assertEquals(value(vector, "sshFingerprint"), SshKeyCodec.fingerprint(parsed.publicKey))
        assertEquals(value(vector, "sshFingerprint"), SshKeyCodec.fingerprintFromEncryptedPrivateKey(pem))
        assertArrayEquals(parsed.publicKey, SshKeyCodec.publicKeyFromEncryptedPrivateKey(pem))
        assertEquals(value(vector, "mnemonic24"), Seedify.toMnemonic(parsed.seed))
        assertEquals(value(vector, "mnemonic24"), Seedify.fromSshKey(pem, passphrase))
    }

    @Test
    fun rejects_oversized_key_input_before_decoding() {
        val oversized = "A".repeat(SshKeyCodec.MAX_SERIALIZED_KEY_CHARS + 1)

        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(oversized)
        }
    }

    @Test
    fun derives_public_key_and_fingerprint_from_encrypted_key_without_passphrase() {
        val vector = vector("unencrypted-ed25519")
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content

        val publicKey = SshKeyCodec.publicKeyFromEncryptedPrivateKey(pem)
        assertEquals(value(vector, "ed25519PubkeyHex"), publicKey.hex())
        assertEquals(value(vector, "sshFingerprint"), SshKeyCodec.fingerprintFromEncryptedPrivateKey(pem))
        assertTrue(SshKeyCodec.publicKeyLine(publicKey, "a comment").startsWith("ssh-ed25519 "))

        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.publicKeyFromEncryptedPrivateKey("not an SSH key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.fingerprintFromEncryptedPrivateKey(value(vector, "sshKeyPem"))
        }
    }

    @Test
    fun generates_brave_sync_code_from_vault_key_backup_associated_account_and_daily_word() {
        val seed = SshKeyCodec.parse(
            root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content,
            "phase1-test-passphrase".toCharArray(),
        ).seed

        val code = Seedify.toBraveSyncCode(seed, Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(
            code.split(" ").take(24).joinToString(" "),
            Seedify.toVaultKeyBackupMnemonic(seed),
        )
        assertEquals(
            code.split(" ").take(24),
            Seedify.toVaultKeyBackupWordIndices(seed).map { Bip39Mnemonics.englishWordlist[it - 1] },
        )

        assertEquals(
            "story solid net hybrid cupboard mistake glance juice large harvest very license maid miss acoustic bracket bargain point marriage sister arctic acquire cattle tourist pledge",
            code,
        )
        assertEquals(25, code.split(" ").size)
    }

    @Test
    fun normalizes_pem_raw_base64_and_json_key_input_to_single_line_base64() {
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
        val canonical = SshKeyCodec.normalizeToSingleLineBase64(pem)
        assertFalse(canonical.contains('\n'))
        assertEquals(canonical, SshKeyCodec.normalizeToSingleLineBase64(canonical))
        assertEquals(canonical, SshKeyCodec.normalizeToSingleLineBase64("{\"OPENSSH PRIVATE KEY\":\"$canonical\"}"))
        assertEquals(canonical, SshKeyCodec.normalizeToSingleLineBase64("  $canonical\n"))
    }

    @Test
    fun validates_supported_encrypted_key_formats_without_a_passphrase() {
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
        val canonical = SshKeyCodec.normalizeToSingleLineBase64(pem)

        assertEquals(canonical, SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(pem))
        assertEquals(canonical, SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(canonical))
        assertEquals(canonical, SshKeyCodec.validateAndNormalizeEncryptedPrivateKey("{\"OPENSSH PRIVATE KEY\":\"$canonical\"}"))
    }

    @Test
    fun ignores_public_metadata_appended_to_a_pem_export() {
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
        val canonical = SshKeyCodec.normalizeToSingleLineBase64(pem)
        val selfDescribing = "-----BEGIN OPENSSH PRIVATE KEY-----\n$canonical\n" +
            "-----END OPENSSH PRIVATE KEY-----\nnpub1example\n1700000000000000\nVaultKey\nCreated on Wednesday, Nov 15, 2023 2:06 AM"

        assertEquals(canonical, SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(selfDescribing))
        assertEquals(canonical, SshKeyCodec.normalizeToSingleLineBase64(selfDescribing))
    }

    @Test
    fun reads_bcrypt_kdf_rounds_without_a_passphrase() {
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content

        assertEquals(1, SshKeyCodec.bcryptKdfRounds(pem))
    }

    @Test
    fun prevalidation_rejects_invalid_and_unencrypted_private_keys() {
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey("not an SSH key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(value(vector("unencrypted-ed25519"), "sshKeyPem"))
        }
    }

    @Test
    fun formats_canonical_key_as_open_ssh_pem_for_export() {
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content

        assertEquals(
            SshKeyCodec.normalizeToSingleLineBase64(pem),
            SshKeyCodec.normalizeToSingleLineBase64(SshKeyCodec.formatAsPem(SshKeyCodec.normalizeToSingleLineBase64(pem))),
        )
    }

    @Test
    fun decrypts_bcrypt_aes_ctr_key_and_rejects_wrong_passphrase() {
        val root = root()
        val pem = root["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content
        // Fixture generated by OpenSSH with aes256-ctr, bcrypt_pbkdf, and one KDF round.
        val parsed = SshKeyCodec.parse(pem, "phase1-test-passphrase".toCharArray())

        assertTrue(parsed.encrypted)
        assertEquals("076be8733da9c93bf11fec6d37a4b2ee6f628d621faccea798ff57bb0038ddcb", parsed.seed.hex())
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.parse(pem, "wrong".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.parse(pem, CharArray(0)) }
    }

    @Test
    fun rejects_unencrypted_keys_and_generates_passphrase_encrypted_associated_accounts() {
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.parse(value(vector("unencrypted-ed25519"), "sshKeyPem"), "any-passphrase".toCharArray())
        }

        val createdAt = 1_700_000_000_123_456L
        val passphrase = "vaultKey-passphrase".toCharArray()
        val generated = SshKeyCodec.generateEncrypted(createdAt, passphrase)
        val parsed = SshKeyCodec.parse(generated.privateKeyPem, passphrase)

        assertTrue(parsed.encrypted)
        assertEquals(createdAt.toString(), parsed.comment)
        assertArrayEquals(parsed.publicKey, SshKeyCodec.publicKeyFromEncryptedPrivateKey(generated.privateKeyPem))
        assertEquals(SshKeyCodec.fingerprint(parsed.publicKey), SshKeyCodec.fingerprintFromEncryptedPrivateKey(generated.privateKeyPem))
        assertEquals(SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS, SshKeyCodec.bcryptKdfRounds(generated.privateKeyPem))
        assertArrayEquals(parsed.publicKey, SshKeyCodec.parse(generated.privateKeyPem, passphrase).publicKey)
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.parse(generated.privateKeyPem, CharArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.parse(generated.privateKeyPem, "wrong".toCharArray()) }
    }

    @Test
    fun generation_accepts_only_rounds_inside_the_supported_band() {
        val passphrase = "vaultKey-passphrase".toCharArray()
        val generated = SshKeyCodec.generateEncrypted(123L, passphrase, rounds = 256)

        assertEquals(256, SshKeyCodec.bcryptKdfRounds(generated.privateKeyPem))
        // Below the floor: a single bcrypt block is far too cheap to protect a key that leaves
        // the device, so generation must refuse it even though parsing still accepts it.
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.generateEncrypted(124L, passphrase, rounds = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.generateEncrypted(125L, passphrase, rounds = 64)
        }
        // Above the ceiling, and a non-power-of-two that only the band check can reject.
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.generateEncrypted(126L, passphrase, rounds = 16_384)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.generateEncrypted(127L, passphrase, rounds = 200)
        }
    }

    @Test
    fun offers_a_contiguous_power_of_two_band_for_generation() {
        val offered = SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS

        assertEquals(SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS, offered.first())
        assertEquals(SshKeyCodec.MAX_BCRYPT_PBKDF_ROUNDS, offered.last())
        // No 1-round key can be created or selected: the weakest offered value must already be
        // expensive enough to slow an offline attack on an exported key.
        assertFalse(offered.contains(1))
        assertTrue(offered.all { it == 1 shl Integer.numberOfTrailingZeros(it) })
        // The default must stay selectable, or the Settings dropdown could not show its value.
        assertTrue(offered.contains(SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS))
    }

    @Test
    fun parsing_still_accepts_a_foreign_key_below_the_generation_floor() {
        // Compatibility, not a security property: ssh-keygen's default is 16 rounds, so a user's
        // own existing key must remain pasteable and unlockable even though we no longer create
        // anything that weak. This fixture also uses one round.
        val pem = root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content

        assertEquals(1, SshKeyCodec.bcryptKdfRounds(pem))
        assertTrue(SshKeyCodec.parse(pem, "phase1-test-passphrase".toCharArray()).encrypted)
    }

    @Test
    fun rejects_a_container_declaring_an_unbounded_bcrypt_work_factor() {
        val bytes = goldenContainer()

        // The work factor sits in a four-byte big-endian field inside the plaintext KDF options,
        // so it can be rewritten without touching any encrypted bytes or the passphrase.
        fun containerWithRounds(rounds: Int): String {
            val tampered = bytes.copyOf()
            ByteBuffer.wrap(tampered).putInt(roundsFieldOffset(tampered), rounds)
            return Base64.getEncoder().encodeToString(tampered)
        }

        // Metadata-only paths must reject it, so a hostile key never even reaches the passphrase
        // step. 2_147_483_647 is the value the audit reproduced as accepted.
        listOf(Int.MAX_VALUE, SshKeyCodec.MAX_BCRYPT_PBKDF_ROUNDS + 1).forEach { rounds ->
            assertThrows(IllegalArgumentException::class.java) {
                SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(containerWithRounds(rounds))
            }
            assertThrows(IllegalArgumentException::class.java) {
                SshKeyCodec.bcryptKdfRounds(containerWithRounds(rounds))
            }
            // The full parse must fail just as fast, before running bcrypt at all.
            assertThrows(IllegalArgumentException::class.java) {
                SshKeyCodec.parse(containerWithRounds(rounds), "phase1-test-passphrase".toCharArray())
            }
        }
        // Positive control: the ceiling itself is still accepted, so the test fails for the right
        // reason rather than because the tampering broke the container.
        assertEquals(
            SshKeyCodec.MAX_BCRYPT_PBKDF_ROUNDS,
            SshKeyCodec.bcryptKdfRounds(containerWithRounds(SshKeyCodec.MAX_BCRYPT_PBKDF_ROUNDS)),
        )
    }

    @Test
    fun app_owned_nostr_adapters_match_golden_and_self_encrypt() {
        val vector = vector("unencrypted-ed25519")
        val keys = NostrKeyDerivation.derive(value(vector, "mnemonic24"))
        val expected = vector["nostrNip06"]!!.jsonObject
        assertEquals(expected["privkey"]!!.jsonPrimitive.content, keys.privateKey.hex())
        assertEquals(expected["pubkey"]!!.jsonPrimitive.content, keys.publicKey.hex())
        assertEquals(expected["npub"]!!.jsonPrimitive.content, keys.npub)
        assertEquals(expected["nsec"]!!.jsonPrimitive.content, keys.nsec)

        val encrypted = Nip44.encryptSelf("backup", keys.privateKey, keys.publicKey)
        assertEquals("backup", Nip44.decryptSelf(encrypted, keys.privateKey, keys.publicKey))
    }

    @Test
    fun rejects_an_openssh_key_whose_seed_does_not_match_its_public_key() {
        // Corrupting one seed byte leaves every structural check intact: the check integers, the
        // two public-key copies, the private-field layout and the padding all still agree. Only
        // re-deriving the public key from the seed detects that the SSH identity (public key) and
        // the recovery identity (seed -> mnemonic) have diverged.
        val container = goldenContainer()
        val corrupted = patchPrivateCiphertextByte(container, SEED_PLAINTEXT_OFFSET, 0x01)

        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.parse(corrupted, "phase1-test-passphrase".toCharArray())
        }
    }

    @Test
    fun rejects_mutated_check_integers_and_padding() {
        val container = goldenContainer()

        // The two check integers must be equal; the second one starts at plaintext offset 4.
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.parse(patchPrivateCiphertextByte(container, CHECK_INTEGER_2_OFFSET, 0x01), "phase1-test-passphrase".toCharArray())
        }

        // The last plaintext byte is the final padding byte, which must equal the padding index.
        // Flipping its high bit always breaks that equality because indices never exceed 16.
        val badPadding = container.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x80).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.parse(Base64.getEncoder().encodeToString(badPadding), "phase1-test-passphrase".toCharArray())
        }
    }

    @Test
    fun rejects_a_private_section_that_is_not_a_whole_number_of_aes_blocks() {
        // A truncated or spliced container must be rejected on sight rather than decrypted into
        // shifted garbage. Appending bytes and widening the stored length keeps the container
        // self-consistent apart from the block alignment.
        val misaligned = appendPrivateCiphertextBytes(goldenContainer(), extra = 5)

        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.parse(misaligned, "phase1-test-passphrase".toCharArray())
        }
    }

    // --- helpers for building hostile variants of the golden key -----------------------------

    private fun goldenContainer() =
        Base64.getDecoder().decode(SshKeyCodec.normalizeToSingleLineBase64(root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content))

    /** Offsets of the encrypted private section's length prefix and payload inside the container. */
    private fun privateSectionOffsets(container: ByteArray): Pair<Int, Int> {
        var offset = MAGIC.size
        repeat(3) { offset += 4 + readUInt(container, offset) } // cipher name, KDF name, KDF options
        offset += 4 // key count
        offset += 4 + readUInt(container, offset) // outer public-key blob
        return offset to (offset + 4)
    }

    /**
     * XOR-flips one byte of the *decrypted* private blob. AES-CTR is a stream cipher, so flipping a
     * ciphertext byte flips exactly the plaintext byte at the same offset — no re-encryption needed.
     */
    private fun patchPrivateCiphertextByte(container: ByteArray, plaintextOffset: Int, xorByte: Int): String {
        val (_, privateStart) = privateSectionOffsets(container)
        val mutated = container.copyOf()
        val index = privateStart + plaintextOffset
        mutated[index] = (mutated[index].toInt() xor xorByte).toByte()
        return Base64.getEncoder().encodeToString(mutated)
    }

    private fun appendPrivateCiphertextBytes(container: ByteArray, extra: Int): String {
        val (lengthPrefix, privateStart) = privateSectionOffsets(container)
        val widened = ByteArray(4).also { ByteBuffer.wrap(it).putInt(readUInt(container, lengthPrefix) + extra) }
        return Base64.getEncoder().encodeToString(
            container.copyOfRange(0, lengthPrefix) + widened + container.copyOfRange(privateStart, container.size) + ByteArray(extra),
        )
    }

    private fun readUInt(bytes: ByteArray, at: Int): Int = ByteBuffer.wrap(bytes, at, 4).int

    /**
     * Offset of the bcrypt work-factor field inside the container. The KDF options are
     * `[salt length][salt][rounds]`, and the options block follows the magic, cipher name, and KDF
     * name. Walking the lengths keeps this correct if the fixture's salt size ever changes.
     */
    private fun roundsFieldOffset(container: ByteArray): Int {
        var offset = MAGIC.size
        repeat(2) { offset += 4 + readUInt(container, offset) } // cipher name, KDF name
        val optionsStart = offset + 4
        return optionsStart + 4 + readUInt(container, optionsStart) // skip the salt string
    }

    private fun root() = json.parseToJsonElement(load("/golden/crypto-vectors.json")).jsonObject
    private fun vector(label: String) = root()["vectors"]!!.jsonArray.map { it.jsonObject }.first { value(it, "label") == label }
    private fun value(object_: kotlinx.serialization.json.JsonObject, name: String) = object_[name]!!.jsonPrimitive.content
    private fun load(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * Offset of the 32-byte seed inside the decrypted private blob. The blob is laid out as
         * [check1][check2][algorithm string][public key string][private field string][comment][padding],
         * so the seed starts right after the private field's 4-byte length prefix:
         * 4+4 check integers, 4+11 algorithm, 4+32 public key, 4 length prefix = 63.
         */
        const val SEED_PLAINTEXT_OFFSET = 4 + 4 + (4 + 11) + (4 + 32) + 4

        /** The second of the two duplicated check integers must equal the first. */
        const val CHECK_INTEGER_2_OFFSET = 4

        /** OpenSSH-v1 magic prefix that precedes the container fields. */
        val MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    }
}
