package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.components.VaultPillButtonStyle
import app.zenten.vault.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch

/** zxcvbn dictionary identifiers, which arrive as snake_case names and are shown as prose. */
private const val DICTIONARY_PASSWORDS = "passwords"
private const val DICTIONARY_ENGLISH_WIKIPEDIA = "english_wikipedia"
private const val DICTIONARY_US_TV_AND_FILM = "us_tv_and_film"
private const val DICTIONARY_SURNAMES = "surnames"
private const val DICTIONARY_FEMALE_NAMES = "female_names"
private const val DICTIONARY_MALE_NAMES = "male_names"

/**
 * Explains a passphrase's rating: its score, how long it would take to crack, what the estimator
 * recognized, and what to change.
 *
 * Presented as a bottom sheet rather than inline because the passphrase step has no room for it,
 * and because it is a "why?" surface rather than something every user reads while typing. It
 * stays free of passphrase content by construction: everything it renders comes from
 * [PassphraseStrength], which never carries the matched text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassphraseStrengthDetailsSheet(
    strength: PassphraseStrength,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        PassphraseStrengthDetailsContent(
            strength = strength,
            onClose = {
                // Wait for the sheet's own exit animation before removing it, so closing looks
                // the same as swiping the sheet away.
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            },
        )
    }
}

/**
 * The sheet's body, kept separate from [PassphraseStrengthDetailsSheet] so the modal chrome — the
 * drag handle and the exit animation — is not tangled up with the content it presents.
 */
@Composable
internal fun PassphraseStrengthDetailsContent(
    strength: PassphraseStrength,
    onClose: () -> Unit,
) {
    val accent = strengthAccentColor(strength.score)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.create_vault_key_passphrase_details_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        // What the rating is, then where it sits on the scale.
        Text(
            text = stringResource(passphraseStrengthBand(strength.score).labelRes()),
            style = MaterialTheme.typography.titleLarge,
            color = accent,
        )
        Spacer(Modifier.height(10.dp))
        PassphraseStrengthBar(score = strength.score)

        // The band is the verdict; the score is the number behind it, directly under the bar.
        SheetValue(
            label = stringResource(R.string.create_vault_key_passphrase_details_score),
            value = stringResource(
                R.string.create_vault_key_passphrase_details_score_value,
                strength.score,
                PassphraseStrengthSegmentCount - 1,
            ),
        )

        SheetSection(stringResource(R.string.create_vault_key_passphrase_details_time_to_crack)) {
            SheetValue(
                label = stringResource(R.string.create_vault_key_passphrase_details_attack_slow_hash),
                value = crackTimeText(crackTimeOf(strength.offlineSlowHashSeconds)),
            )
            SheetValue(
                label = stringResource(R.string.create_vault_key_passphrase_details_attack_fast_hash),
                value = crackTimeText(crackTimeOf(strength.offlineFastHashSeconds)),
            )
        }

        SheetSection(stringResource(R.string.create_vault_key_passphrase_details_noticed)) {
            if (strength.findings.isEmpty()) {
                SheetBodyText(stringResource(R.string.create_vault_key_passphrase_details_nothing_noticed))
            } else {
                // Every entry the estimator found, in passphrase order, including the stretch it
                // could not explain — so the list accounts for the whole passphrase.
                strength.findings.forEach { finding -> FindingItem(finding) }
            }
        }

        // Omitted entirely when there is nothing to suggest, which is the good case.
        if (strength.suggestions.isNotEmpty()) {
            SheetSection(stringResource(R.string.create_vault_key_passphrase_details_improve)) {
                strength.suggestions.forEach { suggestion ->
                    BulletedItem { SheetBodyText(suggestion) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        VaultPillButton(
            label = stringResource(R.string.create_vault_key_passphrase_details_close),
            style = VaultPillButtonStyle.Secondary,
            onClick = onClose,
        )
    }
}

/** Section heading plus its content, separated from the previous section by a rule. */
@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    content()
}

/** Label on the left, value right-aligned so a column of values lines up. */
@Composable
private fun SheetValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Free-standing line of prose inside a section. */
@Composable
private fun SheetBodyText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

/** Bulleted list item, so several entries read as a list rather than as a paragraph. */
@Composable
private fun BulletedItem(content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            Modifier
                .padding(top = 7.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                .height(5.dp)
                .width(5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), content = content)
    }
}

/**
 * One match from the decomposition: the matched text and the pattern zxcvbn matched it as, then what
 * that pattern means, whatever else the estimator knows about it, and what it cost.
 *
 * The matched text is shown on purpose, so a reader can see which part of their own passphrase was
 * recognized. The pattern alone leaves `alice@` indistinguishable from any other six characters.
 */
@Composable
private fun FindingItem(finding: PassphraseFinding) {
    val token = stringResource(R.string.create_vault_key_passphrase_finding_token, finding.token)
    val characters = pluralStringResource(
        R.plurals.create_vault_key_passphrase_finding_characters,
        finding.characterCount,
        finding.characterCount,
    )
    val guesses = stringResource(
        R.string.create_vault_key_passphrase_finding_guesses,
        formatGuessCount(finding.guesses),
    )

    // The matched text leads, because that is what the reader came for; the pattern that matched it
    // sits beside it, and everything else moves to the second line so the first stays scannable.
    val summary = "$token · ${findingPatternLabel(findingPattern(finding.kind))}"
    val explanationParts =
        listOf(findingLabel(finding.kind), characters) + findingDetails(finding) + guesses

    BulletedItem {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SheetBodyText(text = explanationParts.joinToString(" · "), muted = true)
    }
}

/** zxcvbn's own name for the pattern behind a finding. */
@Composable
private fun findingPatternLabel(pattern: PassphraseFindingPattern): String = stringResource(
    when (pattern) {
        PassphraseFindingPattern.Dictionary -> R.string.create_vault_key_passphrase_pattern_dictionary
        PassphraseFindingPattern.Spatial -> R.string.create_vault_key_passphrase_pattern_spatial
        PassphraseFindingPattern.Repeat -> R.string.create_vault_key_passphrase_pattern_repeat
        PassphraseFindingPattern.Sequence -> R.string.create_vault_key_passphrase_pattern_sequence
        PassphraseFindingPattern.Regex -> R.string.create_vault_key_passphrase_pattern_regex
        PassphraseFindingPattern.Date -> R.string.create_vault_key_passphrase_pattern_date
        PassphraseFindingPattern.Bruteforce -> R.string.create_vault_key_passphrase_pattern_bruteforce
    },
)

/**
 * Per-pattern attributes worth naming: which list matched and where in it, which keyboard and how
 * many direction changes, how many repeats, and which way a run goes.
 *
 * Date components are not listed because the matched text on the row above already contains them,
 * so a separate year line would only repeat it.
 */
@Composable
private fun findingDetails(finding: PassphraseFinding): List<String> {
    val dictionaryName = finding.dictionaryName
    val dictionaryRank = finding.dictionaryRank
    val dictionary = if (dictionaryName != null && dictionaryRank != null) {
        stringResource(
            R.string.create_vault_key_passphrase_dictionary_rank,
            dictionaryNameText(dictionaryName),
            dictionaryRank,
        )
    } else {
        null
    }

    val keyboardName = finding.keyboardName
    val keyboard = if (keyboardName != null) {
        stringResource(R.string.create_vault_key_passphrase_finding_keyboard, keyboardName)
    } else {
        null
    }

    val keyboardTurns = finding.keyboardTurns
    val turns = if (keyboardTurns != null) {
        pluralStringResource(
            R.plurals.create_vault_key_passphrase_finding_turns,
            keyboardTurns,
            keyboardTurns,
        )
    } else {
        null
    }

    val repeatCount = finding.repeatCount
    val repeats = if (repeatCount != null) {
        pluralStringResource(
            R.plurals.create_vault_key_passphrase_finding_repeats,
            repeatCount,
            repeatCount,
        )
    } else {
        null
    }

    val ascending = finding.sequenceAscending
    val direction = if (ascending != null) {
        stringResource(
            if (ascending) {
                R.string.create_vault_key_passphrase_finding_ascending
            } else {
                R.string.create_vault_key_passphrase_finding_descending
            },
        )
    } else {
        null
    }

    return listOfNotNull(dictionary, keyboard, turns, repeats, direction)
}

/** The success/error colour for a score, shared by the sheet and the meter. */
@Composable
internal fun strengthAccentColor(score: Int): Color =
    if (score >= VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE) {
        LocalExtendedColors.current.success.color
    } else {
        MaterialTheme.colorScheme.error
    }

/** Renders a [CrackTime] with the app's own wording, so plurals stay correct. */
@Composable
internal fun crackTimeText(crackTime: CrackTime): String = when (crackTime.unit) {
    CrackTimeUnit.LessThanASecond ->
        stringResource(R.string.create_vault_key_passphrase_crack_time_instant)

    CrackTimeUnit.Centuries ->
        stringResource(R.string.create_vault_key_passphrase_crack_time_centuries)

    CrackTimeUnit.Seconds -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_seconds,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )

    CrackTimeUnit.Minutes -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_minutes,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )

    CrackTimeUnit.Hours -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_hours,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )

    CrackTimeUnit.Days -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_days,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )

    CrackTimeUnit.Months -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_months,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )

    CrackTimeUnit.Years -> pluralStringResource(
        R.plurals.create_vault_key_passphrase_crack_time_years,
        crackTime.value.toInt(),
        crackTime.value.toInt(),
    )
}

/** Plain-language name for a recognized kind of structure. */
@Composable
private fun findingLabel(kind: PassphraseFindingKind): String = stringResource(
    when (kind) {
        PassphraseFindingKind.CommonPassword -> R.string.create_vault_key_passphrase_finding_common_password
        PassphraseFindingKind.CommonWord -> R.string.create_vault_key_passphrase_finding_common_word
        PassphraseFindingKind.ReversedWord -> R.string.create_vault_key_passphrase_finding_reversed_word
        PassphraseFindingKind.SubstitutedWord -> R.string.create_vault_key_passphrase_finding_substituted_word
        PassphraseFindingKind.KeyboardPattern -> R.string.create_vault_key_passphrase_finding_keyboard_pattern
        PassphraseFindingKind.RepeatedCharacters -> R.string.create_vault_key_passphrase_finding_repeated_characters
        PassphraseFindingKind.PredictableSequence ->
            R.string.create_vault_key_passphrase_finding_predictable_sequence

        PassphraseFindingKind.Date -> R.string.create_vault_key_passphrase_finding_date
        PassphraseFindingKind.RecentYear -> R.string.create_vault_key_passphrase_finding_recent_year
        PassphraseFindingKind.UnpredictableCharacters ->
            R.string.create_vault_key_passphrase_finding_unpredictable_characters
    },
)

/**
 * Prose name for a zxcvbn dictionary identifier.
 *
 * An unrecognized identifier is shown verbatim rather than hidden, so a library update that adds
 * a dictionary is visible instead of silently blank.
 */
@Composable
private fun dictionaryNameText(name: String): String = when (name) {
    DICTIONARY_PASSWORDS -> stringResource(R.string.create_vault_key_passphrase_dictionary_passwords)
    DICTIONARY_ENGLISH_WIKIPEDIA -> stringResource(R.string.create_vault_key_passphrase_dictionary_english_wikipedia)
    DICTIONARY_US_TV_AND_FILM -> stringResource(R.string.create_vault_key_passphrase_dictionary_us_tv_and_film)
    DICTIONARY_SURNAMES -> stringResource(R.string.create_vault_key_passphrase_dictionary_surnames)
    DICTIONARY_FEMALE_NAMES -> stringResource(R.string.create_vault_key_passphrase_dictionary_female_names)
    DICTIONARY_MALE_NAMES -> stringResource(R.string.create_vault_key_passphrase_dictionary_male_names)
    else -> name
}
