package app.zenten.vault.ui.accounts

import app.zenten.vault.data.model.Account
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyQrCodeTest {
    @Test
    fun account_key_pem_round_trips_through_the_qr_code_unchanged() {
        val account = Account(
            createdAt = 1_700_000_000_000_000L,
            zenTenKey = goldenEncryptedKey(),
            accountName = "Personal account",
        )
        val payload = LocalAccountBackupShare.accountKeyPemText(account)

        val decoded = decode(KeyQrCode.encode(payload))

        // The QR must carry exactly the clipboard text, byte for byte, so scanning it yields the same key.
        assertEquals(payload, decoded)
    }

    @Test
    fun matrix_is_square_and_has_no_margin_of_its_own() {
        val matrix = KeyQrCode.encode("VaultKey")

        assertEquals(matrix.width, matrix.height)
        // Version 1 is 21 modules; the 4-module quiet zone is drawn by the UI, not the matrix.
        assertEquals(21, matrix.width)
    }

    /** Renders the matrix with a quiet zone and reads it back with ZXing's reference decoder. */
    private fun decode(matrix: BitMatrix): String {
        val zone = KeyQrCode.QUIET_ZONE_MODULES
        val scale = 4
        val side = (matrix.width + zone * 2) * scale
        val luminance = IntArray(side * side) { index ->
            val px = index % side / scale - zone
            val py = index / side / scale - zone
            val dark = px in 0 until matrix.width && py in 0 until matrix.height && matrix.get(px, py)
            if (dark) 0x000000 else 0xFFFFFF
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val out = row?.takeIf { it.size >= width } ?: ByteArray(width)
                for (x in 0 until width) {
                    out[x] = (luminance[y * width + x] and 0xFF).toByte()
                }
                return out
            }

            override fun getMatrix(): ByteArray = ByteArray(width * height) { i ->
                (luminance[i] and 0xFF).toByte()
            }
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    /** Encrypted Ed25519 key from the golden crypto vectors, the same fixture the export tests use. */
    private fun goldenEncryptedKey(): String {
        val json = requireNotNull(javaClass.getResourceAsStream("/golden/crypto-vectors.json"))
            .bufferedReader()
            .use { it.readText() }
        return Regex("\\\"encryptedEd25519Pem\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(json)!!
            .groupValues[1]
            .replace("\\n", "\n")
    }
}
