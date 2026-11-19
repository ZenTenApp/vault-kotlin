package app.zenten.vault.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import app.zenten.vault.R
import app.zenten.vault.ui.components.VaultPillButton
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Decodes QR codes from camera frames. Kept separate from the camera plumbing so it can be
 * unit-tested with plain luminance data.
 */
internal object QrFrameDecoder {
    // TRY_HARDER costs a little CPU per frame but makes a screen-displayed code (moiré, glare,
    // slight tilt) far more likely to decode; frames are throttled below so the cost stays low.
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * Decodes one frame of 8-bit luminance (the Y plane), [width] x [height] pixels laid out
     * with [rowStride] bytes per row. Returns the QR text, or null when no code is readable.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        // A fresh reader per frame: QRCodeReader keeps no state worth sharing and is not
        // documented as thread-safe.
        return runCatching {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()
    }
}

/** Minimum gap between delivered results, so an invalid code does not flood the UI. */
private const val ResultThrottleMillis = 1_000L

/**
 * Live camera preview that reports the text of QR codes it sees via [onScanned].
 *
 * Handles the camera permission itself: it asks once, explains a refusal, and offers a
 * shortcut to app settings. Frames are analysed in memory only; nothing is saved or sent.
 * The scanner keeps running after a result so a wrong code can simply be replaced by pointing
 * at the right one; the caller leaves the composition once a code is accepted.
 */
@Composable
internal fun QrScanner(
    enabled: Boolean,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var requestedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        requestedOnce = true
    }
    // Re-check on every resume: the Open Settings shortcut leaves the app, so the permission can
    // change (granted, or revoked) while this composable stays alive and no launcher callback fires.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Ask as soon as the screen opens: the user already chose "Login with QR Code".
    DisposableEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    if (!granted) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (requestedOnce) R.string.qr_scan_permission_denied else R.string.qr_scan_permission_rationale,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            VaultPillButton(
                label = stringResource(
                    if (requestedOnce) R.string.qr_scan_open_settings_action else R.string.qr_scan_grant_action,
                ),
                onClick = {
                    if (requestedOnce) {
                        // After a refusal Android may no longer show the dialog, so settings is
                        // the reliable way to recover.
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }
        return
    }

    // Set when the camera cannot be opened even though permission is granted (no back camera,
    // camera held by another app, hardware error). The empty preview would otherwise give no hint.
    var cameraUnavailable by remember { mutableStateOf(false) }
    if (cameraUnavailable) {
        Text(
            text = stringResource(R.string.qr_scan_camera_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    CameraPreview(
        enabled = enabled,
        onScanned = onScanned,
        onCameraUnavailable = { cameraUnavailable = true },
        modifier = modifier,
    )
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraPreview(
    enabled: Boolean,
    onScanned: (String) -> Unit,
    onCameraUnavailable: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The analyzer outlives recompositions, so it must read the latest callback/flag.
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnCameraUnavailable by rememberUpdatedState(onCameraUnavailable)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var lastDelivered = 0L
        providerFuture.addListener({
            // Both obtaining the provider and binding can fail on real devices, so any failure
            // is reported to the UI instead of leaving an empty square.
            runCatching {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    image.use { frame ->
                        val now = System.currentTimeMillis()
                        if (!currentEnabled || now - lastDelivered < ResultThrottleMillis) return@use
                        val text = frame.decodeQr() ?: return@use
                        lastDelivered = now
                        // Hop to the main thread: the callback mutates Compose state.
                        previewView.post { currentOnScanned(text) }
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { currentOnCameraUnavailable() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            // Releases the camera as soon as the scanner leaves the screen.
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)),
    )
}

/** Extracts the Y (luminance) plane of a camera frame and runs it through [QrFrameDecoder]. */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return QrFrameDecoder.decode(bytes, plane.rowStride, width, height)
}
