package app.zenten.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import app.zenten.vault.ui.VaultNavGraph
import app.zenten.vault.ui.auth.AppLockedScreen
import app.zenten.vault.ui.auth.AppUnlockState
import app.zenten.vault.ui.auth.AppUnlockViewModel
import app.zenten.vault.ui.auth.DeviceLockRequiredScreen
import app.zenten.vault.ui.auth.StrandedVaultScreen
import app.zenten.vault.ui.auth.VAULT_AUTHENTICATORS
import app.zenten.vault.ui.auth.VaultPassphraseScreen
import app.zenten.vault.ui.auth.VaultUnlockGateState
import app.zenten.vault.ui.auth.VaultUnlockGateViewModel
import app.zenten.vault.ui.availability.AppAccessGuard
import app.zenten.vault.ui.availability.AppBlockedScreen
import app.zenten.vault.ui.theme.ZenTenVaultTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The deprecated keyguard intent is the only credential fallback on API 28-29, where AndroidX
 * Biometric rejects the combined `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` prompt.
 */
@Suppress("DEPRECATION")
private fun deviceCredentialIntent(keyguardManager: KeyguardManager?): Intent? =
    keyguardManager?.createConfirmDeviceCredentialIntent(
        "Unlock ZenTen Vault",
        "Confirm your identity to continue",
    )

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /**
     * Activity-scoped, so the unlock session survives a configuration change. A new Activity
     * receives the same ViewModel instance and [AppUnlockViewModel.bind] re-attaches the prompt
     * machinery to it; process death discards it and therefore relocks the vault.
     */
    private val unlockViewModel: AppUnlockViewModel by viewModels { AppUnlockViewModel.factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A vault should never expose its contents in screenshots, recordings, or Recents previews.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Bind before the composition can collect state, so the recreated Activity drives the
        // retained session instead of showing a stale or prompt-less gate.
        unlockViewModel.bind(this)
        setContent { VaultApp(unlockViewModel) }
    }

    override fun onStart() {
        super.onStart()
        // Evaluate the 30-second foreground boundary with the session retained by the ViewModel.
        unlockViewModel.onForeground()
    }

    override fun onStop() {
        // A configuration change tears the Activity down and immediately recreates it, but the
        // user never left the app. Recording that as a background trip would start the grace
        // clock and could relock the vault the user is still looking at.
        if (!isChangingConfigurations) unlockViewModel.onBackground()
        super.onStop()
    }
}

@Composable
private fun VaultApp(unlockViewModel: AppUnlockViewModel) {
    ZenTenVaultTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // LocalActivity is the Compose-safe way to obtain the host Activity;
            // casting LocalContext can fail when the context is a wrapper.
            val activity = LocalActivity.current as FragmentActivity
            val unlockState by unlockViewModel.state.collectAsState()

            // API 28-29 cannot combine strong biometric and device credential in one prompt, so
            // the coordinator asks the activity to run the keyguard credential confirmation.
            val credentialLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                unlockViewModel.onDeviceCredentialResult(result.resultCode == Activity.RESULT_OK)
            }
            val keyguardManager = remember(activity) {
                activity.getSystemService(KeyguardManager::class.java)
            }
            LaunchedEffect(unlockViewModel, keyguardManager) {
                unlockViewModel.deviceCredentialRequests.collect {
                    val intent = deviceCredentialIntent(keyguardManager)
                    if (intent != null) {
                        credentialLauncher.launch(intent)
                    } else {
                        unlockViewModel.onDeviceCredentialResult(false)
                    }
                }
            }

            val gateViewModel: VaultUnlockGateViewModel = hiltViewModel()
            val gateState by gateViewModel.state.collectAsState()

            // The passphrase is memory-only, so any transition away from the unlocked device
            // state drops it. Re-entering the app then requires the passphrase again.
            LaunchedEffect(unlockState) {
                if (unlockState !is AppUnlockState.Unlocked) gateViewModel.lockSession()
            }

            when (unlockState) {
                AppUnlockState.Checking -> AppLockedScreen(isAuthenticating = true, onUnlock = {})
                AppUnlockState.DeviceLockRequired -> DeviceLockRequiredScreen(
                    onOpenSettings = { openSecuritySettings(activity) },
                    onCheckAgain = unlockViewModel::recheckDeviceLock,
                )
                AppUnlockState.Locked,
                AppUnlockState.Authenticating -> AppLockedScreen(
                    isAuthenticating = unlockState == AppUnlockState.Authenticating,
                    onUnlock = unlockViewModel::authenticate,
                )
                AppUnlockState.Unlocked -> when (val gate = gateState) {
                    // A stored VaultKey requires the passphrase before the vault opens; a fresh
                    // install goes straight to onboarding, which collects it itself.
                    VaultUnlockGateState.Loading -> AppLockedScreen(isAuthenticating = true, onUnlock = {})
                    VaultUnlockGateState.Onboarding,
                    VaultUnlockGateState.Unlocked -> AvailableVaultApp()
                    // Records exist but cannot be opened on this device. Routing this to onboarding
                    // would show an empty vault, which reads as data loss with no explanation.
                    is VaultUnlockGateState.VaultUnreadable -> StrandedVaultScreen(
                        viewModel = hiltViewModel(),
                    )
                    is VaultUnlockGateState.PassphraseRequired -> VaultPassphraseScreen(
                        isVerifying = gate.verifying,
                        hasError = gate.error,
                        onPassphraseChanged = gateViewModel::clearError,
                        onSubmit = gateViewModel::submitPassphrase,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailableVaultApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val accessGuard = remember(context) { AppAccessGuard(context) }
    val accessState by accessGuard.state.collectAsState()
    DisposableEffect(accessGuard, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = accessGuard.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            accessGuard.close()
        }
    }

    if (!accessState.canUseApp) {
        AppBlockedScreen(
            blockers = accessState.blockers,
            isChecking = accessState.isCheckingTime,
            onRetry = { accessGuard.refresh(showChecking = true) },
        )
    } else {
        val navController = rememberNavController()
        Scaffold { innerPadding ->
            VaultNavGraph(
                navController = navController,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            )
        }
    }
}

private fun openSecuritySettings(activity: FragmentActivity) {
    val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            VAULT_AUTHENTICATORS,
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    runCatching { activity.startActivity(preferred) }
        .onFailure { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
}
