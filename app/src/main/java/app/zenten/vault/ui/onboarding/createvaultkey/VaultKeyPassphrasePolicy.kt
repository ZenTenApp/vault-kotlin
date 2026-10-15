package app.zenten.vault.ui.onboarding.createvaultkey

/**
 * The independently displayable results of the VaultKey passphrase rules.
 *
 * Only two rules survive: a length floor that can be checked for free on every
 * keystroke, and a guessability floor that needs [PassphraseStrengthChecker].
 *
 * Character-class composition rules (an uppercase letter, a lowercase letter, a
 * digit, a symbol) were deliberately removed. NIST SP 800-63B advises against them,
 * and they actively harm this product: they force users toward exactly the
 * predictable mutations — `Password1!`, `P@ssw0rd` — that a strength estimator
 * already rejects, while pushing long multi-word passphrases out for lacking a digit.
 */
internal data class VaultKeyPassphraseRequirements(
    val hasMinimumLength: Boolean,
    val hasSufficientStrength: Boolean,
) {
    val allSatisfied: Boolean
        get() = hasMinimumLength && hasSufficientStrength
}

/** Rules applied only when choosing a passphrase for an in-app generated VaultKey. */
internal object VaultKeyPassphrasePolicy {
    /**
     * Length floor for the chosen passphrase.
     *
     * NIST's 8-character minimum assumes a rate-limited online login. This passphrase
     * instead protects an offline-crackable key, so the floor is deliberately higher.
     */
    const val MINIMUM_LENGTH = 12

    /**
     * Lowest accepted zxcvbn bucket. Bucket 3 is described by zxcvbn as "safely
     * unguessable" against an offline attack that assumes a salted, slow hash
     * function — which is exactly the `bcrypt_pbkdf` KDF protecting the VaultKey.
     * Raise to 4 to demand the "very unguessable" bucket.
     */
    const val MINIMUM_STRENGTH_SCORE = 3

    /**
     * Combines the free length rule with a strength measurement.
     *
     * A null [strength] means "not measured yet", not "weak", so the caller can show
     * the length rule as satisfied while the estimator is still running.
     */
    fun evaluate(passphrase: CharSequence, strength: PassphraseStrength?) = VaultKeyPassphraseRequirements(
        hasMinimumLength = hasMinimumLength(passphrase),
        hasSufficientStrength = strength != null && strength.score >= MINIMUM_STRENGTH_SCORE,
    )

    /**
     * Cheap first-stage rule, also used to skip measurement entirely for input that
     * could never be accepted.
     */
    fun hasMinimumLength(passphrase: CharSequence): Boolean = passphrase.length >= MINIMUM_LENGTH

    fun isValid(passphrase: CharSequence, strength: PassphraseStrength?): Boolean =
        evaluate(passphrase, strength).allSatisfied
}
