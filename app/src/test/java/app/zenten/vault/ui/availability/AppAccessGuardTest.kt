package app.zenten.vault.ui.availability

import android.app.Application
import android.content.Intent
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that [AppAccessGuard] re-validates the clock on system time changes.
 *
 * The precondition is mocked so the test observes exactly when the guard asks for a new
 * check; a real precondition would perform network I/O that this contract does not need.
 */
@RunWith(RobolectricTestRunner::class)
// Use a plain Application so Robolectric does not run VaultApplication's Hilt graph, which
// touches keystore-backed storage that this test does not exercise.
@Config(sdk = [34], application = Application::class)
class AppAccessGuardTest {
    @Test
    fun system_time_changes_revalidate_the_clock() {
        val context = RuntimeEnvironment.getApplication()
        val precondition = mockk<RemoteOperationPrecondition>()
        // No validated internet keeps refresh synchronous and avoids the time-source network call.
        every { precondition.hasValidatedInternet() } returns false

        val guard = AppAccessGuard(context, precondition)
        // One check seeds the initial state, then the init refresh performs the first real check.
        verify(exactly = 2) { precondition.hasValidatedInternet() }

        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 3) { precondition.hasValidatedInternet() }

        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 4) { precondition.hasValidatedInternet() }

        guard.close()
    }

    @Test
    fun closing_the_guard_stops_revalidating_on_time_changes() {
        val context = RuntimeEnvironment.getApplication()
        val precondition = mockk<RemoteOperationPrecondition>()
        every { precondition.hasValidatedInternet() } returns false
        val guard = AppAccessGuard(context, precondition)

        guard.close()
        context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        // Close unregisters the receiver, so the broadcast must not trigger another check.
        verify(exactly = 2) { precondition.hasValidatedInternet() }
    }
}
