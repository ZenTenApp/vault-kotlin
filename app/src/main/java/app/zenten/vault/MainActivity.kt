package app.zenten.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
import androidx.navigation.compose.rememberNavController
import app.zenten.vault.ui.VaultNavGraph
import app.zenten.vault.ui.availability.AppAccessGuard
import app.zenten.vault.ui.availability.AppBlockedScreen
import app.zenten.vault.ui.theme.ZenTenVaultTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VaultApp()
        }
    }
}

@Composable
private fun VaultApp() {
    ZenTenVaultTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            val accessGuard = remember(context) { AppAccessGuard(context) }
            val accessState by accessGuard.state.collectAsState()
            DisposableEffect(accessGuard) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) = accessGuard.refresh()
                }
                (context as ComponentActivity).lifecycle.addObserver(observer)
                onDispose {
                    (context as ComponentActivity).lifecycle.removeObserver(observer)
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
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}
