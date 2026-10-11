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
import java.time.Instant

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
    fun generates_brave_sync_code_from_brave_associated_account_and_daily_word() {
        val seed = SshKeyCodec.parse(
            root()["keys"]!!.jsonObject["encryptedEd25519Pem"]!!.jsonPrimitive.content,
            "phase1-test-passphrase".toCharArray(),
        ).seed

        val code = Seedify.toBraveSyncCode(seed, Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(
            code.split(" ").take(24).joinToString(" "),
            Seedify.toBraveAccountMnemonic(seed),
        )
        assertEquals(
            code.split(" ").take(24),
            Seedify.toBraveSyncWordIndices(seed).map { Bip39Mnemonics.englishWordlist[it - 1] },
        )

        assertEquals(
            "jacket mutual dry guard cat ribbon wasp police plug absurd add police face rack alcohol palm absorb long pumpkin dizzy odor job crush dial pledge",
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
    fun generation_accepts_only_supported_kdf_rounds() {
        val passphrase = "vaultKey-passphrase".toCharArray()
        val generated = SshKeyCodec.generateEncrypted(123L, passphrase, rounds = 8)

        assertEquals(8, SshKeyCodec.bcryptKdfRounds(generated.privateKeyPem))
        assertThrows(IllegalArgumentException::class.java) {
            SshKeyCodec.generateEncrypted(124L, passphrase, rounds = 3)
        }
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

    private fun root() = json.parseToJsonElement(load("/golden/crypto-vectors.json")).jsonObject
    private fun vector(label: String) = root()["vectors"]!!.jsonArray.map { it.jsonObject }.first { value(it, "label") == label }
    private fun value(object_: kotlinx.serialization.json.JsonObject, name: String) = object_[name]!!.jsonPrimitive.content
    private fun load(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
