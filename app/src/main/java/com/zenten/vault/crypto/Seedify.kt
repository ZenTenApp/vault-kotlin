package com.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics

/** ZenTen's no-prefix path: the raw 32-byte Ed25519 seed is BIP-39 entropy. */
object Seedify {
    fun toMnemonic(seed: ByteArray): String {
        require(seed.size == 32) { "Seedify requires a 32-byte Ed25519 seed" }
        return Bip39Mnemonics.toMnemonics(seed).joinToString(" ")
    }

    fun fromSshKey(privateKeyPem: String, passphrase: CharArray? = null): String =
        toMnemonic(SshKeyCodec.parse(privateKeyPem, passphrase).seed)
}
