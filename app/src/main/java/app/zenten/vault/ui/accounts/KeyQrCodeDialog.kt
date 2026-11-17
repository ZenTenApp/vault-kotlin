package app.zenten.vault.ui.accounts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes a key's text as a QR code matrix.
 *
 * The payload is the same PEM block the clipboard export copies, so scanning the code yields
 * exactly what the clipboard holds. Error correction is medium: a phone camera reading a
 * screen needs some tolerance for glare and moiré, and the PEM is short enough that the extra
 * redundancy still fits in a version a phone scans reliably.
 */
internal object KeyQrCode {
    /** Quiet-zone width in modules. The QR spec asks for 4; scanners fail without it. */
    const val QUIET_ZONE_MODULES = 4

    /** Returns the raw module matrix with no margin; the UI draws the quiet zone itself. */
    @Throws(WriterException::class)
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        ),
    )
}

/**
 * Dialog that shows [text] as a QR code. It appears only after the user taps a button and
 * passes the passphrase check, and is removed as soon as it is dismissed.
 *
 * The code is drawn black on a white square regardless of theme, because scanners expect
 * dark modules on a light background even in dark mode.
 *
 * @param title heading that names the key being shown.
 * @param text the exact text to encode, the same as the clipboard export.
 * @param onDismiss invoked by Close and by scrim taps; the caller drops [text] here.
 */
@Composable
internal fun KeyQrCodeDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    // Encoding is cheap for a PEM block, but remember it so recompositions do not redo it.
    val matrix = remember(text) { runCatching { KeyQrCode.encode(text) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (matrix == null) {
                    Text("This key is too large to show as a QR code.", color = MaterialTheme.colorScheme.error)
                } else {
                    QrCodeImage(matrix)
                }
                Text(
                    text = "Anyone who scans this code gets the passphrase-encrypted key. The passphrase is still required to use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Draws a QR matrix as one vector path, scaled to fill a square. Vector drawing keeps the
 * modules sharp at any density; a bitmap would blur at fractional scales.
 */
@Composable
private fun QrCodeImage(matrix: BitMatrix) {
    // Build the path once per matrix. Each dark module becomes a 1x1 square in module units,
    // and the transform below scales those units to the available pixels.
    val path = remember(matrix) {
        Path().apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix.get(x, y)) {
                        addRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f))
                    }
                }
            }
        }
    }
    val quietZone = KeyQrCode.QUIET_ZONE_MODULES
    val totalModules = matrix.width + quietZone * 2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Scale first, then shift by the quiet zone in module units, so the margin scales too.
            val scaleFactor = size.minDimension / totalModules
            scale(scaleFactor, pivot = Offset.Zero) {
                translate(left = quietZone.toFloat(), top = quietZone.toFloat()) {
                    drawPath(path, Color.Black)
                }
            }
        }
    }
}
