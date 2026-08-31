package app.zenten.vault.crypto

import app.zenten.vault.data.model.NostrKeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec

/** App-facing NIP-06/NIP-19 boundary; Quartz types do not escape this API. */
object NostrKeyDerivation {
    /** Raw Nostr key material alongside its NIP-19 Bech32 representations. */
    data class NostrKeys(val privateKey: ByteArray, val publicKey: ByteArray, val nsec: String, val npub: String)

    /**
     * Derives the NIP-06 key pair for [account] from a BIP-39 [mnemonic].
     *
     * The result includes raw key bytes and their NIP-19 `nsec` and `npub` encodings.
     */
    fun derive(mnemonic: String, account: Long = 0): NostrKeys {
        val privateKey = Nip06().privateKeyFromMnemonic(mnemonic, account)
        val publicKey = KeyPair(privKey = privateKey).pubKey
        return NostrKeys(privateKey, publicKey, privateKey.toNsec(), publicKey.toNpub())
    }

}

/** Converts derived key bytes to the hexadecimal representation used by local persistence. */
fun NostrKeyDerivation.NostrKeys.toPersistedKeyPair(): NostrKeyPair =
    NostrKeyPair(publicKey.hex(), privateKey.hex())

/** Encodes each byte as two lowercase hexadecimal characters. */
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
