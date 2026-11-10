package app.zenten.vault.ui.onboarding

import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrength
import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.VaultKeyPassphrasePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The latest measured guessability of a passphrase being typed on an onboarding step.
 *
 * Deliberately carries no copy of the measured text. Every value here is a non-secret projection
 * of the passphrase, so the feedback state can be read by the UI without holding the secret a
 * second time; the submit path re-measures the passphrase it was handed instead of trusting this.
 */
data class MeasuredPassphrase(
    val strength: PassphraseStrength,
)

/**
 * Debounced passphrase measurement for the imported and recovered VaultKey steps.
 *
 * This is the onboarding-only half of the **Create VaultKey** strength feedback: those two steps
 * show the same meter and details sheet to explain a refusal, but not the length rule's collapse
 * animation. The preview never decides acceptance — a submit re-measures the passphrase it was
 * handed — so this exists purely to tell the user what the rules will make of their input before
 * they tap. Measurement is debounced behind a typing pause and runs through the shared
 * [app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker], which keeps the
 * CPU-bound pattern search off the main thread.
 *
 * Only the non-secret projection is published. No copy of the typed text is retained here.
 */
class OnboardingPassphraseMeter(
    private val scope: CoroutineScope,
    /** Receives every new result, including null when there is no measurement. */
    private val publish: (MeasuredPassphrase?) -> Unit,
) {

    private var job: Job? = null

    /** Called on every keystroke: schedules a measurement unless the input is blank or too short. */
    fun onChanged(passphrase: String, checker: PassphraseStrengthChecker) {
        job?.cancel()
        // Below the length floor the passphrase already fails, and the requirement row says so;
        // spending the estimator on it would only add a second, redundant piece of feedback.
        if (passphrase.isBlank() || !VaultKeyPassphrasePolicy.hasMinimumLength(passphrase)) {
            publish(null)
            return
        }
        job = scope.launch {
            delay(STRENGTH_DEBOUNCE_MILLIS)
            publish(MeasuredPassphrase(checker.measure(passphrase)))
        }
    }

    /** Drops any pending measurement and the cached result, e.g. when the step leaves the screen. */
    fun clear() {
        job?.cancel()
        job = null
        publish(null)
    }

    companion object {
        /**
         * Quiet period before measuring, long enough to absorb normal typing and cover the
         * estimator's own millisecond-scale cost.
         */
        const val STRENGTH_DEBOUNCE_MILLIS = 250L
    }
}
