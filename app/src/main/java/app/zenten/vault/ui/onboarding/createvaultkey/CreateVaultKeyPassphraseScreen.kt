package app.zenten.vault.ui.onboarding.createvaultkey

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.zenten.vault.R
import app.zenten.vault.ui.VaultDestinations
import app.zenten.vault.ui.components.VaultPillButton
import app.zenten.vault.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Step 2 of the in-app **Create VaultKey** flow: choose the passphrase that
 * will protect the generated VaultKey.
 *
 * The step shows two things, stacked, under the input: the length requirement
 * and a strength meter. They are separate rows on purpose — they answer
 * different questions ("is it long enough?" and "is it hard to guess?") and a
 * side-by-side grid made them read as one sentence.
 *
 * The meter's label names the band in words; the concrete crack-time estimate
 * lives in [PassphraseStrengthDetailsSheet], which the info button beside the bar
 * opens. Keeping the number out of the label leaves the row scannable, and the
 * whole explanation lives in the sheet rather than inline because this step has no
 * room for it.
 *
 * The length rule is checked here on every keystroke because it is free, and it
 * leaves the panel two seconds after it is met so the meter becomes the focus.
 * Guessability cannot be checked here: it needs the shared zxcvbn estimator, so
 * the text is handed to the graph-scoped ViewModel and the measured result
 * comes back as state. That measurement is discarded when this step leaves the
 * screen, and **Confirm** stays hidden until both requirements are met.
 */
@Composable
fun CreateVaultKeyPassphraseScreen(navController: NavHostController) {
    var passphrase by remember { mutableStateOf("") }
    var detailsVisible by remember { mutableStateOf(false) }
    val viewModel = rememberCreateVaultKeyViewModel(navController)
    val strength by viewModel.passphraseStrength.collectAsState()
    val requirements = VaultKeyPassphrasePolicy.evaluate(passphrase, strength)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Keeps the successful length rule on screen long enough to be noticed, then
    // collapses it so only the strength meter is left. It returns immediately if the
    // passphrase drops back below the floor.
    var showLengthRule by remember { mutableStateOf(true) }
    LaunchedEffect(requirements.hasMinimumLength) {
        if (requirements.hasMinimumLength) {
            delay(RULE_SUCCESS_DISPLAY_MILLIS)
            showLengthRule = false
        } else {
            showLengthRule = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearPassphraseStrength() }
    }

    CreateVaultKeyScaffold(
        title = stringResource(R.string.create_vault_key_passphrase_title),
        heroIcon = R.drawable.hero_icon_passphrase,
        heroIconDescription = stringResource(R.string.create_vault_key_passphrase_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(28.dp))

            PassphraseField(
                label = null,
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    viewModel.onPassphraseChanged(it)
                },
            )

            // Vertical inset is half the horizontal one so the length rule and the meter sit
            // closer to the field; together with StrengthMeterTopSpacing this puts the bar about
            // 16dp below the input, down from about 32dp.
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)) {
                AnimatedVisibility(
                    visible = showLengthRule,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    PassphraseRequirement(
                        label = stringResource(
                            R.string.create_vault_key_passphrase_rule_min_length,
                            VaultKeyPassphrasePolicy.MINIMUM_LENGTH,
                        ),
                        satisfied = requirements.hasMinimumLength,
                    )
                }

                // Only rendered once a measurement exists. Before that there is nothing to
                // report, and below the length floor the estimator is deliberately not run
                // at all, so an empty meter would either mislead or duplicate the rule above.
                strength?.let { measured ->
                    Spacer(Modifier.height(StrengthMeterTopSpacing))
                    PassphraseStrengthMeter(
                        score = measured.score,
                        onShowDetails = {
                            // Drop the keyboard first so the sheet is not squeezed behind it.
                            focusManager.clearFocus()
                            detailsVisible = true
                        },
                    )
                }
            }
        },
        actions = {
            AnimatedVisibility(
                visible = requirements.allSatisfied,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                VaultPillButton(
                    label = stringResource(R.string.create_vault_key_passphrase_confirm),
                    onClick = {
                        // Recheck in the ViewModel so policy enforcement does not depend on
                        // UI state. Measuring guessability is CPU-bound, so confirmation
                        // runs in a coroutine rather than on the main thread.
                        scope.launch {
                            if (viewModel.choosePassphrase(passphrase)) {
                                navController.navigate(VaultDestinations.CREATE_VAULT_KEY_CONFIRM_PASSPHRASE)
                            }
                        }
                    },
                )
            }
        },
    )

    val measured = strength
    if (detailsVisible && measured != null) {
        PassphraseStrengthDetailsSheet(
            strength = measured,
            onDismiss = { detailsVisible = false },
        )
    }
}

/** How long a satisfied requirement stays visible before it collapses out of the panel. */
private const val RULE_SUCCESS_DISPLAY_MILLIS = 2_000L

/**
 * Gap between the length requirement and the strength meter, so the two read as separate items.
 * Halved from 14dp together with the panel's vertical inset, which halves the distance from the
 * input field to the bar.
 */
private val StrengthMeterTopSpacing = 7.dp

/** Height of one strength segment; thin enough to read as a meter rather than a bar chart. */
private val StrengthSegmentHeight = 8.dp

/** Corner radius that turns each flat segment into a pill at [StrengthSegmentHeight]. */
private val StrengthSegmentCorner = 4.dp

/** Gap between segments, wide enough that the filled segments stay individually countable. */
private val StrengthSegmentGap = 6.dp

/** Gap between the bar and its info button. */
private val StrengthDetailsIconSpacing = 4.dp

/** Tappable box for the info button; Material still enforces a 48dp touch target around it. */
private val StrengthDetailsIconButtonSize = 36.dp

/** Drawn size of the info glyph itself. */
private val StrengthDetailsIconSize = 20.dp

/**
 * Number of segments in the strength meter: one per zxcvbn bucket.
 *
 * Matching the estimator's buckets keeps the meter honest — a full meter means the top
 * bucket, not merely "above the bar".
 */
internal const val PassphraseStrengthSegmentCount = 5

/**
 * How a zxcvbn score reads to the user.
 *
 * The bands exist so the meter's wording is decided in one testable place instead of in a
 * `when` block inside a composable.
 */
internal enum class PassphraseStrengthBand {
    VeryWeak,
    Weak,
    NotStrongEnough,
    Strong,
    VeryStrong,
}

/**
 * Maps a zxcvbn score onto the band shown under the meter.
 *
 * Only bands at or above [VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE] read as approval,
 * so the meter can never call a passphrase strong while **Confirm** is still hidden.
 */
internal fun passphraseStrengthBand(score: Int): PassphraseStrengthBand = when {
    score <= 0 -> PassphraseStrengthBand.VeryWeak
    score == 1 -> PassphraseStrengthBand.Weak
    score < VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE -> PassphraseStrengthBand.NotStrongEnough
    score == VaultKeyPassphrasePolicy.MINIMUM_STRENGTH_SCORE -> PassphraseStrengthBand.Strong
    else -> PassphraseStrengthBand.VeryStrong
}

/**
 * Number of lit segments for [score].
 *
 * A score of 0 still lights one segment: an entirely dark meter reads as "no input" rather
 * than as the weakest possible passphrase.
 */
internal fun passphraseStrengthFilledSegments(score: Int): Int =
    (score + 1).coerceIn(1, PassphraseStrengthSegmentCount)

/**
 * The five-segment bar on its own.
 *
 * Carries no semantics: it is a picture of the score, and every place that shows it also shows
 * the band in words. Keeping it separate lets the details sheet reuse the bar without the
 * meter's label row or info button.
 */
@Composable
internal fun PassphraseStrengthBar(score: Int, modifier: Modifier = Modifier) {
    val filledColor by animateColorAsState(
        // Animated so crossing the policy floor reads as a hand-off from red to green rather
        // than a flicker. Compose honours the system animation-scale setting.
        targetValue = strengthAccentColor(score),
        label = "passphraseStrengthColor",
    )
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val filledSegments = passphraseStrengthFilledSegments(score)

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(StrengthSegmentGap)) {
        repeat(PassphraseStrengthSegmentCount) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(StrengthSegmentHeight)
                    .background(
                        color = if (index < filledSegments) filledColor else trackColor,
                        shape = RoundedCornerShape(StrengthSegmentCorner),
                    ),
            )
        }
    }
}

/**
 * The bar plus its label row: the band in words and an optional "why?" button.
 *
 * The crack-time estimate is deliberately not repeated here; it belongs in the details sheet,
 * which is where the numbers behind the rating are explained.
 *
 * @param onShowDetails when null the info button is omitted, so a caller can show the bar and
 *   label without offering to open the sheet.
 */
@Composable
internal fun PassphraseStrengthMeter(
    score: Int,
    modifier: Modifier = Modifier,
    onShowDetails: (() -> Unit)? = null,
) {
    val bandLabel = stringResource(passphraseStrengthBand(score).labelRes())
    val summary = stringResource(
        R.string.create_vault_key_passphrase_meter_summary,
        bandLabel,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PassphraseStrengthBar(score = score, modifier = Modifier.weight(1f))

            if (onShowDetails != null) {
                Spacer(Modifier.width(StrengthDetailsIconSpacing))
                IconButton(
                    onClick = onShowDetails,
                    modifier = Modifier.size(StrengthDetailsIconButtonSize),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(
                            R.string.create_vault_key_passphrase_details_open,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(StrengthDetailsIconSize),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            color = strengthAccentColor(score),
        )
    }
}

/** Maps a band onto its user-facing wording. */
internal fun PassphraseStrengthBand.labelRes(): Int = when (this) {
    PassphraseStrengthBand.VeryWeak -> R.string.create_vault_key_passphrase_strength_very_weak
    PassphraseStrengthBand.Weak -> R.string.create_vault_key_passphrase_strength_weak
    PassphraseStrengthBand.NotStrongEnough -> R.string.create_vault_key_passphrase_strength_not_strong_enough
    PassphraseStrengthBand.Strong -> R.string.create_vault_key_passphrase_strength_strong
    PassphraseStrengthBand.VeryStrong -> R.string.create_vault_key_passphrase_strength_very_strong
}

/** One requirement row: a filled state dot (or check) followed by its label. */
@Composable
private fun PassphraseRequirement(label: String, satisfied: Boolean, modifier: Modifier = Modifier) {
    val success = LocalExtendedColors.current.success
    val stateDescription = stringResource(
        if (satisfied) {
            R.string.create_vault_key_passphrase_rule_satisfied
        } else {
            R.string.create_vault_key_passphrase_rule_pending
        },
        label,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = stateDescription }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    color = if (satisfied) success.color else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (satisfied) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = success.onColor,
                    modifier = Modifier.size(13.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (satisfied) success.color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (satisfied) 0.78f else 1f),
        )
    }
}
