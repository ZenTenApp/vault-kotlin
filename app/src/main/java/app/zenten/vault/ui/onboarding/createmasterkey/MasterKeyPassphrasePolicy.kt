package app.zenten.vault.ui.onboarding.createmasterkey

/** Rules applied only when choosing a passphrase for an in-app generated MasterKey. */
internal object MasterKeyPassphrasePolicy {
    const val MINIMUM_LENGTH = 8

    /**
     * Whitespace does not count as a special character. This prevents an invisible
     * separator from being the only character satisfying the special-character rule.
     */
    fun isValid(passphrase: String): Boolean =
        passphrase.length >= MINIMUM_LENGTH &&
            passphrase.any(Char::isUpperCase) &&
            passphrase.any(Char::isLowerCase) &&
            passphrase.any(Char::isDigit) &&
            passphrase.any { !it.isLetterOrDigit() && !it.isWhitespace() }
}
