package app.zenten.vault.ui.onboarding.createvaultkey

/**
 * Minimal [PassphraseStrength] for tests that only care about the strength bucket.
 *
 * The real projection is exercised by [PassphraseStrengthMappingTest]; tests about the policy or
 * the meter's colours do not need a real estimator run behind them.
 */
internal fun passphraseStrengthForScore(score: Int): PassphraseStrength = PassphraseStrength(
    score = score,
    offlineSlowHashSeconds = 0.0,
    offlineFastHashSeconds = 0.0,
    suggestions = emptyList(),
    findings = emptyList(),
)
