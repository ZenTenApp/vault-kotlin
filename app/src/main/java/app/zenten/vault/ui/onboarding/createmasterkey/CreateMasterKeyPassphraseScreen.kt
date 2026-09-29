package app.zenten.vault.ui.onboarding.createmasterkey

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/**
 * Step 2 of the in-app **Create MasterKey** flow: choose the passphrase that
 * will protect the generated MasterKey.
 *
 * Requirements are shown below the unlabelled input. A completed rule shows
 * success feedback for two seconds before leaving the grid, keeping attention
 * on the remaining requirements. The ViewModel checks the policy again before
 * retaining the secret.
 */
@Composable
fun CreateMasterKeyPassphraseScreen(navController: NavHostController) {
    var passphrase by remember { mutableStateOf("") }
    val viewModel = rememberCreateMasterKeyViewModel(navController)
    val requirements = MasterKeyPassphrasePolicy.evaluate(passphrase)

    // Passphrase input is on screen here, so block screenshots and recordings.
    ScreenshotProtection()

    CreateMasterKeyScaffold(
        title = stringResource(R.string.create_master_key_passphrase_title),
        heroIcon = R.drawable.hero_icon_passphrase,
        heroIconDescription = stringResource(R.string.create_master_key_passphrase_icon_description),
        onBack = navController::popBackStack,
        content = {
            Spacer(Modifier.height(28.dp))

            PassphraseField(
                label = null,
                value = passphrase,
                onValueChange = { passphrase = it },
            )

            PassphraseRequirementsGrid(
                requirements = requirements,
                modifier = Modifier.padding(18.dp),
            )
        },
        actions = {
            AnimatedVisibility(
                visible = requirements.allSatisfied,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                VaultPillButton(
                    label = stringResource(R.string.create_master_key_passphrase_confirm),
                    onClick = {
                        // Recheck in the ViewModel so policy enforcement does not depend on UI state.
                        if (viewModel.choosePassphrase(passphrase)) {
                            navController.navigate(VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE)
                        }
                    },
                )
            }
        },
    )
}

/** A compact two-column checklist that makes passphrase progress visible at a glance. */
@Composable
private fun PassphraseRequirementsGrid(
    requirements: MasterKeyPassphraseRequirements,
    modifier: Modifier = Modifier,
) {
    val rules = listOf(
        stringResource(R.string.create_master_key_passphrase_rule_length) to requirements.hasMinimumLength,
        stringResource(R.string.create_master_key_passphrase_rule_uppercase) to requirements.hasUppercase,
        stringResource(R.string.create_master_key_passphrase_rule_lowercase) to requirements.hasLowercase,
        stringResource(R.string.create_master_key_passphrase_rule_number) to requirements.hasNumber,
        stringResource(R.string.create_master_key_passphrase_rule_special) to requirements.hasSpecialCharacter,
    )
    val ruleVisibility = remember { mutableStateMapOf<Int, Boolean>() }

    rules.forEachIndexed { index, (_, satisfied) ->
        LaunchedEffect(index, satisfied) {
            if (satisfied) {
                // Keep success feedback visible long enough to be perceived before removal.
                delay(RULE_SUCCESS_DISPLAY_MILLIS)
                ruleVisibility[index] = false
            } else {
                ruleVisibility[index] = true
            }
        }
    }
    val visibleRules = rules.filterIndexed { index, _ -> ruleVisibility[index] != false }

    Column(modifier = modifier.fillMaxWidth()) {
        visibleRules.chunked(2).forEachIndexed { rowIndex, rowRules ->
            if (rowIndex > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowRules.forEach { (label, satisfied) ->
                    PassphraseRule(
                        label = label,
                        satisfied = satisfied,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the final odd item aligned to the same two-column grid.
                if (rowRules.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private const val RULE_SUCCESS_DISPLAY_MILLIS = 2_000L

@Composable
private fun PassphraseRule(label: String, satisfied: Boolean, modifier: Modifier = Modifier) {
    val success = LocalExtendedColors.current.success
    val stateDescription = stringResource(
        if (satisfied) R.string.create_master_key_passphrase_rule_satisfied
        else R.string.create_master_key_passphrase_rule_pending,
        label,
    )

    Row(
        modifier = modifier
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
            style = MaterialTheme.typography.bodySmall,
            color = if (satisfied) success.color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (satisfied) 0.78f else 1f),
        )
    }
}
