package app.zenten.vault.ui.auth

import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Retains the unlock session across Activity recreation.
 *
 * This ViewModel is scoped to the Activity's `ViewModelStore`, which Android keeps alive across a
 * configuration change (rotation, theme/dark mode, locale, font size). It therefore owns the
 * in-memory [AppUnlockSessionState] while the Activity, composition, and [AppUnlockCoordinator]
 * are destroyed and rebuilt. Process death clears the `ViewModelStore` too, so the session is still
 * memory-only and a cold start always begins locked.
 *
 * The coordinator itself stays Activity-bound because `BiometricPrompt` needs a
 * `FragmentActivity`; [bind] rebinds it to each new Activity without dropping session state.
 *
 * It deliberately has no injected dependencies and is not `@HiltViewModel`: `MainActivity` obtains
 * it through [factory], which `androidx.activity.viewModels()` uses instead of the Hilt factory.
 * A Hilt `@Binds` entry would add nothing here and would drag the ViewModel into the Hilt graph.
 */
class AppUnlockViewModel : ViewModel() {
    private val session = AppUnlockSessionState()

    val state: StateFlow<AppUnlockState> = session.state

    /** Emits when the host Activity must launch the API 28-29 keyguard credential prompt. */
    val deviceCredentialRequests: SharedFlow<Unit> = session.deviceCredentialRequests

    private var coordinator: AppUnlockCoordinator? = null

    /**
     * The Activity this ViewModel's coordinator currently belongs to, held weakly: a ViewModel
     * outlives the Activity it was created for (that is the point of this class), so a strong
     * reference here would keep a destroyed Activity alive. The coordinator is released in
     * [bind] and [onCleared], so this is only a same-activity guard for a duplicate [bind].
     */
    private var boundActivity: WeakReference<FragmentActivity>? = null

    /**
     * Binds a coordinator to [activity]. Called from `MainActivity.onCreate` after
     * `super.onCreate`, so this runs once per Activity instance but only once per ViewModel
     * lifetime.
     *
     * Re-binding for a new Activity is not a foreground event by itself: if the app was genuinely
     * backgrounded, [onBackground] already recorded the timestamp and the `onStart` foreground
     * check still evaluates the grace window.
     */
    fun bind(activity: FragmentActivity) {
        if (boundActivity?.get() === activity) return

        // The previous coordinator is bound to the discarded Activity. Cancelling its prompt makes
        // sure no system prompt window outlives the Activity; its callbacks are ignored once closed.
        coordinator?.close()
        coordinator = null

        // The old Activity's prompt machinery is gone, so clear both prompt guards. If the retained
        // state was mid-authentication, the spinner must become a retryable locked screen instead
        // of waiting forever for a result that can no longer reach the new Activity.
        // `credentialRequestInFlight` must also be cleared: otherwise the API 28-29 keyguard
        // confirmation could leave it set, and every later `authenticate()` would silently return.
        session.promptRequested = false
        session.credentialRequestInFlight = false
        if (session.state.value == AppUnlockState.Authenticating) {
            session.state.value = AppUnlockState.Locked
        }

        boundActivity = WeakReference(activity)
        coordinator = AppUnlockCoordinator(
            activity,
            ContextCompat.getMainExecutor(activity),
            session = session,
        )
    }

    // Explicit Unit-returning blocks (rather than `= coordinator?.onX()` expression bodies) keep
    // these callable as ordinary `() -> Unit` method references from the composition.
    fun onForeground() {
        coordinator?.onForeground()
    }

    fun onBackground() {
        coordinator?.onBackground()
    }

    fun authenticate() {
        coordinator?.authenticate()
    }

    fun onDeviceCredentialResult(succeeded: Boolean) {
        coordinator?.onDeviceCredentialResult(succeeded)
    }

    fun recheckDeviceLock() {
        coordinator?.recheckDeviceLock()
    }

    override fun onCleared() {
        // The Activity is gone for good (finished or process death); release the prompt and do not
        // leave a callback path that could mutate a session nobody observes anymore.
        coordinator?.close()
        coordinator = null
        boundActivity = null
    }

    companion object {
        /**
         * Explicit factory for `by viewModels()`. There are no dependencies to inject, and stating
         * the construction here avoids reflective instantiation.
         */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AppUnlockViewModel() }
        }
    }
}
