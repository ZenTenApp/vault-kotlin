package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec

/**
 * App-facing NIP-06/NIP-19 boundary; Quartz types do not escape this API.
 *
 * Callers derive an identity where they need it rather than storing one. The derivation is a pure
 * function of an account's SSH seed, so the Vault Key's own identity for associated-account records
 * is `NostrKeyDerivation.derive(Seedify.toMnemonic(seed))` and the recoverable Vault Key backup
 * identity is `NostrKeyDerivation.derive(Seedify.toVaultKeyBackupMnemonic(seed))`.
 */
object NostrKeyDerivation {
    /**
     * Raw Nostr key material alongside its NIP-19 Bech32 representations.
     *
     * [pubkey] is the lowercase hex form that Nostr events carry in their `pubkey` field, so
     * callers comparing an event's author to this identity never re-encode the bytes themselves.
     */
    data class NostrKeys(val privateKey: ByteArray, val publicKey: ByteArray, val nsec: String, val npub: String) {
        val pubkey: String get() = publicKey.joinToString("") { "%02x".format(it) }
    }

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

    /**
     * The Vault Key's own identity, used for associated-account backup records.
     *
     * Derived from the same 24-word mnemonic as the Vault Key's seed, so a caller holding the
     * decrypted key can rebuild it at any time without storing it.
     */
    fun fromAccountSeed(seed: ByteArray): NostrKeys = derive(Seedify.toMnemonic(seed))

    /**
     * The recoverable Vault Key backup identity, which owns every Vault Key relay record.
     *
     * Uses the `vkbckp` associated-account mnemonic so the identity can be rebuilt from the 24
     * Secret Combinations alone. Never store the result: its whole point is being recomputable.
     */
    fun fromVaultKeyBackupSeed(seed: ByteArray): NostrKeys = derive(Seedify.toVaultKeyBackupMnemonic(seed))
}
