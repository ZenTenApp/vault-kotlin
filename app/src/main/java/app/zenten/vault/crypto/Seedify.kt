package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import java.security.MessageDigest
import java.time.Instant

/** ZenTen's no-prefix path: the raw 32-byte Ed25519 seed is BIP-39 entropy. */
object Seedify {
    private const val SEED_SIZE_BYTES = 32

    /**
     * Sub-account name used to derive the recoverable Vault Key backup seed.
     *
     * `vkbckp` is the Vault Key BacKup acronym ("VK" + "BCKP"). [deriveAssociatedAccountSeed]
     * hashes it and mixes it with the Vault Key's SSH seed, so the exact spelling is part of the
     * generation contract: changing it produces a different 24-word mnemonic, sync code, and
     * Nostr identity for Vault Keys generated afterwards.
     */
    private const val VAULT_KEY_BACKUP_ASSOCIATED_ACCOUNT = "vkbckp"
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    private val braveSyncEpoch = Instant.parse("2022-05-10T00:00:00Z")

    fun toMnemonic(seed: ByteArray): String {
        require(seed.size == SEED_SIZE_BYTES) { "Seedify requires a 32-byte Ed25519 seed" }
        return Bip39Mnemonics.toMnemonics(seed).joinToString(" ")
    }

    /**
     * Generates a Brave Sync code from the Vault Key backup (`vkbckp`) associated account by
     * encoding its seed as a normal 24-word BIP-39 mnemonic, then appending Brave's date-derived
     * 25th word.
     *
     * This intentionally does not use Seedify's legacy Brave-prefixed mnemonic
     * derivation. [now] is injectable so callers and tests can reproduce a code
     * for a particular Brave Sync day.
     */
    fun toBraveSyncCode(seed: ByteArray, now: Instant = Instant.now()): String =
        "${toVaultKeyBackupMnemonic(seed)} ${braveSync25thWord(now)}"

    /** The 24-word mnemonic of Seedify's deterministic Vault Key backup (`vkbckp`) associated account. */
    fun toVaultKeyBackupMnemonic(seed: ByteArray): String =
        toMnemonic(deriveAssociatedAccountSeed(seed, VAULT_KEY_BACKUP_ASSOCIATED_ACCOUNT))

    /** One-based BIP-39 positions for the 24 recovery words in the Brave Sync code. */
    fun toVaultKeyBackupWordIndices(seed: ByteArray): List<Int> {
        return toVaultKeyBackupMnemonic(seed).split(" ").map { word ->
            Bip39Mnemonics.englishWordlist.indexOf(word).also { index ->
                check(index >= 0) { "Generated Brave Sync word is absent from the BIP-39 word list" }
            } + 1
        }
    }

    fun fromSshKey(privateKeyPem: String, passphrase: CharArray): String =
        toMnemonic(SshKeyCodec.parse(privateKeyPem, passphrase).seed)

    private fun deriveAssociatedAccountSeed(seed: ByteArray, associatedAccount: String): ByteArray {
        require(seed.size == SEED_SIZE_BYTES) { "Seedify requires a 32-byte Ed25519 seed" }
        val associatedAccountHash = sha256(associatedAccount.encodeToByteArray())
        return sha256(associatedAccountHash + seed)
    }

    /**
     * Date-derived final word shared by recovery, which already has the first 24 words.
     *
     * The day index wraps modulo the 2,048-word list, mirroring Brave's own
     * `TimeLimitedWords::GetWordByIndex` (`index = index % BIP39_WORDLIST_LEN`) and its parser.
     * Without the wrap, every day from day index 2048 onwards (2027-12-18 UTC, but rolling at
     * noon UTC because both implementations round to the nearest day) would index past the end
     * of the list and throw, breaking Brave backup and recovery for all users. Brave wraps, so
     * wrapping also keeps Vault's codes interoperable with real Brave clients. The word is a
     * freshness token only: it is not an input to the auth-key or Nigori derivations.
     */
    fun braveSync25thWord(now: Instant = Instant.now()): String {
        val elapsedMillis = now.toEpochMilli() - braveSyncEpoch.toEpochMilli()
        val dayIndex = Math.round(elapsedMillis.toDouble() / MILLIS_PER_DAY).toInt()
        require(dayIndex >= 0) { "Date is before the Brave Sync epoch" }
        val wordlist = Bip39Mnemonics.englishWordlist
        return wordlist[dayIndex % wordlist.size]
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
