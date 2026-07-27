package com.zenten.vault.crypto

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec

/** App-facing NIP-06/NIP-19 boundary; Quartz types do not escape this API. */
object NostrKeyDerivation {
    data class NostrKeys(val privateKey: ByteArray, val publicKey: ByteArray, val nsec: String, val npub: String)

    fun derive(mnemonic: String, account: Long = 0): NostrKeys {
        val privateKey = Nip06().privateKeyFromMnemonic(mnemonic, account)
        val publicKey = KeyPair(privKey = privateKey).pubKey
        return NostrKeys(privateKey, publicKey, privateKey.toNsec(), publicKey.toNpub())
    }
}
