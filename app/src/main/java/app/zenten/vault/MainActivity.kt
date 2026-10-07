package app.zenten.vault

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import app.zenten.vault.ui.VaultNavGraph
import app.zenten.vault.ui.auth.AppLockedScreen
import app.zenten.vault.ui.auth.AppUnlockCoordinator
import app.zenten.vault.ui.auth.AppUnlockState
import app.zenten.vault.ui.auth.DeviceLockRequiredScreen
import app.zenten.vault.ui.auth.VAULT_AUTHENTICATORS
import app.zenten.vault.ui.availability.AppAccessGuard
import app.zenten.vault.ui.availability.AppBlockedScreen
import app.zenten.vault.ui.theme.ZenTenVaultTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A vault should never expose its contents in screenshots, recordings, or Recents previews.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { VaultApp() }
    }
}

@Composable
private fun VaultApp() {
    ZenTenVaultTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val activity = LocalContext.current as FragmentActivity
            val lifecycleOwner = LocalLifecycleOwner.current
            val unlockCoordinator = remember(activity) {
                AppUnlockCoordinator(activity, ContextCompat.getMainExecutor(activity))
            }
            val unlockState by unlockCoordinator.state.collectAsState()

            DisposableEffect(lifecycleOwner, unlockCoordinator) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = unlockCoordinator.onForeground()
                    override fun onStop(owner: LifecycleOwner) {
                        if (!activity.isChangingConfigurations) unlockCoordinator.onBackground()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            when (unlockState) {
                AppUnlockState.Checking -> AppLockedScreen(isAuthenticating = true, onUnlock = {})
                AppUnlockState.DeviceLockRequired -> DeviceLockRequiredScreen(
                    onOpenSettings = { openSecuritySettings(activity) },
                    onCheckAgain = unlockCoordinator::recheckDeviceLock,
                )
                AppUnlockState.Locked,
                AppUnlockState.Authenticating -> AppLockedScreen(
                    isAuthenticating = unlockState == AppUnlockState.Authenticating,
                    onUnlock = unlockCoordinator::authenticate,
                )
                AppUnlockState.Unlocked -> AvailableVaultApp()
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
