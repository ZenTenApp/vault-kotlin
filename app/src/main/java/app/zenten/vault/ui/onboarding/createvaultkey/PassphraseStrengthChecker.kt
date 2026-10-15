package app.zenten.vault.ui.onboarding.createvaultkey

import com.nulabinc.zxcvbn.Pattern
import com.nulabinc.zxcvbn.Strength
import com.nulabinc.zxcvbn.WipeableString
import com.nulabinc.zxcvbn.Zxcvbn
import com.nulabinc.zxcvbn.matchers.Match
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The zxcvbn dictionary that holds its ranked list of real-world passwords. */
private const val COMMON_PASSWORDS_DICTIONARY = "passwords"

/**
 * Estimates how hard [passphrase] is to guess.
 *
 * Implementations run a CPU-bound pattern search and must keep it off the main
 * thread; the value they return is safe to display.
 */
interface PassphraseStrengthChecker {
    suspend fun measure(passphrase: CharSequence): PassphraseStrength
}

/**
 * [PassphraseStrengthChecker] backed by zxcvbn, which scores a passphrase by finding
 * the cheapest way an attacker could explain it instead of counting character classes.
 *
 * That difference matters here because the VaultKey passphrase is the only secret
 * protecting a key that is also published to Nostr relays and Brave Sync. An attacker
 * who obtains a backup can attack it offline with no rate limit, so the passphrase has
 * to survive an unthrottled guessing campaign. A character-class rule gets this
 * backwards: it accepts `Password1!` and rejects a long passphrase that happens to
 * contain no digit.
 *
 * One instance is shared app-wide because the first call parses roughly 800 KB of
 * bundled dictionary data; paying that once keeps it off every passphrase keystroke.
 */
@Singleton
class ZxcvbnPassphraseStrengthChecker @Inject constructor() : PassphraseStrengthChecker {

    /**
     * Terms that are guessable in this product specifically even though they are not
     * famous passwords. zxcvbn prices a dictionary match by how rare the word is, so
     * declaring its own product names common is what makes `vaultkey1234!` score as
     * the guessable string it really is.
     */
    private val productTerms = listOf("zenten", "vault", "vaultkey")

    private val zxcvbn: Zxcvbn by lazy { Zxcvbn() }

    override suspend fun measure(passphrase: CharSequence): PassphraseStrength {
        // Bound the work before matching: zxcvbn's search grows faster than linearly, so
        // an adversarial paste would otherwise pin a shared worker thread for seconds.
        //
        // Anything past the bound is refused rather than measured by prefix. Measuring a
        // prefix could report a *stronger* score than the whole string deserves — a
        // repeating pattern cut mid-repeat loses the repeat match and scores high — and
        // this rule must never be satisfied by accident. Reporting the weakest bucket is
        // the fail-closed answer for input that cannot be measured within the budget.
        if (passphrase.length > MAX_MEASURED_LENGTH) {
            return PassphraseStrength(
                score = 0,
                offlineSlowHashSeconds = 0.0,
                offlineFastHashSeconds = 0.0,
                suggestions = emptyList(),
                findings = emptyList(),
            )
        }
        return withContext(Dispatchers.Default) {
            // A wipeable buffer lets us clear this object's own copy of the passphrase
            // once matching finishes. The library still builds short-lived internal
            // strings while matching; only our copy is guaranteed to be wiped.
            val wipeable = WipeableString(passphrase)
            try {
                passphraseStrengthOf(zxcvbn.measure(wipeable, productTerms))
            } finally {
                wipeable.wipe()
            }
        }
    }

    private companion object {
        /** Keeps a worst-case measurement near 150 ms even for adversarial input. */
        const val MAX_MEASURED_LENGTH = 256
    }
}

/**
 * Projects zxcvbn's raw result onto the non-secret model the UI renders.
 *
 * `Match.token` — the matched text — is carried through, because the sheet has to show which part
 * of the passphrase was recognized. Everything else that reproduces the passphrase is still
 * dropped: `Strength.password`, `Match.matchedWord` (the de-obfuscated word behind a substitution),
 * and `Match.baseToken`, `Match.regexMatch`, `Match.baseMatches`, and the raw l33t `Match.sub` map.
 * What else survives is the crack-time inputs, the library's advice, and each match's kind, span,
 * cost, and per-pattern attributes.
 */
internal fun passphraseStrengthOf(strength: Strength): PassphraseStrength = PassphraseStrength(
    score = strength.score,
    offlineSlowHashSeconds = strength.crackTimeSeconds.offlineSlowHashing1e4perSecond,
    offlineFastHashSeconds = strength.crackTimeSeconds.offlineFastHashing1e10PerSecond,
    suggestions = strength.feedback?.suggestions.orEmpty(),
    // Kept in the estimator's own order, so the list reads left to right through the passphrase
    // and its spans add up to the whole string. Nothing is merged or dropped, including the
    // stretch zxcvbn could not explain.
    findings = strength.sequence.orEmpty().map { it.toFinding() },
)

/** Classifies one match and carries over whatever the estimator knows about it. */
private fun Match.toFinding(): PassphraseFinding {
    val kind = when (pattern) {
        Pattern.Dictionary -> when {
            // A reversed or substituted word is the same word underneath, and saying which is
            // more actionable than the generic "common word".
            l33t -> PassphraseFindingKind.SubstitutedWord
            reversed -> PassphraseFindingKind.ReversedWord
            dictionaryName == COMMON_PASSWORDS_DICTIONARY -> PassphraseFindingKind.CommonPassword
            else -> PassphraseFindingKind.CommonWord
        }

        Pattern.Spatial -> PassphraseFindingKind.KeyboardPattern
        Pattern.Repeat -> PassphraseFindingKind.RepeatedCharacters
        Pattern.Sequence -> PassphraseFindingKind.PredictableSequence
        Pattern.Date -> PassphraseFindingKind.Date
        Pattern.Regex -> PassphraseFindingKind.RecentYear
        // Bruteforce, and anything the library adds later: the stretch it could not explain.
        else -> PassphraseFindingKind.UnpredictableCharacters
    }

    val isDictionary = pattern == Pattern.Dictionary
    val isSpatial = pattern == Pattern.Spatial
    val isRepeat = pattern == Pattern.Repeat
    val isSequence = pattern == Pattern.Sequence

    return PassphraseFinding(
        kind = kind,
        // The matched text itself, so the sheet can name the sequence rather than describe it.
        token = token?.toString().orEmpty(),
        startIndex = i,
        endIndex = j,
        guesses = guesses ?: Double.MAX_VALUE,
        dictionaryName = if (isDictionary) dictionaryName else null,
        // zxcvbn leaves rank at 0 outside a dictionary match, so it is only meaningful here.
        dictionaryRank = if (isDictionary) rank else null,
        keyboardName = if (isSpatial) graph else null,
        keyboardTurns = if (isSpatial) turns else null,
        repeatCount = if (isRepeat) this.repeatCount else null,
        sequenceAscending = if (isSequence) ascending else null,
    )
}
