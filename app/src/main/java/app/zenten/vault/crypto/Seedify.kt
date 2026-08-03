package app.zenten.vault.crypto

import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import java.security.MessageDigest
import java.time.Instant

/** ZenTen's no-prefix path: the raw 32-byte Ed25519 seed is BIP-39 entropy. */
object Seedify {
    private const val SEED_SIZE_BYTES = 32
    private const val BRAVE_SUB_ACCOUNT = "brave"
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    private val braveSyncEpoch = Instant.parse("2022-05-10T00:00:00Z")

    fun toMnemonic(seed: ByteArray): String {
        require(seed.size == SEED_SIZE_BYTES) { "Seedify requires a 32-byte Ed25519 seed" }
        return Bip39Mnemonics.toMnemonics(seed).joinToString(" ")
    }

    /**
     * Generates a Brave Sync code by deriving Seedify's `brave` sub-account,
     * encoding its seed as a normal 24-word BIP-39 mnemonic, then appending
     * Brave's date-derived 25th word.
     *
     * This intentionally does not use Seedify's legacy Brave-prefixed mnemonic
     * derivation. [now] is injectable so callers and tests can reproduce a code
     * for a particular Brave Sync day.
     */
    fun toBraveSyncCode(seed: ByteArray, now: Instant = Instant.now()): String {
        val braveSeed = deriveSubAccountSeed(seed, BRAVE_SUB_ACCOUNT)
        return "${toMnemonic(braveSeed)} ${braveSync25thWord(now)}"
    }

    fun fromSshKey(privateKeyPem: String, passphrase: CharArray): String =
        toMnemonic(SshKeyCodec.parse(privateKeyPem, passphrase).seed)

    private fun deriveSubAccountSeed(seed: ByteArray, subAccount: String): ByteArray {
        require(seed.size == SEED_SIZE_BYTES) { "Seedify requires a 32-byte Ed25519 seed" }
        val subAccountHash = sha256(subAccount.encodeToByteArray())
        return sha256(subAccountHash + seed)
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
