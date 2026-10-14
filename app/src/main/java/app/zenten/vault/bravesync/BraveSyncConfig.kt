package app.zenten.vault.bravesync

/** Protocol constants shared with the password-only Brave Sync implementation. */
object BraveSyncConfig {
    const val DEFAULT_SERVER_URL = "https://sync-v2.brave.com/v2"
    // Brave's public Sync service key from the open-source Brave client. It is a
    // shared protocol constant, not a credential, so Gitleaks is told to ignore it.
    const val SERVICE_KEY = "qztbjzBqJueQZLFkwTTJrieu8Vw3789u" // gitleaks:allow

    const val PASSWORD_TYPE_ID = 45_873
    const val NIGORI_TYPE_ID = 47_745
    const val WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L

    const val KEY_DERIVATION_PBKDF2 = 1
    const val KEY_DERIVATION_SCRYPT = 2
}
