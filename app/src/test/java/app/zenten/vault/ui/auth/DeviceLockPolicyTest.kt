package app.zenten.vault.ui.auth

import androidx.biometric.BiometricManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceLockPolicyTest {
    @Test
    fun `available device authentication starts locked`() {
        assertEquals(
            AppUnlockState.Locked,
            DeviceLockPolicy.stateFor(BiometricManager.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `missing or unavailable authentication fails closed`() {
        listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        ).forEach { result ->
            assertEquals(AppUnlockState.DeviceLockRequired, DeviceLockPolicy.stateFor(result))
        }
    }

    @Test
    fun `unlocked session remains open inside thirty second grace period`() {
        assertFalse(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = 1_000L, now = 30_999L))
    }

    @Test
    fun `session relocks at grace boundary`() {
        assertTrue(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = 1_000L, now = 31_000L))
    }

    @Test
    fun `cold or previously locked session always authenticates`() {
        assertTrue(DeviceLockPolicy.requiresAuthentication(false, backgroundAt = 1_000L, now = 1_001L))
        assertTrue(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = null, now = 1_001L))
    }
}
