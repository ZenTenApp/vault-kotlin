package app.zenten.vault.ui.accounts

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.VaultDestinations
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocalAccountBackupQrScreen(
    navController: NavHostController,
    accountId: Long,
    viewModel: LocalAccountBackupViewModel = hiltViewModel(),
) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }
    fun save() {
        if (account == null || saving) return
        saving = true
        scope.launch {
            runCatching { saveQrToGallery(context, qrBitmap(SshKeyCodec.formatAsPem(account.zenTenKey)), account.createdAt) }
                .onSuccess {
                    viewModel.markQrBackedUp()
                    Toast.makeText(context, "QR image saved to Gallery", Toast.LENGTH_SHORT).show()
                    navController.navigate(VaultDestinations.localAccountBackupExport(account.createdAt)) {
                        popUpTo(VaultDestinations.localAccountBackupQr(account.createdAt)) { inclusive = true }
                    }
                }
                .onFailure { error -> Toast.makeText(context, "Could not save QR image: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
            saving = false
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingSave) {
            pendingSave = false
            if (granted) save() else Toast.makeText(context, "Storage permission is required to save the QR image.", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler { }
    SecureWindow()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Save QR image", style = MaterialTheme.typography.headlineMedium)
        Text("Save an image containing this account's encrypted OpenSSH private key to your Gallery. This is required before continuing.")
        if (account == null) Text("Account not found", color = MaterialTheme.colorScheme.error)
        else Button(onClick = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingSave = true
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else save()
        }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving..." else "Save QR image to gallery") }
    }
}

@Composable
fun LocalAccountBackupExportScreen(
    navController: NavHostController,
    accountId: Long,
    viewModel: LocalAccountBackupViewModel = hiltViewModel(),
) {
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account
    val context = LocalContext.current
    BackHandler { }
    SecureWindow()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Export account", style = MaterialTheme.typography.headlineMedium)
        Text("Export this account's encrypted OpenSSH private key to Notes, Messages, or another app. This is required before continuing.")
        if (account == null) Text("Account not found", color = MaterialTheme.colorScheme.error)
        else Button(onClick = {
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SshKeyCodec.formatAsPem(account.zenTenKey)),
                "Export encrypted SSH private key",
            ))
            viewModel.complete()
        }, modifier = Modifier.fillMaxWidth()) { Text("Export to notes") }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navController.navigate(VaultDestinations.ACCOUNTS) {
            popUpTo(VaultDestinations.ACCOUNTS) { inclusive = true }
        }
    }
}

@Composable
private fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun qrBitmap(content: String, size: Int = 1080): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    }
}

private suspend fun saveQrToGallery(context: Context, bitmap: Bitmap, accountId: Long) = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "zenten-account-$accountId-backup.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZenTen Vault")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Gallery is unavailable" }
    try {
        requireNotNull(resolver.openOutputStream(uri)).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode PNG" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}
