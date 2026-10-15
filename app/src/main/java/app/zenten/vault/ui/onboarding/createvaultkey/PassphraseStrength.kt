package app.zenten.vault.ui.onboarding.createvaultkey

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One candidate passphrase's zxcvbn result, reduced to the parts the UI shows.
 *
 * This model carries passphrase fragments in each [PassphraseFinding.token], so the sheet can show
 * which part of the passphrase the estimator recognized. It therefore holds secret material while
 * the step is on screen, lives only in the graph-scoped ViewModel, and is dropped when the step is
 * left — the same lifetime as the passphrase itself. It is never persisted and never logged.
 */
data class PassphraseStrength(
    /** zxcvbn bucket, 0 (trivially guessable) through 4 (very unguessable). */
    val score: Int,
    /**
     * Seconds to crack offline against a stolen backup when the backup is protected by a slow,
     * modern hash — the `bcrypt_pbkdf` situation, and therefore the realistic figure.
     */
    val offlineSlowHashSeconds: Double,
    /**
     * Seconds to crack the same backup if it were protected by only a fast hash. Shown next to
     * [offlineSlowHashSeconds] so the value of the slow hash is visible rather than asserted.
     */
    val offlineFastHashSeconds: Double,
    /** zxcvbn's own improvement advice, all of it. */
    val suggestions: List<String>,
    /** The estimator's full decomposition of the passphrase. */
    val findings: List<PassphraseFinding>,
)

/**
 * The kinds of structure zxcvbn can recognize.
 *
 * An enum rather than zxcvbn's free text so the wording stays in `strings.xml`; the estimator's
 * messages are English-only library strings. [UnpredictableCharacters] is the one entry that is
 * not a weakness: it is the stretch zxcvbn could not explain, kept so the list adds up to the
 * whole passphrase instead of silently skipping part of it.
 */
enum class PassphraseFindingKind {
    CommonPassword,
    CommonWord,
    ReversedWord,
    SubstitutedWord,
    KeyboardPattern,
    RepeatedCharacters,
    PredictableSequence,
    Date,
    RecentYear,
    UnpredictableCharacters,
}

/**
 * The zxcvbn pattern behind a finding, named as the estimator names it.
 *
 * Shown alongside [PassphraseFindingKind] because the two answer different questions: the pattern
 * is what the estimator matched, and the kind is what that pattern means for a reader.
 */
enum class PassphraseFindingPattern {
    Dictionary,
    Spatial,
    Repeat,
    Sequence,
    Regex,
    Date,
    Bruteforce,
}

/**
 * Maps a finding back to the pattern it was classified from.
 *
 * Derived rather than stored: the classification only ever narrows a pattern, so the reverse is
 * total — every dictionary finding came from `Dictionary`, and nothing else produces
 * [PassphraseFindingKind.UnpredictableCharacters] but `Bruteforce`. A test pins the whole table so
 * a new kind cannot silently acquire the wrong label.
 */
internal fun findingPattern(kind: PassphraseFindingKind): PassphraseFindingPattern = when (kind) {
    PassphraseFindingKind.CommonPassword,
    PassphraseFindingKind.CommonWord,
    PassphraseFindingKind.ReversedWord,
    PassphraseFindingKind.SubstitutedWord,
    -> PassphraseFindingPattern.Dictionary

    PassphraseFindingKind.KeyboardPattern -> PassphraseFindingPattern.Spatial
    PassphraseFindingKind.RepeatedCharacters -> PassphraseFindingPattern.Repeat
    PassphraseFindingKind.PredictableSequence -> PassphraseFindingPattern.Sequence
    PassphraseFindingKind.Date -> PassphraseFindingPattern.Date
    PassphraseFindingKind.RecentYear -> PassphraseFindingPattern.Regex
    PassphraseFindingKind.UnpredictableCharacters -> PassphraseFindingPattern.Bruteforce
}

/**
 * One entry in the decomposition, with whatever the estimator knows about it.
 *
 * [token] is the matched text, which is a literal substring of the passphrase. It is carried on
 * purpose: the sheet's job is to show *which* part of the passphrase was recognized, and a match
 * described only by its pattern and span leaves the reader unable to tell `alice@` from any other
 * six-character run. The rest of the passphrase (`Strength.password`) and the de-obfuscated word
 * behind a match (`Match.matchedWord`) are still not carried.
 *
 * Date components are not carried separately because [token] already contains them.
 *
 * The trailing fields are per-pattern: which are set depends on [kind], mirroring zxcvbn's own
 * flat `Match`.
 */
data class PassphraseFinding(
    val kind: PassphraseFindingKind,
    /** The matched text: a literal substring of the passphrase. */
    val token: String,
    /** Inclusive index of the first matched character. */
    val startIndex: Int,
    /** Inclusive index of the last matched character. */
    val endIndex: Int,
    /** Guesses needed for this piece alone; lower means more predictable. */
    val guesses: Double,
    /** Which ranked list matched, and where in it for the dictionary kinds. */
    val dictionaryName: String? = null,
    val dictionaryRank: Int? = null,
    /** Keyboard layout and direction changes for [PassphraseFindingKind.KeyboardPattern]. */
    val keyboardName: String? = null,
    val keyboardTurns: Int? = null,
    /** How many times the unit repeats for [PassphraseFindingKind.RepeatedCharacters]. */
    val repeatCount: Int? = null,
    /** Run direction for [PassphraseFindingKind.PredictableSequence]. */
    val sequenceAscending: Boolean? = null,
) {
    val characterCount: Int get() = endIndex - startIndex + 1
}

/** A crack time broken into a count and a unit, so the UI owns the wording and plural rules. */
data class CrackTime(val value: Long, val unit: CrackTimeUnit)

enum class CrackTimeUnit {
    LessThanASecond,
    Seconds,
    Minutes,
    Hours,
    Days,
    Months,
    Years,
    Centuries,
}

private const val SECONDS_PER_MINUTE = 60.0
private const val SECONDS_PER_HOUR = 3_600.0
private const val SECONDS_PER_DAY = 86_400.0
private const val SECONDS_PER_MONTH = 2_629_800.0
private const val SECONDS_PER_YEAR = 31_557_600.0

/** Above this, the exact figure stops meaning anything, so the UI says "centuries". */
private const val SECONDS_PER_CENTURY = SECONDS_PER_YEAR * 100

/**
 * Reduces a crack time in seconds to a count and a unit.
 *
 * zxcvbn ships its own display strings, but they are English-only library text; deriving the
 * unit here keeps the sheet's wording and plurals in `strings.xml` with the rest of the app.
 */
internal fun crackTimeOf(seconds: Double): CrackTime = when {
    seconds < 1.0 -> CrackTime(0, CrackTimeUnit.LessThanASecond)
    seconds < SECONDS_PER_MINUTE -> CrackTime(seconds.roundToLong(), CrackTimeUnit.Seconds)
    seconds < SECONDS_PER_HOUR -> CrackTime((seconds / SECONDS_PER_MINUTE).roundToLong(), CrackTimeUnit.Minutes)
    seconds < SECONDS_PER_DAY -> CrackTime((seconds / SECONDS_PER_HOUR).roundToLong(), CrackTimeUnit.Hours)
    seconds < SECONDS_PER_MONTH -> CrackTime((seconds / SECONDS_PER_DAY).roundToLong(), CrackTimeUnit.Days)
    seconds < SECONDS_PER_YEAR -> CrackTime((seconds / SECONDS_PER_MONTH).roundToLong(), CrackTimeUnit.Months)
    seconds < SECONDS_PER_CENTURY -> CrackTime((seconds / SECONDS_PER_YEAR).roundToLong(), CrackTimeUnit.Years)
    else -> CrackTime(0, CrackTimeUnit.Centuries)
}

private const val PLAIN_GUESS_COUNT_LIMIT = 10_000.0

/**
 * Renders a guess count compactly: plain below [PLAIN_GUESS_COUNT_LIMIT], otherwise one
 * significant decimal in scientific notation, because a raw `4.5600001E11` tells a reader
 * nothing.
 *
 * Built from arithmetic instead of `String.format` so the output cannot pick up the device
 * locale's decimal separator while the surrounding sheet is English.
 */
internal fun formatGuessCount(guesses: Double): String {
    if (guesses < PLAIN_GUESS_COUNT_LIMIT) return guesses.roundToLong().toString()
    val exponent = floor(log10(guesses)).toInt()
    val mantissa = guesses / 10.0.pow(exponent)
    val roundedMantissa = (mantissa * 10).roundToLong() / 10.0
    return "$roundedMantissa × 10^$exponent"
}
