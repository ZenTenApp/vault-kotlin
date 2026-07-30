package app.zenten.vault.crypto

/** App-facing NIP-44 v2 boundary for backup self-encryption. */
object Nip44 {
    fun encryptSelf(plaintext: String, privateKey: ByteArray, publicKey: ByteArray): String =
        com.vitorpamplona.quartz.nip44Encryption.Nip44.v2.encrypt(plaintext, privateKey, publicKey).encodePayload()

    fun decryptSelf(payload: String, privateKey: ByteArray, publicKey: ByteArray): String =
        com.vitorpamplona.quartz.nip44Encryption.Nip44.v2.decrypt(payload, privateKey, publicKey)
}
