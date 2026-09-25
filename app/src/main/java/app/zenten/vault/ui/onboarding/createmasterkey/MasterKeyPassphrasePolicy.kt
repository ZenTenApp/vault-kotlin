package app.zenten.vault.ui.onboarding.createmasterkey

/** The independently displayable results of the MasterKey passphrase rules. */
internal data class MasterKeyPassphraseRequirements(
    val hasMinimumLength: Boolean,
    val hasUppercase: Boolean,
    val hasLowercase: Boolean,
    val hasNumber: Boolean,
    val hasSpecialCharacter: Boolean,
) {
    val completedCount: Int
        get() = listOf(
            hasMinimumLength,
            hasUppercase,
            hasLowercase,
            hasNumber,
            hasSpecialCharacter,
        ).count { it }

    val allSatisfied: Boolean
        get() = completedCount == TOTAL_COUNT

    private companion object {
        const val TOTAL_COUNT = 5
    }
}

/** Rules applied only when choosing a passphrase for an in-app generated MasterKey. */
internal object MasterKeyPassphrasePolicy {
    const val MINIMUM_LENGTH = 8

    /**
     * Evaluates each rule separately so the UI can give live, specific feedback
     * without duplicating security-policy logic in the composable.
     */
    fun evaluate(passphrase: String) = MasterKeyPassphraseRequirements(
        hasMinimumLength = passphrase.length >= MINIMUM_LENGTH,
        hasUppercase = passphrase.any(Char::isUpperCase),
        hasLowercase = passphrase.any(Char::isLowerCase),
        hasNumber = passphrase.any(Char::isDigit),
        // Whitespace cannot be the invisible character that satisfies this rule.
        hasSpecialCharacter = passphrase.any { !it.isLetterOrDigit() && !it.isWhitespace() },
    )

    fun isValid(passphrase: String): Boolean = evaluate(passphrase).allSatisfied
}
