package com.zenten.vault.crypto

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PHASE-1 PARITY GATE — proves byte-for-byte parity between Quartz's NIP-06 / NIP-19 /
 * NIP-44 v2 implementations and our reference golden vectors (generated from
 * nostr-tools + the official paulmillr/nip44 vectors).
 *
 * Because these pass, Quartz owns the entire "standard Nostr" half of the crypto chain
 * (NIP-06/19/44 + events); we do NOT hand-roll NIP-44 or vendor a bech32 encoder.
 *
 * These tests run on the HOST JVM (JUnit5), so secp256k1 + ChaCha20 work without an
 * Android device. NOTE: Quartz ships JVM 21 bytecode, so the unit-test task is pinned
 * to a JDK 21 toolchain in app/build.gradle.kts.
 */
class QuartzParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader().use { it.readText() }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // ---- NIP-06 / NIP-19 parity against our production vault path -----------------

    @Test
    fun nip06_derivation_matches_golden_npub_nsec_hex() {
        val root = json.parseToJsonElement(loadResource("/golden/crypto-vectors.json")).jsonObject
        val vector = root["vectors"]!!.jsonArray.first().jsonObject
        val mnemonic = vector["mnemonic24"]!!.jsonPrimitive.content
        val expected = vector["nostrNip06"]!!.jsonObject

        val nip06 = Nip06()
        assertTrue(nip06.isValidMnemonic(mnemonic), "golden mnemonic must pass BIP39 checksum")

        // account 0 -> m/44'/1237'/0'/0/0 (standard NIP-06)
        val privKey = nip06.privateKeyFromMnemonic(mnemonic, 0L)
        val keyPair = KeyPair(privKey = privKey)

        assertEquals(
            expected["privkey"]!!.jsonPrimitive.content,
            bytesToHex(privKey),
            "NIP-06 private key hex must match nostr-tools golden",
        )
        assertEquals(
            expected["pubkey"]!!.jsonPrimitive.content,
            bytesToHex(keyPair.pubKey),
            "x-only pubkey hex must match nostr-tools golden",
        )
        assertEquals(
            expected["npub"]!!.jsonPrimitive.content,
            keyPair.pubKey.toNpub(),
            "NIP-19 npub must match golden",
        )
        assertEquals(
            expected["nsec"]!!.jsonPrimitive.content,
            privKey.toNsec(),
            "NIP-19 nsec must match golden",
        )
    }

    // ---- NIP-44 v2 conversation-key parity (official vectors) ---------------------

    @Test
    fun nip44_conversationKey_matches_official_vectors() {
        val v2 = officialV2Valid()
        val cases = v2["get_conversation_key"]!!.jsonArray

        cases.forEach { el ->
            val c = el.jsonObject
            val sec1 = hexToBytes(c["sec1"]!!.jsonPrimitive.content)
            val pub2 = hexToBytes(c["pub2"]!!.jsonPrimitive.content)
            val expected = c["conversation_key"]!!.jsonPrimitive.content

            val actual = bytesToHex(Nip44.v2.getConversationKey(sec1, pub2))
            assertEquals(expected, actual, "conversation key mismatch for sec1=${c["sec1"]!!.jsonPrimitive.content}")
        }
        assertEquals(35, cases.size, "expected all 35 official conversation-key vectors")
    }

    // ---- NIP-44 v2 FULL ChaCha20 parity via fixed-nonce encrypt -------------------

    @Test
    fun nip44_encrypt_with_fixed_nonce_matches_official_payloads() {
        val v2 = officialV2Valid()
        val cases = v2["encrypt_decrypt"]!!.jsonArray

        cases.forEach { el ->
            val c = el.jsonObject
            val convKey = hexToBytes(c["conversation_key"]!!.jsonPrimitive.content)
            val nonce = hexToBytes(c["nonce"]!!.jsonPrimitive.content)
            val plaintext = c["plaintext"]!!.jsonPrimitive.content
            val expectedPayload = c["payload"]!!.jsonPrimitive.content

            // Deterministic encrypt (fixed nonce) => full byte-for-byte ciphertext parity.
            val encrypted = Nip44.v2.encryptWithNonce(plaintext, convKey, nonce)
            assertEquals(expectedPayload, encrypted.encodePayload(), "ciphertext payload mismatch")

            // Decrypt round-trip from the official payload.
            val decrypted = Nip44.v2.decrypt(expectedPayload, convKey)
            assertEquals(plaintext, decrypted, "decrypt round-trip mismatch")
        }
    }

    private fun officialV2Valid(): JsonObject =
        json.parseToJsonElement(loadResource("/golden/nip44.vectors.json"))
            .jsonObject["v2"]!!.jsonObject["valid"]!!.jsonObject
}
