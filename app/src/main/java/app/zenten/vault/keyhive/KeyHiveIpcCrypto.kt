package app.zenten.vault.keyhive

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** KeyHive-compatible certificate-bound encryption for ContentProvider responses. */
object KeyHiveIpcCrypto {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private val salt = byteArrayOf(
        0x5A, 0x65, 0x6E, 0x74, 0x65, 0x6E, 0x49, 0x70,
        0x63, 0x56, 0x33, 0x2E, 0x30, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    fun encrypt(plaintext: String, context: Context): String {
        val iv = ByteArray(IV_LENGTH).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sharedKey(context), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun sharedKey(context: Context): SecretKey {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
                    ?.firstOrNull(),
            ) { "No signing certificate" }
        } else {
            @Suppress("DEPRECATION")
            requireNotNull(
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures
                    ?.firstOrNull(),
            ) { "No signing certificate" }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        val password = fingerprint.joinToString("") { "%02x".format(it) }.toCharArray()
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)).encoded
        return SecretKeySpec(key, "AES")
    }
}
