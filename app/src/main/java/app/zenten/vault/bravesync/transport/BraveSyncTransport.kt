package app.zenten.vault.bravesync.transport

import app.zenten.vault.bravesync.BraveSyncConfig
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP transport for Brave's gzip-capable protobuf Sync endpoint. */
internal class BraveSyncTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val serverUrl: String = BraveSyncConfig.DEFAULT_SERVER_URL,
    private val serviceKey: String = BraveSyncConfig.SERVICE_KEY,
) {
    fun post(clientIdHex: String, authToken: String, protobuf: ByteArray): ByteArray {
        val clientId = hexToBytes(clientIdHex)
        val url = "${serverUrl.trimEnd('/')}/command/?client=Chromium&client_id=" +
            java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(clientId), Charsets.UTF_8.name())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $authToken")
            .header("BraveServiceKey", serviceKey)
            .header("Content-Type", "application/octet-stream")
            .header("User-Agent", "Chrome MAC 144.1.86.142 (zenten-vault)")
            .header("Accept-Encoding", "gzip")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .post(protobuf.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) {
                throw IOException("Brave Sync HTTP ${response.code}: ${bytes.decodeToString().take(500)}")
            }
            return if (response.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true || isGzip(bytes)) {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } else {
                bytes
            }
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) { "Sync client ID must be a 32-byte hex public key" }
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
