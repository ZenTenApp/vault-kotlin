package app.zenten.vault.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sshKdfRounds by viewModel.sshKdfRounds.collectAsState()
    var roundsMenuExpanded by remember { mutableStateOf(false) }
    // Trigger width (px) captured to inset the dropdown with equal left/right margins.
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val sideMarginPx = with(LocalDensity.current) { 16.dp.toPx().roundToInt() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to accounts")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { navController.navigate(VaultDestinations.VAULT_KEY) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "VaultKey Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Review backup coverage and consolidate your vault",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }

            Column {
                Text(
                    text = "SSH key generation",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                ExposedDropdownMenuBox(
                    expanded = roundsMenuExpanded,
                    onExpandedChange = { roundsMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { roundsMenuExpanded = true },
                        modifier = Modifier
                            .menuAnchor()
                            .onGloballyPositioned { anchorWidthPx = it.size.width }
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "KDF rounds",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Used for newly generated SSH keys",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(sshKdfRounds.toString(), style = MaterialTheme.typography.titleMedium)
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = roundsMenuExpanded)
                    }
                    ExposedDropdownMenu(
                        expanded = roundsMenuExpanded,
                        onDismissRequest = { roundsMenuExpanded = false },
                        modifier = Modifier.insetDropdownMenu(anchorWidthPx, sideMarginPx),
                    ) {
                        SshKeyCodec.SUPPORTED_BCRYPT_PBKDF_ROUNDS.forEach { rounds ->
                            DropdownMenuItem(
                                text = { Text(rounds.toString()) },
                                onClick = {
                                    viewModel.setSshKdfRounds(rounds)
                                    roundsMenuExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                trailingIcon = {
                                    if (rounds == sshKdfRounds) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Narrows the dropdown surface to the trigger width and slides it over so it keeps equal
 * left/right margins instead of spanning the full anchor width like the trigger button.
 */
private fun Modifier.insetDropdownMenu(anchorWidthPx: Int, sideMarginPx: Int): Modifier =
    layout { measurable, constraints ->
        if (anchorWidthPx > 2 * sideMarginPx) {
            val contentWidth = anchorWidthPx - 2 * sideMarginPx
            val placeable = measurable.measure(
                constraints.copy(minWidth = contentWidth, maxWidth = contentWidth),
            )
            layout(anchorWidthPx, placeable.height) {
                placeable.place(sideMarginPx, 0)
            }
        } else {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }
    }
