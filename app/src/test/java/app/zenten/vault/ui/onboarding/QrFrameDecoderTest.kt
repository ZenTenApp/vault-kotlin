package app.zenten.vault.ui.onboarding

import app.zenten.vault.ui.keys.KeyQrCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QrFrameDecoderTest {
    /** Renders the QR as an 8-bit luminance frame (dark = 0, light = 255) with a quiet zone. */
    private fun frameOf(text: String, scale: Int = 6): Triple<ByteArray, Int, Int> {
        val matrix = KeyQrCode.encode(text)
        val quiet = KeyQrCode.QUIET_ZONE_MODULES
        val size = (matrix.width + 2 * quiet) * scale
        val bytes = ByteArray(size * size) { 0xFF.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                bytes[((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx] = 0
            }
        }
        return Triple(bytes, size, size)
    }

    @Test
    fun decodes_a_generated_qr_frame() {
        val payload = "VaultKey payload\nline two"
        val (bytes, w, h) = frameOf(payload)
        assertEquals(payload, QrFrameDecoder.decode(bytes, w, w, h))
    }

    @Test
    fun blank_frame_yields_null() {
        assertNull(QrFrameDecoder.decode(ByteArray(64 * 64) { 0x80.toByte() }, 64, 64, 64))
    }
}
