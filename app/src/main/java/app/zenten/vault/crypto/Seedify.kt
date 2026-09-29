package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import java.security.MessageDigest
import java.time.Instant

/** ZenTen's no-prefix path: the raw 32-byte Ed25519 seed is BIP-39 entropy. */
object Seedify {
    private const val SEED_SIZE_BYTES = 32
    private const val BRAVE_ASSOCIATED_ACCOUNT = "brave"
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    private val braveSyncEpoch = Instant.parse("2022-05-10T00:00:00Z")

    fun toMnemonic(seed: ByteArray): String {
        require(seed.size == SEED_SIZE_BYTES) { "Seedify requires a 32-byte Ed25519 seed" }
        return Bip39Mnemonics.toMnemonics(seed).joinToString(" ")
    }

    /**
     * Generates a Brave Sync code by deriving Seedify's `brave` associated account,
     * encoding its seed as a normal 24-word BIP-39 mnemonic, then appending
     * Brave's date-derived 25th word.
     *
     * This intentionally does not use Seedify's legacy Brave-prefixed mnemonic
     * derivation. [now] is injectable so callers and tests can reproduce a code
     * for a particular Brave Sync day.
     */
    fun toBraveSyncCode(seed: ByteArray, now: Instant = Instant.now()): String =
        "${toBraveAccountMnemonic(seed)} ${braveSync25thWord(now)}"

    /** The 24-word mnemonic of Seedify's deterministic `brave` associated account. */
    fun toBraveAccountMnemonic(seed: ByteArray): String =
        toMnemonic(deriveAssociatedAccountSeed(seed, BRAVE_ASSOCIATED_ACCOUNT))

    /** One-based BIP-39 positions for the 24 recovery words in the Brave Sync code. */
    fun toBraveSyncWordIndices(seed: ByteArray): List<Int> {
        return toBraveAccountMnemonic(seed).split(" ").map { word ->
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

    private fun braveSync25thWord(now: Instant): String {
        val elapsedMillis = now.toEpochMilli() - braveSyncEpoch.toEpochMilli()
        val dayIndex = Math.round(elapsedMillis.toDouble() / MILLIS_PER_DAY).toInt()
        require(dayIndex >= 0) { "Date is before the Brave Sync epoch" }
        val wordlist = Bip39Mnemonics.englishWordlist
        require(dayIndex < wordlist.size) { "Brave Sync word index is out of bounds: $dayIndex" }
        return wordlist[dayIndex]
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
