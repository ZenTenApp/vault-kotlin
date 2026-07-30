package app.zenten.vault.bravesync.password

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Chromium password Sync identity helpers. */
object PasswordUniqueTag {
    private val escapedAscii = setOf(' ', '"', '#', '%', ':', '<', '>', '?', '[', '\\', ']', '^', '`', '{', '|', '}')
    private val passwordProtobufPrefix = byteArrayOf(0x8a.toByte(), 0xb3.toByte(), 0x16, 0x00)

    /** Chromium EscapePath, deliberately not generic URL encoding. */
    fun escapePath(value: String): String = buildString {
        value.forEach { char ->
            if (char in escapedAscii || char.code < 0x20 || char.code >= 0x7f) {
                char.toString().toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                    append('%')
                    append("%02X".format(byte.toInt() and 0xff))
                }
            } else {
                append(char)
            }
        }
    }

    fun compute(origin: String, usernameElement: String, username: String, passwordElement: String, signonRealm: String): String =
        listOf(origin, usernameElement, username, passwordElement, signonRealm).joinToString("|") { escapePath(it) }

    fun clientTagHash(uniqueTag: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest(passwordProtobufPrefix + uniqueTag.toByteArray(StandardCharsets.UTF_8)),
    )
}
