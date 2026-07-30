package app.zenten.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import app.zenten.vault.ui.VaultNavGraph
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
